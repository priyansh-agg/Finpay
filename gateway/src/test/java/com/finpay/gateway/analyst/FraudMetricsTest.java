package com.finpay.gateway.analyst;

import com.finpay.gateway.groundtruth.GroundTruthResolver;
import com.finpay.gateway.order.Order;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.payment.PaymentService;
import com.finpay.gateway.settlement.SettlementService;
import com.finpay.gateway.sim.SimulationScenario;
import com.finpay.gateway.sim.SimulationService;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Precision/recall/false-positive-rate against the hidden ground truth (interview sprint
 * Day 10 — the last item the original audit's §D.2 verdict listed: "per-run metrics
 * (precision, recall, FP rate, review reduction, fraud caught, false auto-clears)"). Reviewed
 * as EVALUATION ONLY, never a decision input.
 *
 * <p>Six deliberately distinct outcomes, one of each kind the pipeline can produce, so the
 * exact arithmetic is pinned down rather than merely "looks plausible":
 * <ul>
 *   <li>fraud caught by review (rejected before capture)</li>
 *   <li>fraud caught by write-off (captured, then withheld from settlement — Day 9, the closer
 *       for Day 7's auto-capture-bypass finding)</li>
 *   <li>fraud missed (captured, ground truth resolved, no write-off run yet — a real, current gap)</li>
 *   <li>legit correctly cleared</li>
 *   <li>legit wrongly rejected (a false positive — friction with no fraud behind it)</li>
 *   <li>legit declined by the simulated processor for an ordinary reason (never the fraud
 *       pipeline's doing) — must NOT be counted as either a catch or a false positive</li>
 * </ul>
 */
class FraudMetricsTest extends AbstractIntegrationTest {

    @Autowired TestFixtures fixtures;
    @Autowired PaymentService payments;
    @Autowired SettlementService settlements;
    @Autowired GroundTruthResolver groundTruthResolver;
    @Autowired AutoClearMetricsService metrics;
    @Autowired SimulationService sim;
    @Autowired TransactionTemplate tx;

    @Test
    void fivePaymentOutcomes_produceExactPrecisionRecallAndFalsePositiveRate() {
        UUID merchantId = fixtures.newMerchant();

        // fraud, caught by review: an adverse pattern (new device + new IP + large amount)
        // lands HELD, then a human rejects it.
        Payment caughtByReview = heldAdversePayment(merchantId, 900_000);
        payments.resolveReview(caughtByReview.getId(), false, "HUMAN", "reject");
        forceGroundTruthFraud(caughtByReview.getId());

        // fraud, caught by write-off: a plain LOW-risk capture, confirmed fraud, then a
        // settlement batch withholds it before it can pay out.
        Payment caughtByWriteOff = fixtures.newCapturedPayment(merchantId, 50_000);
        forceGroundTruthFraud(caughtByWriteOff.getId());

        // legit, correctly cleared.
        Payment legitCleared = fixtures.newCapturedPayment(merchantId, 70_000);
        forceGroundTruthResolved(legitCleared.getId());

        // legit, wrongly rejected: same adverse pattern as above, but genuinely a real
        // customer — rejected anyway (a false positive).
        Payment legitRejected = heldAdversePayment(merchantId, 900_000);
        payments.resolveReview(legitRejected.getId(), false, "HUMAN", "reject");
        forceGroundTruthResolved(legitRejected.getId());

        // Run settlement NOW, before "missed" even exists — only caughtByWriteOff is a
        // confirmed-fraud capture at this point, so only it gets written off.
        settlements.runBatch();

        // fraud, missed: captured, ground truth resolved fraud, but AFTER the only settlement
        // batch this test runs — a real, current gap, not yet closed.
        Payment missed = fixtures.newCapturedPayment(merchantId, 60_000);
        forceGroundTruthFraud(missed.getId());

        // legit, declined by the simulated processor for an ordinary reason — nothing the
        // fraud pipeline did. Must count toward totalLegit but NOT legitWronglyRejected.
        Payment legitDeclined = fixtures.pay(merchantId, fixtures.newOrder(merchantId, 30_000).getId(),
                "CARD", "tok_declined");
        forceGroundTruthResolved(legitDeclined.getId());

        AutoClearMetricsService.FraudMetrics m = metrics.computeFraudMetrics(null);

        assertThat(m.totalFraud()).isEqualTo(3);
        assertThat(m.totalLegit()).isEqualTo(3);
        assertThat(m.fraudCaughtByReview()).isEqualTo(1);
        assertThat(m.fraudCaughtByWriteOff()).isEqualTo(1);
        assertThat(m.fraudCaught()).isEqualTo(2);
        assertThat(m.fraudCaughtMinor()).isEqualTo(900_000 + 50_000);
        assertThat(m.fraudMissed()).isEqualTo(1);
        assertThat(m.fraudMissedMinor()).isEqualTo(60_000);
        assertThat(m.fraudStillPending()).isZero();
        assertThat(m.legitWronglyRejected()).as("the plain processor decline is not counted here").isEqualTo(1);
        assertThat(m.legitCorrectlyCleared()).isEqualTo(1);

        assertThat(m.recallPct()).as("2 caught of 3 actual fraud").isEqualTo(66.67);
        assertThat(m.precisionPct()).as("2 real catches of 3 total blocks (2 fraud + 1 wrongly-rejected legit)")
                .isEqualTo(66.67);
        assertThat(m.falsePositiveRatePct()).as("1 of 3 actual-legit payments wrongly rejected — the plain decline isn't one")
                .isEqualTo(33.33);
    }

    /**
     * A simulation run's warm-up scenery (customer-history seeding) is guaranteed-legit
     * padding, planted with its own ground-truth row like everything else — mixing it into
     * precision/recall/FP-rate would dilute them the same way warm-ups silently inflated
     * "1,000 transactions" to 1,712 payments before interview sprint Day 6 excluded them from
     * every other summary. Same population, not scenery, rule here.
     */
    @Test
    void warmupScenery_isExcludedFromFraudMetrics_matchingPopulationOnly() {
        // small enough that a single resolveDue() pass resolves every ground-truth row it plants
        SimulationScenario scenario = new SimulationScenario(30, 0.3, 0.0, 0.0, 555L);
        SimulationService.SimSummary summary = sim.run(scenario, "test");
        sim.resolve(summary.runId());

        AutoClearMetricsService.FraudMetrics m = metrics.computeFraudMetrics(summary.runId());

        assertThat(m.totalFraud() + m.totalLegit())
                .as("warm-up scenery must never count toward the fraud-metrics population")
                .isEqualTo(summary.populationPayments());
    }

    @Test
    void emptyPopulation_reportsNullRatesRatherThanDivideByZero() {
        AutoClearMetricsService.FraudMetrics m = metrics.computeFraudMetrics(UUID.randomUUID());
        assertThat(m.totalFraud()).isZero();
        assertThat(m.totalLegit()).isZero();
        assertThat(m.recallPct()).isNull();
        assertThat(m.precisionPct()).isNull();
        assertThat(m.falsePositiveRatePct()).isNull();
    }

    /** New device + new IP + a large amount: rules fire, lands HELD (not auto-captured). */
    private Payment heldAdversePayment(UUID merchantId, long amountMinor) {
        Order order = fixtures.newOrder(merchantId, amountMinor);
        return tx.execute(s -> payments.createPayment(new PaymentService.CreatePaymentCommand(
                merchantId, order.getId(), "CARD", "tok_valid", "brand-new-device-" + UUID.randomUUID(),
                "203.0.113." + (1 + (Math.abs(UUID.randomUUID().hashCode()) % 250)))));
    }

    private void forceGroundTruthResolved(UUID paymentId) {
        jdbc.update("UPDATE fraud_ground_truth SET resolve_at = now() WHERE payment_id = ?", paymentId);
        groundTruthResolver.resolveDue();
    }

    private void forceGroundTruthFraud(UUID paymentId) {
        jdbc.update("UPDATE fraud_ground_truth SET intended_class = 'FRAUD', resolve_at = now() WHERE payment_id = ?",
                paymentId);
        groundTruthResolver.resolveDue();
    }
}
