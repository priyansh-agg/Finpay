package com.finpay.gateway.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.groundtruth.GroundTruthLabel;
import com.finpay.gateway.identity.Customer;
import com.finpay.gateway.identity.CustomerRepository;
import com.finpay.gateway.identity.Merchant;
import com.finpay.gateway.identity.MerchantRepository;
import com.finpay.gateway.order.Order;
import com.finpay.gateway.order.OrderService;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.payment.PaymentService;
import com.finpay.gateway.payment.SimulationContext;
import com.finpay.gateway.risk.FeatureStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

/**
 * Phase-3 evaluation harness. Generates a batch of realistic traffic against a dedicated
 * simulation merchant: mostly legitimate payments, plus a minority of fraud injected as
 * <b>patterns</b> (velocity bursts, new-device high-value, large deviation, failed-attempt
 * bursts). Fraudulent transactions are tagged in the hidden ground-truth table so the
 * Auto-Clear Engine can be scored exactly.
 *
 * <p>Because fraud is a pattern (not a magic flag), the rule + ML layers have a real signal,
 * and the "zero false auto-clear" claim is a genuine result rather than a tautology.
 *
 * <p><b>Provenance (interview sprint Day 6).</b> Every call is recorded as a
 * {@link SimulationRun} — the scenario as requested, so the run is reproducible from that row
 * alone — and every payment it creates is tagged with that run's id and whether it is part of
 * the requested population or scenery the generator needed to produce it (a customer's seeded
 * history, the warm-up burst before a velocity-abuse pattern). The report this returns is
 * computed from those tags after the fact, from the database, not accumulated in memory while
 * generating — so it is what actually happened, including payments that failed to create.
 */
@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private final MerchantRepository merchants;
    private final CustomerRepository customers;
    private final OrderService orders;
    private final PaymentService payments;
    private final FeatureStore featureStore;
    private final SimulationRunRepository runs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public SimulationService(MerchantRepository merchants, CustomerRepository customers, OrderService orders,
                             PaymentService payments, FeatureStore featureStore, SimulationRunRepository runs,
                             JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.merchants = merchants;
        this.customers = customers;
        this.orders = orders;
        this.payments = payments;
        this.featureStore = featureStore;
        this.runs = runs;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    /**
     * @param requestedDraws     the scenario's {@code count} — how many transaction attempts
     *                           (legit or fraud) were requested. One fraud draw can expand into
     *                           several actual payments (a velocity-abuse session IS several
     *                           card attempts), so this is not the same number as
     *                           {@code populationPayments} below, and that gap is itself
     *                           informative rather than a bug to paper over.
     * @param populationPayments payments actually created with {@code is_warmup = false} —
     *                           the population this run reports metrics over
     * @param warmupPayments     payments created as scenery ({@code is_warmup = true}):
     *                           customer history, pattern warm-ups. Never counted above, and
     *                           never fed into an auto-clear metric.
     * @param legit / fraud      population-only, from the hidden ground-truth label
     */
    public record SimSummary(
            UUID runId, UUID merchantId, long seed,
            int requestedDraws, double requestedFraudRate,
            int populationPayments, int warmupPayments,
            long legit, long fraud,
            Map<String, Long> bucketCounts, Map<String, Long> statusCounts) {}

    public SimSummary run(SimulationScenario scenario, String createdBy) {
        Random rng = new Random(scenario.seed());
        Merchant merchant = merchants.findByEmail("simulation@finpay.local")
                .orElseGet(() -> merchants.save(new Merchant("Simulation Co", "simulation@finpay.local")));
        UUID merchantId = merchant.getId();

        SimulationRun runRow = new SimulationRun();
        runRow.setMerchantId(merchantId);
        runRow.setSeed(scenario.seed());
        runRow.setScenario(writeJson(scenario));
        runRow.setCreatedBy(createdBy);
        runRow = runs.save(runRow);
        UUID runId = runRow.getId();
        SimulationContext population = SimulationContext.population(runId);
        SimulationContext warmup = SimulationContext.warmup(runId);

        // A fresh pool of customers PER RUN (unique refs) so a confirmed fraud on one
        // simulated customer doesn't blanket-reject every later transaction for them.
        int poolSize = Math.max(20, scenario.count() / 12);
        long[] avgOptions = {15_000, 25_000, 40_000, 60_000, 90_000, 120_000, 200_000, 350_000};
        List<UUID> customerIds = new ArrayList<>();
        long[] avgFor = new long[poolSize];
        for (int i = 0; i < poolSize; i++) {
            String ref = "sim-" + scenario.seed() + "-cust-" + i;
            Customer c = customers.findByMerchantIdAndExternalRef(merchantId, ref)
                    .orElseGet(() -> customers.save(new Customer(merchantId, ref, ref + "@sim.local")));
            customerIds.add(c.getId());
            // realistic tenure (60..800 days) so "new customer" isn't a signal on every row
            jdbc.update("UPDATE customers SET first_seen_at = now() - (? || ' days')::interval WHERE id = ?",
                    60 + rng.nextInt(740), c.getId());
            long avg = avgOptions[rng.nextInt(avgOptions.length)];
            avgFor[i] = avg;
            // seed 3 normal captures so the rolling average is populated (each a separate session)
            for (int k = 0; k < 3; k++) {
                featureStore.resetVelocity(c.getId());
                pay(merchantId, c.getId(), jitter(rng, avg, 0.2), "tok_valid", "sim-dev-home-" + scenario.seed() + "-" + i,
                        "10.1." + (i / 256) + "." + (i % 256), GroundTruthLabel.legit("history_seed"), warmup);
            }
            featureStore.resetVelocity(c.getId());
        }

        for (int i = 0; i < scenario.count(); i++) {
            int ci = rng.nextInt(customerIds.size());
            UUID customerId = customerIds.get(ci);
            long avg = avgFor[ci];
            boolean isFraud = rng.nextDouble() < scenario.fraudRate();
            // each logical transaction is a fresh session unless it is a deliberate burst
            featureStore.resetVelocity(customerId);
            if (isFraud) {
                fraudTxn(rng, merchantId, customerId, avg, population, warmup);
            } else {
                pay(merchantId, customerId, jitter(rng, avg, 0.4),
                        "tok_valid", "sim-dev-home-" + ci, "10.0.0." + ci, GroundTruthLabel.legit("regular"), population);
            }
        }

        SimSummary summary = summarize(runId, merchantId, scenario);
        runRow.setCompletedAt(Instant.now());
        runRow.setSummary(writeJson(summary));
        runs.save(runRow);

        log.info("simulation {} done: requested {} draws ({}% fraud) -> {} population payments, {} warm-up; "
                        + "buckets={} statuses={}",
                runId, scenario.count(), Math.round(scenario.fraudRate() * 100),
                summary.populationPayments(), summary.warmupPayments(), summary.bucketCounts(), summary.statusCounts());
        return summary;
    }

    /** Everything this run actually produced, read back from the database it wrote to. */
    private SimSummary summarize(UUID runId, UUID merchantId, SimulationScenario scenario) {
        long populationCount = count("SELECT count(*) FROM payments WHERE run_id = ? AND is_warmup = false", runId);
        long warmupCount = count("SELECT count(*) FROM payments WHERE run_id = ? AND is_warmup = true", runId);
        long legit = count("""
                SELECT count(*) FROM payments p JOIN fraud_ground_truth g ON g.payment_id = p.id
                WHERE p.run_id = ? AND p.is_warmup = false AND g.intended_class = 'LEGIT'
                """, runId);
        long fraud = count("""
                SELECT count(*) FROM payments p JOIN fraud_ground_truth g ON g.payment_id = p.id
                WHERE p.run_id = ? AND p.is_warmup = false AND g.intended_class = 'FRAUD'
                """, runId);

        Map<String, Long> buckets = new LinkedHashMap<>();
        for (var row : jdbc.queryForList("""
                SELECT risk_bucket, count(*) AS c FROM payments
                WHERE run_id = ? AND is_warmup = false AND risk_bucket IS NOT NULL
                GROUP BY risk_bucket""", runId)) {
            buckets.put((String) row.get("risk_bucket"), ((Number) row.get("c")).longValue());
        }
        Map<String, Long> statuses = new LinkedHashMap<>();
        for (var row : jdbc.queryForList("""
                SELECT status, count(*) AS c FROM payments
                WHERE run_id = ? AND is_warmup = false GROUP BY status""", runId)) {
            statuses.put((String) row.get("status"), ((Number) row.get("c")).longValue());
        }

        return new SimSummary(runId, merchantId, scenario.seed(), scenario.count(), scenario.fraudRate(),
                (int) populationCount, (int) warmupCount, legit, fraud, buckets, statuses);
    }

    /**
     * Injects one fraudulent "session" as a realistic pattern, and says so in ground truth.
     *
     * <p>What makes these transactions fraud is the label the harness writes, not a special
     * card token — the fraud itself pays with {@code tok_valid}, because a stolen card
     * authorises. Every FRAUD-labelled payment here is population (it is the signal the run
     * exists to produce); the warm-up transactions that set the scene — a velocity burst needs
     * seven clean payments first to push the rule past its threshold, a failed-attempt burst
     * needs several declines before the "successful" fraud — are warm-up, tagged and excluded
     * from every metric (interview sprint Day 6; previously they were indistinguishable from
     * the population they were built to produce).
     *
     * <p>Every pattern here is obvious by design — it trips rules — so its disguise level is 0.
     * Disguised ("stealth") fraud arrives with the scenario engine's second half, Day 7.
     */
    private void fraudTxn(Random rng, UUID merchantId, UUID customerId, long avg,
                          SimulationContext population, SimulationContext warmup) {
        int pattern = rng.nextInt(4);
        switch (pattern) {
            case 0 -> { // velocity burst from a fresh device
                String dev = "sim-dev-fraud-" + rng.nextInt(1_000_000);
                // 7 warm-up txns push velocity5m well past the rule threshold (>5) FIRST, so
                // every fraud-labelled txn that follows carries a detectable velocity signal.
                for (int k = 0; k < 7; k++) {
                    pay(merchantId, customerId, jitter(rng, avg, 0.3), "tok_valid", dev, randIp(rng),
                            GroundTruthLabel.legit("fraud_session_context"), warmup);
                }
                for (int k = 0; k < 3 + rng.nextInt(4); k++) {
                    pay(merchantId, customerId, jitter(rng, avg, 0.3), "tok_valid", dev, randIp(rng),
                            GroundTruthLabel.fraud("velocity_abuse", 0.0), population);
                }
            }
            case 1 -> // new device + high value
                    pay(merchantId, customerId, avg * (4 + rng.nextInt(8)), "tok_valid",
                            "sim-dev-fraud-" + rng.nextInt(1_000_000), randIp(rng),
                            GroundTruthLabel.fraud("new_device_high_value", 0.0), population);
            case 2 -> // large deviation from norm
                    pay(merchantId, customerId, (long) (avg * (3.5 + rng.nextDouble() * 6)), "tok_valid",
                            "sim-dev-home-shared", randIp(rng), GroundTruthLabel.fraud("amount_deviation", 0.0), population);
            default -> { // failed-attempt burst then success
                for (int k = 0; k < 4 + rng.nextInt(4); k++) {
                    pay(merchantId, customerId, jitter(rng, avg, 0.2), "tok_declined",
                            "sim-dev-fraud-x", randIp(rng), GroundTruthLabel.legit("fraud_session_context"), warmup);
                }
                pay(merchantId, customerId, jitter(rng, avg, 0.2), "tok_valid", "sim-dev-fraud-x", randIp(rng),
                        GroundTruthLabel.fraud("failed_attempt_burst", 0.0), population);
            }
        }
    }

    private Payment pay(UUID merchantId, UUID customerId, long amount, String token, String device, String ip,
                        GroundTruthLabel label, SimulationContext ctx) {
        try {
            return tx.execute(s -> {
                Customer c = customers.findById(customerId).orElseThrow();
                Order o = orders.create(merchantId, c.getExternalRef(), c.getEmail(), Math.max(amount, 1000),
                        "INR", "sim", Map.of());
                return payments.createPayment(new PaymentService.CreatePaymentCommand(
                        merchantId, o.getId(), "CARD", token, device, ip, label, ctx));
            });
        } catch (RuntimeException e) {
            log.debug("sim payment failed: {}", e.toString());
            return null;
        }
    }

    private long jitter(Random rng, long base, double pct) {
        double f = 1 + (rng.nextDouble() * 2 - 1) * pct;
        return Math.max(1000, (long) (base * f));
    }

    private String randIp(Random rng) {
        return (1 + rng.nextInt(223)) + "." + rng.nextInt(256) + "." + rng.nextInt(256) + "." + (1 + rng.nextInt(254));
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialise simulation run data", e);
        }
    }
}
