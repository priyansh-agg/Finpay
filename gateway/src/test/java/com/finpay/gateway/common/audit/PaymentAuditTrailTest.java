package com.finpay.gateway.common.audit;

import com.finpay.gateway.analyst.AnalystReview;
import com.finpay.gateway.analyst.AnalystReviewRepository;
import com.finpay.gateway.analyst.ReviewDecider;
import com.finpay.gateway.analyst.SimulatedAnalystDecider;
import com.finpay.gateway.order.Order;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.payment.PaymentRepository;
import com.finpay.gateway.payment.PaymentService;
import com.finpay.gateway.payment.PaymentState;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Before this, nothing about a payment's life — created, failed, held, voided — or a review
 * decision ever reached {@code audit_logs}, whatever entity_type/entity_id you searched for.
 * Only capture, refund, settlement and merchant onboarding were audited; an analyst clearing
 * or rejecting a flagged payment left no record of who decided what, or why.
 */
class PaymentAuditTrailTest extends AbstractIntegrationTest {

    @Autowired TestFixtures fixtures;
    @Autowired PaymentService payments;
    @Autowired PaymentRepository paymentRepo;
    @Autowired AuditLogRepository audit;
    @Autowired AnalystReviewRepository reviews;
    @Autowired ReviewDecider decider;
    @Autowired SimulatedAnalystDecider simulatedAnalyst;
    @Autowired TransactionTemplate tx;

    @Test
    void aCapturedPayment_isAuditedAsCreatedThenCaptured() {
        UUID merchantId = fixtures.newMerchant();
        Payment p = fixtures.newCapturedPayment(merchantId, 20_000);

        // PAYMENT_CAPTURED already existed (doCapture); PAYMENT_CREATED is new. Together they
        // are the whole story for the common case — nothing missing, nothing duplicated.
        List<AuditLog> trail = trailFor(p.getId());
        assertThat(trail).extracting(AuditLog::getAction).containsExactly("PAYMENT_CREATED", "PAYMENT_CAPTURED");

        AuditLog created = trail.get(0);
        assertThat(created.getActorType()).isEqualTo("MERCHANT");
        assertThat(created.getActorId()).isEqualTo(merchantId.toString());
        assertThat(created.getAfterState()).containsEntry("amountMinor", 20_000).containsEntry("method", "CARD");
    }

    @Test
    void aDeclinedPayment_isAuditedAsCreatedThenFailed() {
        UUID merchantId = fixtures.newMerchant();
        Payment p = fixtures.pay(merchantId, fixtures.newOrder(merchantId, 15_000).getId(), "CARD", "tok_declined");
        assertThat(p.getStatus()).isEqualTo(PaymentState.FAILED);

        List<AuditLog> trail = trailFor(p.getId());
        assertThat(trail).extracting(AuditLog::getAction).containsExactly("PAYMENT_CREATED", "PAYMENT_FAILED");

        AuditLog failed = trail.get(1);
        assertThat(failed.getBeforeState()).containsEntry("status", "PROCESSING");
        assertThat(failed.getAfterState()).containsEntry("status", "FAILED").containsEntry("reason", "card_declined");
    }

    @Test
    void aFlaggedPayment_isAuditedAsHeld_thenAsAReviewDecision() {
        // Same adverse pattern AutoClearFlowTest uses: new device + new IP + a non-trivial
        // amount lands MEDIUM/HIGH and is held or sent to PENDING_REVIEW.
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 900_000);
        Payment p = tx.execute(s -> payments.createPayment(new PaymentService.CreatePaymentCommand(
                merchantId, order.getId(), "CARD", "tok_valid", "brand-new-device", "203.0.113.9")));
        assertThat(p.getStatus()).isIn(PaymentState.HELD, PaymentState.PENDING_REVIEW);

        // Two ways in: MEDIUM/LOW risk is authorised then explicitly held (audited as
        // PAYMENT_HELD); HIGH risk is REVIEW before authorisation and goes straight to
        // PENDING_REVIEW, which is not itself a separately audited business event — only its
        // eventual decision is.
        List<String> afterCreate = auditedActions(p.getId());
        if (p.getStatus() == PaymentState.HELD) {
            assertThat(afterCreate).containsExactly("PAYMENT_CREATED", "PAYMENT_HELD");
        } else {
            assertThat(afterCreate).containsExactly("PAYMENT_CREATED");
        }

        AnalystReview review = reviews.findFirstByPaymentIdOrderByCreatedAtDesc(p.getId()).orElseThrow();
        decider.decide(review.getId());   // proposer only — no ML sidecar in tests, so it declines
        assertThat(auditedActions(p.getId())).as("declining to auto-clear is not itself an audited event")
                .doesNotContain("REVIEW_DECIDED");

        simulatedAnalyst.decide(review.getId());   // the demo stand-in finishes what the proposer left

        List<AuditLog> trail = trailFor(p.getId());
        AuditLog decision = trail.get(trail.size() - 1);
        assertThat(decision.getAction()).isEqualTo("REVIEW_DECIDED");
        assertThat(decision.getActorType()).isEqualTo("SYSTEM");
        assertThat(decision.getActorId()).isEqualTo("SIMULATED_ANALYST");
        assertThat(decision.getAfterState()).containsKeys("decision", "reviewedBy", "reason", "resultingStatus");
    }

    @Test
    void aHumanClearingAPayment_isAuditedAsAUserDecision() {
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 900_000);
        Payment p = tx.execute(s -> payments.createPayment(new PaymentService.CreatePaymentCommand(
                merchantId, order.getId(), "CARD", "tok_valid", "brand-new-device-2", "203.0.113.10")));
        assertThat(p.getStatus()).isIn(PaymentState.HELD, PaymentState.PENDING_REVIEW);

        payments.resolveReview(p.getId(), true, "HUMAN", "looks fine on review");

        AuditLog decision = trailFor(p.getId()).stream()
                .filter(a -> a.getAction().equals("REVIEW_DECIDED")).findFirst().orElseThrow();
        assertThat(decision.getActorType()).isEqualTo("USER");
        assertThat(decision.getActorId()).isEqualTo("HUMAN");
        assertThat(decision.getAfterState())
                .containsEntry("decision", "CLEAR")
                .containsEntry("reason", "looks fine on review")
                .containsEntry("resultingStatus", "CAPTURED");
    }

    private List<AuditLog> trailFor(UUID paymentId) {
        return audit.findByEntityTypeAndEntityIdOrderBySeqAsc("PAYMENT", paymentId.toString(), PageRequest.of(0, 50)).getContent();
    }

    private List<String> auditedActions(UUID paymentId) {
        return trailFor(paymentId).stream().map(AuditLog::getAction).toList();
    }
}
