package com.finpay.gateway.risk;

import com.finpay.gateway.analyst.NoopReviewQueue;
import com.finpay.gateway.analyst.api.ReviewQueuePort;
import com.finpay.gateway.order.Order;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.payment.PaymentService;
import com.finpay.gateway.payment.PaymentState;
import com.finpay.gateway.risk.api.RiskGate;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code phase1} profile runs the gateway with the fraud engine switched off — Phase 1 in
 * isolation, per docs/PHASES.md: {@link AllowAllRiskGate} scores everything LOW, reviews are
 * dropped.
 *
 * <p>Every other suite now runs the real engine, so this is the one place the profile is
 * still exercised. It exists to stop the profile rotting unnoticed, and to state plainly what
 * it means: a payment the real engine would send to review is captured on the spot. That is
 * exactly why the rest of the suite must never run under it.
 */
@ActiveProfiles("phase1")   // merged with the base class's "test"
class Phase1ProfileSmokeTest extends AbstractIntegrationTest {

    @Autowired RiskGate riskGate;
    @Autowired ReviewQueuePort reviewQueue;
    @Autowired PaymentService payments;
    @Autowired TestFixtures fixtures;
    @Autowired TransactionTemplate tx;

    @Test
    void phase1_swapsInTheAllowAllGate_andCapturesWhatTheRealEngineWouldReview() {
        assertThat(riskGate).isInstanceOf(AllowAllRiskGate.class);
        assertThat(reviewQueue).isInstanceOf(NoopReviewQueue.class);

        // New device + new IP + ₹9,000: the real engine scores this HIGH and sends it to
        // review (see AutoClearFlowTest). Here it sails straight through.
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 900_000);
        Payment p = tx.execute(s -> payments.createPayment(new PaymentService.CreatePaymentCommand(
                merchantId, order.getId(), "CARD", "tok_valid", "brand-new-device", "203.0.113.9")));

        assertThat(p.getStatus()).isEqualTo(PaymentState.CAPTURED);
        assertThat(p.getRiskBucket()).isEqualTo("LOW");
    }
}
