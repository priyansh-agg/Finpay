package com.finpay.gateway.payment;

import com.finpay.gateway.analyst.AnalystReviewRepository;
import com.finpay.gateway.analyst.ReviewDecider;
import com.finpay.gateway.auth.FinPayPrincipal;
import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.groundtruth.GroundTruthResolver;
import com.finpay.gateway.order.Order;
import com.finpay.gateway.refund.RefundController;
import com.finpay.gateway.refund.RefundService;
import com.finpay.gateway.settlement.SettlementService;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import com.finpay.gateway.webhook.WebhookDispatcher;
import com.finpay.gateway.webhook.WebhookEndpoint;
import com.finpay.gateway.webhook.WebhookEndpointRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Payment Detail view's facets (interview sprint Day 12 — the audit's §C.2: "4 of ~10
 * facets are retrievable and 0 are assembled"). Every facet here is a read; the point is
 * answering "what happened to this payment," not deciding anything.
 */
class PaymentDetailTest extends AbstractIntegrationTest {

    @Autowired TestFixtures fixtures;
    @Autowired PaymentService paymentService;
    @Autowired PaymentController controller;
    @Autowired RefundController refundController;
    @Autowired RefundService refundService;
    @Autowired SettlementService settlements;
    @Autowired AnalystReviewRepository reviews;
    @Autowired ReviewDecider decider;
    @Autowired GroundTruthResolver groundTruthResolver;
    @Autowired WebhookEndpointRepository webhookEndpoints;
    @Autowired WebhookDispatcher dispatcher;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void merchantSeesItsOwnPayment_anotherMerchantGets404_analystSeesAny() {
        UUID m1 = fixtures.newMerchant();
        UUID m2 = fixtures.newMerchant();
        Payment p = fixtures.newCapturedPayment(m1, 20_000);

        loginAsMerchant(m1);
        assertThat(controller.get(p.getId()).id()).isEqualTo(p.getId());

        loginAsMerchant(m2);
        assertThatThrownBy(() -> controller.get(p.getId())).isInstanceOf(ApiException.class);

        loginAsAdmin("RISK_ANALYST");
        assertThat(controller.get(p.getId()).id()).as("an analyst can look up any merchant's payment")
                .isEqualTo(p.getId());

        loginAsAdmin("SUPER_ADMIN");
        assertThat(controller.get(p.getId()).id()).isEqualTo(p.getId());
    }

    @Test
    void everyFacetEndpoint_alsoRespectsMerchantScoping() {
        UUID m1 = fixtures.newMerchant();
        UUID m2 = fixtures.newMerchant();
        Payment p = fixtures.newCapturedPayment(m1, 20_000);

        loginAsMerchant(m2);
        assertThatThrownBy(() -> controller.timeline(p.getId())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.ledger(p.getId())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.webhooks(p.getId())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.audit(p.getId())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.autoClear(p.getId())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.customer(p.getId())).isInstanceOf(ApiException.class);
    }

    @Test
    void timeline_matchesThePaymentsOwnAttemptHistory() {
        UUID merchantId = fixtures.newMerchant();
        Payment p = fixtures.newCapturedPayment(merchantId, 15_000);
        loginAsMerchant(merchantId);

        List<PaymentDetailService.TimelineEntry> timeline = controller.timeline(p.getId());

        assertThat(timeline).isNotEmpty();
        assertThat(timeline.get(timeline.size() - 1).toStatus()).isEqualTo("CAPTURED");
    }

    @Test
    void ledgerImpact_assemblesCaptureAndRefund_withRealEntries() {
        UUID merchantId = fixtures.newMerchant();
        Payment p = fixtures.newCapturedPayment(merchantId, 40_000);
        refundService.refund(merchantId, p.getId(), 15_000L, "partial", "rf-" + UUID.randomUUID());
        loginAsMerchant(merchantId);

        List<PaymentDetailService.LedgerTxView> ledger = controller.ledger(p.getId());

        assertThat(ledger).extracting(PaymentDetailService.LedgerTxView::txType)
                .containsExactly("CAPTURE", "REFUND");
        var capture = ledger.get(0);
        assertThat(capture.entries()).hasSize(2);
        assertThat(capture.entries()).extracting(PaymentDetailService.LedgerEntryView::accountType)
                .containsExactlyInAnyOrder("CUSTOMER_CLEARING", "MERCHANT_PAYABLE");
        var refund = ledger.get(1);
        assertThat(refund.entries()).extracting(e -> e.amountMinor()).containsOnly(15_000L);
    }

    @Test
    void ledgerImpact_includesTheWriteOff_whenConfirmedFraudCaughtBeforeSettlement() {
        UUID merchantId = fixtures.newMerchant();
        Payment p = fixtures.newCapturedPayment(merchantId, 60_000);
        jdbc.update("UPDATE fraud_ground_truth SET intended_class = 'FRAUD', resolve_at = now() WHERE payment_id = ?",
                p.getId());
        groundTruthResolver.resolveDue();
        settlements.runBatch();
        loginAsMerchant(merchantId);

        List<PaymentDetailService.LedgerTxView> ledger = controller.ledger(p.getId());

        assertThat(ledger).extracting(PaymentDetailService.LedgerTxView::txType)
                .containsExactly("CAPTURE", "FRAUD_WRITEOFF");
        assertThat(ledger.get(1).entries()).extracting(e -> e.amountMinor()).containsOnly(60_000L);
    }

    @Test
    void webhookActivity_assemblesOutboxEventsAndTheirDeliveries() {
        UUID merchantId = fixtures.newMerchant();
        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setMerchantId(merchantId);
        ep.setUrl("http://localhost:1/unused");
        ep.setSecret("whsec_test");
        ep.setEvents(new String[0]);
        webhookEndpoints.save(ep);

        Payment p = fixtures.newCapturedPayment(merchantId, 10_000);
        dispatcher.fanOut();
        loginAsMerchant(merchantId);

        List<PaymentDetailService.OutboxEventView> events = controller.webhooks(p.getId());

        assertThat(events).extracting(PaymentDetailService.OutboxEventView::eventType).contains("payment.captured");
        var captured = events.stream().filter(e -> e.eventType().equals("payment.captured")).findFirst().orElseThrow();
        assertThat(captured.deliveries()).as("fanned out to the subscribed endpoint").hasSize(1);
    }

    @Test
    void auditTrail_matchesAuditLogs() {
        UUID merchantId = fixtures.newMerchant();
        Payment p = fixtures.newCapturedPayment(merchantId, 10_000);
        loginAsMerchant(merchantId);

        List<PaymentDetailService.AuditEntryView> trail = controller.audit(p.getId());

        assertThat(trail).extracting(PaymentDetailService.AuditEntryView::action)
                .containsExactly("PAYMENT_CREATED", "PAYMENT_CAPTURED");
    }

    @Test
    void autoClearStatus_reflectsTheProposersEvaluation() {
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 900_000);
        Payment p = tx.execute(s -> paymentService.createPayment(new PaymentService.CreatePaymentCommand(
                merchantId, order.getId(), "CARD", "tok_valid", "brand-new-device", "203.0.113.9")));
        var review = reviews.findFirstByPaymentIdOrderByCreatedAtDesc(p.getId()).orElseThrow();
        decider.decide(review.getId());
        loginAsMerchant(merchantId);

        List<PaymentDetailService.AutoClearDecisionView> decisions = controller.autoClear(p.getId());

        assertThat(decisions).as("the proposer's shadow row, even though it declined").hasSize(1);
        assertThat(decisions.get(0).autoCleared()).isFalse();
    }

    @Test
    void customer_returnsProfileAndCaptureStats() {
        UUID merchantId = fixtures.newMerchant();
        Payment p = fixtures.newCapturedPayment(merchantId, 25_000);
        loginAsMerchant(merchantId);

        PaymentDetailService.CustomerView customer = controller.customer(p.getId());

        assertThat(customer.externalRef()).isNotBlank();
        assertThat(customer.capturedCount()).isEqualTo(1);
        assertThat(customer.capturedSumMinor()).isEqualTo(25_000);
    }

    @Test
    void refunds_areVisibleToAnAnalystToo_notJustTheOwningMerchant() {
        UUID merchantId = fixtures.newMerchant();
        Payment p = fixtures.newCapturedPayment(merchantId, 30_000);
        refundService.refund(merchantId, p.getId(), 10_000L, "partial", "rf-" + UUID.randomUUID());

        loginAsAdmin("SUPER_ADMIN");
        List<RefundController.RefundResponse> refunds = refundController.list(p.getId());

        assertThat(refunds).singleElement().satisfies(r -> assertThat(r.amountMinor()).isEqualTo(10_000));
    }

    private void loginAsMerchant(UUID merchantId) {
        var principal = new FinPayPrincipal.MerchantCaller(merchantId, UUID.randomUUID(), "MERCHANT_ADMIN");
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_MERCHANT_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void loginAsAdmin(String role) {
        var principal = new FinPayPrincipal.UserCaller(UUID.randomUUID(), null, "admin@test.local", role);
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
