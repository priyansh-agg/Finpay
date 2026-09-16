package com.finpay.gateway.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.analyst.AnalystReview;
import com.finpay.gateway.analyst.AnalystReviewRepository;
import com.finpay.gateway.analyst.AutoClearPolicy;
import com.finpay.gateway.analyst.ReviewDecider;
import com.finpay.gateway.analyst.SimulatedAnalystDecider;
import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.groundtruth.FraudGroundTruthRepository;
import com.finpay.gateway.groundtruth.GroundTruthLabel;
import com.finpay.gateway.groundtruth.GroundTruthResolver;
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
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * <p><b>Provenance (interview sprint Day 6).</b> Every call is recorded as a
 * {@link SimulationRun} — the scenario as requested, so the run is reproducible from that row
 * alone — and every payment it creates is tagged with that run's id and whether it is part of
 * the requested population or scenery the generator needed to produce it.
 *
 * <p><b>The two hard populations (Day 7).</b> The original four fraud patterns all trip a
 * rule by design, and the original "regular" legit pattern never does — which means none of
 * them was ever actually eligible to be auto-cleared or wrongly rejected in the first place,
 * and "zero false auto-clears" / "review reduction" were being claimed over a population that
 * could not produce the failure either was meant to measure. Two more patterns fix that:
 * {@code stealthRate} controls fraud disguised well enough to carry no rule signal at all
 * (the only population the zero-false-auto-clear claim means anything over), and
 * {@code fraudShapedLegitRate} controls genuine customers whose real behaviour trips a rule
 * anyway (the population the review-reduction number is actually about).
 *
 * <p><b>Determinism.</b> Every identifier this run creates or touches — the customer pool's
 * external refs, every device fingerprint — is scoped by this run's own id, not by the
 * scenario's seed. Two runs with the SAME seed used to reuse the SAME customer rows (Postgres
 * looks external refs up by {@code "sim-<seed>-cust-<i>"}) and could draw the SAME "random"
 * device fingerprint strings (a fixed seed reproduces the same {@code Random} sequence) —
 * so a second run with the same seed silently inherited the first run's velocity counters,
 * seen-device history and device→account associations from Redis, and its outcome could
 * differ from the first run's for reasons that had nothing to do with the scenario. Making
 * every identifier unique per run removes the leak at its source, rather than requiring an
 * operator to remember to flush Redis between runs (an explicit {@link #reset()} still exists,
 * for the one thing that is deliberately NOT run-scoped — see its own docs).
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
    private final AnalystReviewRepository reviews;
    private final ReviewDecider reviewDecider;
    private final SimulatedAnalystDecider simulatedAnalystDecider;
    private final FraudGroundTruthRepository groundTruth;
    private final GroundTruthResolver groundTruthResolver;
    private final AutoClearPolicy autoClearPolicy;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public SimulationService(MerchantRepository merchants, CustomerRepository customers, OrderService orders,
                             PaymentService payments, FeatureStore featureStore, SimulationRunRepository runs,
                             AnalystReviewRepository reviews, ReviewDecider reviewDecider,
                             SimulatedAnalystDecider simulatedAnalystDecider,
                             FraudGroundTruthRepository groundTruth, GroundTruthResolver groundTruthResolver,
                             AutoClearPolicy autoClearPolicy, StringRedisTemplate redis,
                             JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.merchants = merchants;
        this.customers = customers;
        this.orders = orders;
        this.payments = payments;
        this.featureStore = featureStore;
        this.runs = runs;
        this.reviews = reviews;
        this.reviewDecider = reviewDecider;
        this.simulatedAnalystDecider = simulatedAnalystDecider;
        this.groundTruth = groundTruth;
        this.groundTruthResolver = groundTruthResolver;
        this.autoClearPolicy = autoClearPolicy;
        this.redis = redis;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    /**
     * @param requestedDraws     the scenario's {@code count} — how many transaction attempts
     *                           were requested. One fraud draw can expand into several actual
     *                           payments (a velocity-abuse session IS several card attempts),
     *                           so this is not the same number as {@code populationPayments}.
     * @param populationPayments payments actually created with {@code is_warmup = false} —
     *                           the population this run reports metrics over
     * @param warmupPayments     payments created as scenery ({@code is_warmup = true}):
     *                           customer history, pattern warm-ups. Never counted above.
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
        // Every fingerprint this run mints is prefixed with a slice of its own id, so it can
        // never collide with a fingerprint from any other run — see the class-level note on
        // determinism. Customer external refs get the same treatment just below.
        String devTag = runId.toString().substring(0, 8);

        // A fresh pool of customers PER RUN so a confirmed fraud on one simulated customer
        // doesn't blanket-reject every later transaction for them, AND so two runs never
        // share a customer (and therefore never share that customer's Redis state) even when
        // they share a seed.
        int poolSize = Math.max(20, scenario.count() / 12);
        long[] avgOptions = {15_000, 25_000, 40_000, 60_000, 90_000, 120_000, 200_000, 350_000};
        List<UUID> customerIds = new ArrayList<>();
        long[] avgFor = new long[poolSize];
        for (int i = 0; i < poolSize; i++) {
            String ref = "sim-" + runId + "-cust-" + i;
            Customer c = customers.findByMerchantIdAndExternalRef(merchantId, ref)
                    .orElseGet(() -> customers.save(new Customer(merchantId, ref, ref + "@sim.local")));
            customerIds.add(c.getId());
            // realistic tenure (60..800 days) so "new customer" isn't a signal on every row
            jdbc.update("UPDATE customers SET first_seen_at = now() - (? || ' days')::interval WHERE id = ?",
                    60 + rng.nextInt(740), c.getId());
            long avg = avgOptions[rng.nextInt(avgOptions.length)];
            avgFor[i] = avg;
            // Seed 3 normal captures on the SAME device/IP this customer's later population
            // draws use (homeDevice/homeIp below) — otherwise a customer's first population
            // draw looks like a brand-new device by accident, which used to happen here.
            for (int k = 0; k < 3; k++) {
                featureStore.resetVelocity(c.getId());
                pay(merchantId, c.getId(), jitter(rng, avg, 0.2), "tok_valid", homeDevice(devTag, i), homeIp(i),
                        GroundTruthLabel.legit("history_seed"), warmup);
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
                if (rng.nextDouble() < scenario.stealthRate()) {
                    stealthFraudTxn(rng, merchantId, customerId, ci, devTag, avg, population);
                } else {
                    fraudTxn(rng, merchantId, customerId, devTag, avg, population, warmup);
                }
            } else if (rng.nextDouble() < scenario.fraudShapedLegitRate()) {
                fraudShapedLegitTxn(rng, merchantId, customerId, devTag, population);
            } else {
                pay(merchantId, customerId, jitter(rng, avg, 0.4), "tok_valid", homeDevice(devTag, ci), homeIp(ci),
                        GroundTruthLabel.legit("regular"), population);
            }
        }

        SimSummary summary = summarize(runId, merchantId, scenario);
        runRow.setCompletedAt(Instant.now());
        runRow.setSummary(writeJson(summary));
        runs.save(runRow);

        log.info("simulation {} done: requested {} draws ({}% fraud, {}% stealth, {}% fraud-shaped-legit) -> "
                        + "{} population payments, {} warm-up; buckets={} statuses={}",
                runId, scenario.count(), Math.round(scenario.fraudRate() * 100), Math.round(scenario.stealthRate() * 100),
                Math.round(scenario.fraudShapedLegitRate() * 100),
                summary.populationPayments(), summary.warmupPayments(), summary.bucketCounts(), summary.statusCounts());
        return summary;
    }

    /**
     * Force this run to a fully-decided, fully-resolved state right now, instead of waiting
     * on the wall clock (ground truth's simulated dispute window) or the review-queue
     * scheduler — which is disabled outright under the test profile, and which a live demo
     * shouldn't need to wait on either. Order matters: reviews must be decided (so their
     * {@code autoclear_decisions} rows exist) BEFORE ground truth resolves, because resolution
     * is what reconciles those rows against the now-known label.
     *
     * <p>Since interview sprint Day 8, {@link ReviewDecider} only PROPOSES auto-clear — a
     * review it declines stays PENDING for a real human. A simulation run has none, so this
     * hands anything still PENDING after the proposer to {@link SimulatedAnalystDecider}
     * directly (bypassing its own latency and claim checks — resolving a run on demand isn't
     * a demo of response time), the same demo-only path a live queue with nobody at the
     * keyboard uses, so a run this method resolves is never left half-decided.
     */
    public SimSummary resolve(UUID runId) {
        SimulationRun row = runs.findById(runId).orElseThrow(() -> ApiException.notFound("Simulation run"));

        for (AnalystReview r : reviews.findByRunIdAndQueueStatus(runId, AnalystReview.QueueStatus.PENDING)) {
            try {
                reviewDecider.decide(r.getId());
            } catch (Exception e) {
                log.warn("could not propose auto-clear for review {} while resolving run {}: {}", r.getId(), runId, e.toString());
            }
        }
        for (AnalystReview r : reviews.findByRunIdAndQueueStatus(runId, AnalystReview.QueueStatus.PENDING)) {
            try {
                simulatedAnalystDecider.decide(r.getId());
            } catch (Exception e) {
                log.warn("simulated analyst could not decide review {} while resolving run {}: {}", r.getId(), runId, e.toString());
            }
        }
        groundTruth.forceResolutionWindow(runId);
        resolveGroundTruthFully(runId);

        SimSummary summary = summarize(runId, row.getMerchantId(), readScenario(row));
        row.setSummary(writeJson(summary));
        runs.save(row);
        return summary;
    }

    /**
     * {@link GroundTruthResolver#resolveDue()} is a bounded, global sweep (100 rows per call,
     * not scoped to a run) meant for its own steady 5s scheduled tick under ordinary load — one
     * call is not guaranteed to resolve everything a single large run just planted. A scenario
     * whose population + warm-up rows exceed that page size left some of this run's ground
     * truth silently {@code resolved = false}, which {@link AutoClearMetricsService} and every
     * other resolved-only reader would then just quietly skip — not wrong, just incomplete,
     * and "resolve this run right now" is supposed to mean ALL of it. Loop until this run has
     * none left, capped so a genuine problem elsewhere fails loudly instead of spinning forever.
     */
    private void resolveGroundTruthFully(UUID runId) {
        int maxPasses = 50;
        for (int pass = 0; pass < maxPasses; pass++) {
            groundTruthResolver.resolveDue();
            Long stillDue = jdbc.queryForObject(
                    "SELECT count(*) FROM fraud_ground_truth g JOIN payments p ON p.id = g.payment_id "
                            + "WHERE p.run_id = ? AND g.resolved = false",
                    Long.class, runId);
            if (stillDue == null || stillDue == 0) return;
        }
        log.warn("run {} still has unresolved ground truth after {} resolution passes", runId, maxPasses);
    }

    /**
     * Clears the one piece of state this service deliberately does NOT scope per run: the
     * Redis feature store, and the auto-clear confidence threshold.
     *
     * <p>Both are shared across runs on purpose elsewhere in the system (a real gateway has
     * exactly one feature store and one calibrated threshold, not one per simulation), so
     * unlike customer pools and device fingerprints they cannot simply be made run-unique.
     * The threshold in particular only ever ratchets UP after a confirmed false auto-clear
     * (architecture-freeze Doubt 4) — which stealth fraud can now genuinely trigger — so a
     * demo or test that wants two same-seed runs to produce the SAME numbers must call this
     * between them, or the second run's higher threshold is not non-determinism, it is the
     * calibrator doing exactly its job against what the first run left behind.
     *
     * @return how many Redis keys were cleared
     */
    public int reset() {
        var keys = redis.keys("finpay:*");
        int cleared = keys == null ? 0 : keys.size();
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        autoClearPolicy.resetToFloor();
        return cleared;
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
     * Injects one fraudulent "session" as a realistic, RULE-TRIPPING pattern, and says so in
     * ground truth. What makes these transactions fraud is the label the harness writes, not
     * a special card token — the fraud itself pays with {@code tok_valid}, because a stolen
     * card authorises. Every FRAUD-labelled payment here is population; the warm-up
     * transactions that set the scene (seven clean payments to push velocity past its
     * threshold, several declines before the "successful" fraud) are warm-up, tagged and
     * excluded from every metric.
     *
     * <p>Every pattern here is obvious by design — it trips a rule — so its disguise level is
     * 0. That is exactly why none of these four patterns can test "zero false auto-clears":
     * a transaction that trips a rule is either held at a bucket the auto-clear gate never
     * reaches, or rejected outright by the reference analyst's hard pattern checks, before its
     * ML confidence is ever asked. See {@link #stealthFraudTxn} for the population that can.
     */
    private void fraudTxn(Random rng, UUID merchantId, UUID customerId, String devTag, long avg,
                          SimulationContext population, SimulationContext warmup) {
        int pattern = rng.nextInt(4);
        switch (pattern) {
            case 0 -> { // velocity burst from a fresh device
                String dev = "sim-dev-" + devTag + "-fraud-" + rng.nextInt(1_000_000);
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
                            "sim-dev-" + devTag + "-fraud-" + rng.nextInt(1_000_000), randIp(rng),
                            GroundTruthLabel.fraud("new_device_high_value", 0.0), population);
            case 2 -> // large deviation from norm
                    pay(merchantId, customerId, (long) (avg * (3.5 + rng.nextDouble() * 6)), "tok_valid",
                            "sim-dev-" + devTag + "-home-shared", randIp(rng),
                            GroundTruthLabel.fraud("amount_deviation", 0.0), population);
            default -> { // failed-attempt burst then success
                String dev = "sim-dev-" + devTag + "-fraud-x";
                for (int k = 0; k < 4 + rng.nextInt(4); k++) {
                    pay(merchantId, customerId, jitter(rng, avg, 0.2), "tok_declined", dev, randIp(rng),
                            GroundTruthLabel.legit("fraud_session_context"), warmup);
                }
                pay(merchantId, customerId, jitter(rng, avg, 0.2), "tok_valid", dev, randIp(rng),
                        GroundTruthLabel.fraud("failed_attempt_burst", 0.0), population);
            }
        }
    }

    /**
     * Fraud crafted to carry no rule signal: the customer's own known device, their own known
     * IP, an amount close to their own average — everything a "stealth" transaction has to
     * get right is simply to look exactly like the "regular" legit pattern below, because
     * that population is the only thing it is compared against by the risk engine. No warm-up
     * transactions either; unlike the obvious patterns, this one has no scene to set. If this
     * lands in a bucket the Auto-Clear Engine reaches and the reference analyst has no adverse
     * pattern to reject it on (neither does, by construction), it is genuinely eligible to be
     * auto-cleared — and if it is, it is a real false auto-clear once ground truth resolves,
     * which is the entire point of this population existing: "zero false auto-clears" is
     * either true against transactions that could actually slip through, or it isn't a claim
     * about anything.
     */
    private void stealthFraudTxn(Random rng, UUID merchantId, UUID customerId, int ci, String devTag, long avg,
                                 SimulationContext population) {
        pay(merchantId, customerId, jitter(rng, avg, 0.25), "tok_valid", homeDevice(devTag, ci), homeIp(ci),
                GroundTruthLabel.fraud("stealth", 1.0), population);
    }

    /**
     * A genuine customer whose real behaviour trips a rule anyway: established (tenure comes
     * from the seeded pool, so this is never a brand-new account), but buying from a device
     * and IP that has never seen them before — travelling, a new laptop, a borrowed connection
     * — at an amount large enough to trip NEW_DEVICE_NEW_IP (over ₹1,500) but decoupled from
     * their own average on purpose: this is about an unfamiliar CONTEXT, not an unusual
     * AMOUNT (that is {@code fraudTxn}'s "amount_deviation" pattern). The amount is drawn wide
     * enough (₹1,500–₹2,700) to sometimes clear ReferenceAnalyst's hard "new device + new IP
     * on a large amount" reject threshold and sometimes not — both outcomes are real and worth
     * showing: a false positive that gets correctly cleared, and one that does not.
     */
    private void fraudShapedLegitTxn(Random rng, UUID merchantId, UUID customerId, String devTag,
                                     SimulationContext population) {
        long amount = 150_000 + rng.nextInt(120_000);
        pay(merchantId, customerId, amount, "tok_valid",
                "sim-dev-" + devTag + "-travel-" + rng.nextInt(1_000_000), randIp(rng),
                GroundTruthLabel.legit("fraud_shaped_travel"), population);
    }

    private String homeDevice(String devTag, int ci) {
        return "sim-dev-" + devTag + "-home-" + ci;
    }

    private String homeIp(int ci) {
        return "10.0.0." + ci;
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

    private SimulationScenario readScenario(SimulationRun row) {
        try {
            return json.readValue(row.getScenario(), SimulationScenario.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt stored scenario for run " + row.getId(), e);
        }
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialise simulation run data", e);
        }
    }
}
