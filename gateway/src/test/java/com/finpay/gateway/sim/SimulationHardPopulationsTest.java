package com.finpay.gateway.sim;

import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two populations that make the Auto-Clear USP measurable (interview sprint Day 7), and
 * the determinism that makes a scenario reproducible enough to demo.
 *
 * <p>The original four fraud patterns all trip a rule by design, and "regular" legit traffic
 * never does — so neither could ever produce a false auto-clear or a wrongly-rejected false
 * positive. "Zero false auto-clears" and "review reduction" were being reported without a
 * population that could actually fail either claim.
 */
class SimulationHardPopulationsTest extends AbstractIntegrationTest {

    @Autowired SimulationService sim;
    @Autowired StringRedisTemplate redis;
    @Autowired TestFixtures fixtures;

    /**
     * The single most important finding of the day. A stealth-fraud transaction is built to
     * carry literally no rule signal — that is the whole premise: it must be indistinguishable
     * from the "regular" legit pattern. With no ML sidecar reachable (the test profile points
     * at a closed port) and no rule flags, its score is exactly 0 — comfortably under
     * {@code auto-capture-max} (6 in production, same value here). That does not send it to
     * review where the Auto-Clear Engine could accept or refuse it: {@code PaymentService}
     * auto-CAPTURES anything that confidently LOW on the spot, before the review queue, the
     * reference analyst or {@code autoclear_decisions} ever see it.
     *
     * <p>So the existing "false auto-clears" metric — counted from {@code autoclear_decisions}
     * — cannot see this loss at all. A stealth-fraud payment can be confirmed fraudulent after
     * resolution while the metric that is supposed to catch exactly that stays at zero,
     * because this money never took the path that metric watches. This is not a bug in the
     * scenario; {@code auto-capture-max} exists precisely to skip review for confidently-LOW
     * transactions (architecture-freeze Doubt 4) — a perfect stealth fraud satisfies that
     * bar by construction. It is the reason interview-sprint Day 9 (settlement holdback) has
     * to cover auto-CAPTURED funds, not only auto-CLEARED ones.
     */
    @Test
    void stealthFraud_bypassesReviewEntirely_viaTheLowRiskAutoCaptureShortcut() {
        SimulationScenario scenario = new SimulationScenario(60, 0.3, 1.0, 0.0, 909L);
        SimulationService.SimSummary summary = sim.run(scenario, "test");

        List<Map<String, Object>> stealthRows = jdbc.queryForList("""
                SELECT p.id, p.status, p.risk_bucket, re.rule_score
                FROM payments p
                JOIN fraud_ground_truth g ON g.payment_id = p.id
                JOIN risk_evaluations re ON re.payment_id = p.id
                WHERE p.run_id = ? AND g.scenario_pattern = 'stealth'
                """, summary.runId());
        assertThat(stealthRows).as("the scenario actually drew some stealth fraud").isNotEmpty();

        for (var row : stealthRows) {
            assertThat(((Number) row.get("rule_score")).doubleValue())
                    .as("a stealth transaction that trips a rule isn't stealth").isZero();
            assertThat(row.get("risk_bucket")).isEqualTo("LOW");
            assertThat(row.get("status")).as("confidently-LOW auto-captures before review, not after it")
                    .isEqualTo("CAPTURED");
        }

        UUID onePaymentId = (UUID) stealthRows.get(0).get("id");
        assertThat(count("SELECT count(*) FROM analyst_reviews WHERE payment_id = ?", onePaymentId))
                .as("it never entered the review queue").isZero();
        assertThat(count("SELECT count(*) FROM autoclear_decisions WHERE payment_id = ?", onePaymentId))
                .as("the Auto-Clear Engine never saw it, so it cannot be counted as a false auto-clear").isZero();

        // Resolve and confirm the loss is real, even though the safety metric never recorded it.
        SimulationService.SimSummary resolved = sim.resolve(summary.runId());
        long confirmedStealthFraud = count("""
                SELECT count(*) FROM payments p JOIN fraud_ground_truth g ON g.payment_id = p.id
                WHERE p.run_id = ? AND g.scenario_pattern = 'stealth'
                  AND g.resolved = true AND g.actually_fraudulent = true AND p.status = 'CAPTURED'
                """, summary.runId());
        assertThat(confirmedStealthFraud).as("real, confirmed fraud sitting in CAPTURED — a real loss").isPositive();
        assertThat(count("SELECT count(*) FROM autoclear_decisions WHERE auto_cleared = true AND later_confirmed_fraud = true"))
                .as("the false-auto-clears metric (from autoclear_decisions) still shows none of it").isZero();
        assertThat(resolved).isNotNull();
    }

    /**
     * A genuine customer, travelling — new device, new IP, a real amount — trips
     * NEW_DEVICE_NEW_IP and lands in review. Unlike stealth fraud this is exactly what the
     * risk engine is supposed to catch; the interesting question is what happens next.
     */
    @Test
    void fraudShapedLegit_reachesReview_andSplitsBetweenCorrectlyClearedAndWronglyRejected() {
        SimulationScenario scenario = new SimulationScenario(80, 0.0, 0.0, 1.0, 606L);
        SimulationService.SimSummary summary = sim.run(scenario, "test");

        List<UUID> paymentIds = jdbc.queryForList("""
                SELECT p.id FROM payments p JOIN fraud_ground_truth g ON g.payment_id = p.id
                WHERE p.run_id = ? AND g.scenario_pattern = 'fraud_shaped_travel'
                """, UUID.class, summary.runId());
        assertThat(paymentIds).as("the scenario actually drew some fraud-shaped-legit traffic").isNotEmpty();

        long reviewed = count("""
                SELECT count(DISTINCT r.payment_id) FROM analyst_reviews r
                JOIN payments p ON p.id = r.payment_id
                JOIN fraud_ground_truth g ON g.payment_id = p.id
                WHERE p.run_id = ? AND g.scenario_pattern = 'fraud_shaped_travel'
                """, summary.runId());
        assertThat(reviewed).as("a real customer tripping a rule is exactly what should reach review")
                .isEqualTo(paymentIds.size());

        SimulationService.SimSummary resolved = sim.resolve(summary.runId());

        // Every FRAUD_SHAPED_TRAVEL payment is legitimate, so its eventual outcome is one of
        // two things: captured (correctly cleared) or voided/failed (wrongly rejected).
        // is_warmup = false is load-bearing here: without it this also counts the 60
        // customer-history-seed captures, which are population-excluded scenery, not outcomes
        // of this pattern (a first version of this test missed it and asserted 140 == 80).
        long cleared = count("SELECT count(*) FROM payments WHERE run_id = ? AND is_warmup = false AND status = 'CAPTURED'",
                summary.runId());
        long rejected = count("""
                SELECT count(*) FROM payments WHERE run_id = ? AND is_warmup = false AND status IN ('VOIDED', 'FAILED')
                """, summary.runId());
        assertThat(cleared + rejected).isEqualTo(paymentIds.size());
        // Neither outcome is asserted alone: a scenario that always clears, or always rejects,
        // would not be demonstrating the trade-off this population exists to show.
        assertThat(cleared).as("some genuinely legitimate travel is correctly cleared").isGreaterThan(0);
        assertThat(rejected).as("the reference analyst's hard pattern checks genuinely reject some of it too")
                .isGreaterThan(0);
        assertThat(resolved.legit()).isEqualTo(paymentIds.size());
        assertThat(resolved.fraud()).isZero();
    }

    /**
     * The exit criterion: same seed, same population, same metrics, twice in a row — with NO
     * reset() between the runs. This is what proves the fix (run-scoped customer pools and
     * device fingerprints — see {@link SimulationService}'s class doc) rather than merely
     * asserting it: before it, a second run with the same seed reused the first run's customer
     * rows (same external ref) and could redraw the same "random" device fingerprint strings,
     * so its outcome depended on what the first run had already done to Redis.
     */
    @Test
    void sameSeed_producesTheSamePopulation_twiceInARow_withNoResetNeeded() {
        SimulationScenario scenario = new SimulationScenario(50, 0.25, 0.5, 0.4, 314159L);

        SimulationService.SimSummary first = sim.run(scenario, "test");
        SimulationService.SimSummary second = sim.run(scenario, "test");

        assertThat(second.populationPayments()).isEqualTo(first.populationPayments());
        assertThat(second.warmupPayments()).isEqualTo(first.warmupPayments());
        assertThat(second.legit()).isEqualTo(first.legit());
        assertThat(second.fraud()).isEqualTo(first.fraud());
        assertThat(second.bucketCounts()).isEqualTo(first.bucketCounts());
        assertThat(second.statusCounts()).isEqualTo(first.statusCounts());

        // And the mechanism: no device fingerprint from either run appears in the other.
        Set<String> devicesFirst = deviceFingerprintsFor(first.runId());
        Set<String> devicesSecond = deviceFingerprintsFor(second.runId());
        assertThat(devicesFirst).isNotEmpty();
        assertThat(devicesSecond).hasSameSizeAs(devicesFirst);
        assertThat(java.util.stream.Stream.concat(devicesFirst.stream(), devicesSecond.stream())
                .distinct().count()).as("no fingerprint collides across the two runs")
                .isEqualTo(devicesFirst.size() + devicesSecond.size());
    }

    /**
     * What {@code reset()} is actually for: NOT basic reproducibility (proven above without
     * it), but the state that stays deliberately global across runs — the auto-clear
     * threshold (only ever ratchets up) and the Redis feature store — plus one more that
     * {@code reset()}'s own javadoc never mentioned until interview sprint Day 14: the
     * pgvector confirmed-fraud case store ({@link com.finpay.gateway.risk.FraudVectorStore}).
     * Found while writing a demo script meant to be exactly reproducible by seed —
     * {@code GroundTruthResolver} writes every confirmed fraud into {@code fraud_case_vectors}
     * (global, never run-scoped, by design — a real fraud case library should keep growing),
     * and {@link com.finpay.gateway.risk.FraudVectorStore#avgSimilarityToKnownFraud} starts
     * contributing to every later risk score the moment that table crosses 25 rows. Two runs
     * of the identical seed, {@code reset()} called between them, produced the same population
     * and the same fraud/legit split (proving the run-scoping fix from Day 7 still holds) but
     * a different risk-bucket distribution — the first run had a CRITICAL-bucket payment and
     * the second didn't, because the second started with the first run's confirmed fraud
     * already sitting in the similarity index. That's not the calibrator "doing its job" the
     * way the ratchet is — it's state {@code reset()} claimed to clear and silently didn't.
     */
    @Test
    void reset_putsTheRatchetedThresholdBackToItsFloor_clearsTheFeatureStore_andClearsConfirmedFraudCases() {
        // Reproduce a ratchet the way AutoClearCalibrator actually leaves one, without
        // needing a real false auto-clear: go straight at the config row it writes.
        jdbc.update("INSERT INTO system_config(key, value) VALUES ('autoclear.threshold', '0.95') "
                + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value");
        redis.opsForValue().set("finpay:vel:reset-test-marker", "1");
        assertThat(count("SELECT count(*) FROM system_config WHERE key = 'autoclear.threshold' AND value = '0.95'"))
                .isEqualTo(1);

        UUID merchantId = fixtures.newMerchant();
        UUID paymentId = fixtures.newCapturedPayment(merchantId, 10_000).getId();
        String zeroVector = "[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]";
        jdbc.update("INSERT INTO fraud_case_vectors (payment_id, feature_vector, fraud_type) VALUES (?, ?::vector, ?)",
                paymentId, zeroVector, "velocity_abuse");
        assertThat(count("SELECT count(*) FROM fraud_case_vectors")).isEqualTo(1);

        int cleared = sim.reset();

        assertThat(count("SELECT count(*) FROM system_config WHERE key = 'autoclear.threshold'"))
                .as("the ratchet is gone — threshold() now falls back to its configured floor").isZero();
        assertThat(cleared).as("the marker key (and whatever else was left in the feature store) was cleared")
                .isGreaterThanOrEqualTo(1);
        assertThat(redis.hasKey("finpay:vel:reset-test-marker")).isFalse();
        assertThat(count("SELECT count(*) FROM fraud_case_vectors"))
                .as("the confirmed-fraud similarity index — otherwise invisible non-determinism between two same-seed demo runs — is empty too")
                .isZero();
    }

    private Set<String> deviceFingerprintsFor(UUID runId) {
        return new java.util.HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT device_fingerprint FROM payments WHERE run_id = ? AND device_fingerprint IS NOT NULL",
                String.class, runId));
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
