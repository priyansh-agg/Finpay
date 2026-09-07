package com.finpay.gateway.analyst;

import com.finpay.gateway.groundtruth.GroundTruthResolver;
import com.finpay.gateway.order.Order;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.payment.PaymentRepository;
import com.finpay.gateway.payment.PaymentService;
import com.finpay.gateway.payment.PaymentState;
import com.finpay.gateway.support.AbstractFraudStackTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3, deterministic (no ML sidecar needed):
 *  - a payment with an adverse pattern is HELD, not captured;
 *  - the reference analyst decides it (never the auto-clear engine, since the ML model is absent);
 *  - a confirmed false auto-clear ratchets the confidence threshold up.
 */
class AutoClearFlowTest extends AbstractFraudStackTest {

    @Autowired TestFixtures fixtures;
    @Autowired PaymentService payments;
    @Autowired PaymentRepository paymentRepo;
    @Autowired AnalystReviewRepository reviews;
    @Autowired AutoClearDecisionRepository autoClearDecisions;
    @Autowired ReviewDecider decider;
    @Autowired AutoClearCalibrator calibrator;
    @Autowired AutoClearPolicy policy;
    @Autowired GroundTruthResolver groundTruthResolver;
    @Autowired TransactionTemplate tx;

    @Test
    void adversePattern_isHeld_thenRejectedByReferenceAnalyst_notAutoCleared() {
        UUID merchantId = fixtures.newMerchant();
        // new device + new IP + large amount -> rules fire -> MEDIUM -> HELD;
        // reference analyst rejects "new device + new IP on a non-trivial amount".
        Order order = fixtures.newOrder(merchantId, 900_000);
        Payment p = tx.execute(s -> payments.createPayment(new PaymentService.CreatePaymentCommand(
                merchantId, order.getId(), "CARD", "tok_valid", "brand-new-device", "203.0.113.9")));

        assertThat(p.getStatus()).isIn(PaymentState.HELD, PaymentState.PENDING_REVIEW);
        var review = reviews.findFirstByPaymentIdOrderByCreatedAtDesc(p.getId()).orElseThrow();

        decider.decide(review.getId());

        var decided = reviews.findById(review.getId()).orElseThrow();
        assertThat(decided.getQueueStatus()).isEqualTo(AnalystReview.QueueStatus.DECIDED);
        assertThat(decided.getReviewerType()).isEqualTo("REFERENCE_ANALYST"); // NOT auto-cleared (no ML model)
        assertThat(decided.getDecision()).isEqualTo("REJECT");
        // rejected => funds never captured (VOIDED from HELD, or FAILED from PENDING_REVIEW)
        assertThat(paymentRepo.findById(p.getId()).orElseThrow().getStatus())
                .isIn(PaymentState.VOIDED, PaymentState.FAILED);
        assertThat(autoClearDecisions.countByAutoClearedTrue()).isZero();
    }

    @Test
    void confirmedFalseAutoClear_ratchetsThresholdUp() {
        double before = policy.threshold();

        // plant an auto-clear that ground truth later confirms as fraud
        UUID paymentId = seedCapturedPayment();
        jdbc.update("""
                INSERT INTO autoclear_decisions
                  (payment_id, confidence, threshold, auto_cleared, later_confirmed_fraud)
                VALUES (?, 0.985, 0.80, true, true)
                """, paymentId);

        calibrator.calibrate();

        assertThat(policy.threshold())
                .as("threshold ratchets above the confidence that produced the false clear")
                .isGreaterThan(0.985)
                .isGreaterThan(before);
    }

    private UUID seedCapturedPayment() {
        UUID merchantId = fixtures.newMerchant();
        Order o = fixtures.newOrder(merchantId, 10_000);
        Payment p = tx.execute(s -> payments.createPayment(new PaymentService.CreatePaymentCommand(
                merchantId, o.getId(), "CARD", "tok_valid", "d", "10.0.0.1")));
        return p.getId();
    }
}
