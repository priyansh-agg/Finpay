package com.finpay.gateway.refund;

import com.finpay.gateway.common.audit.AuditService;
import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.ledger.LedgerService;
import com.finpay.gateway.outbox.OutboxService;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.payment.PaymentRepository;
import com.finpay.gateway.payment.PaymentState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class RefundService {

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final LedgerService ledger;
    private final OutboxService outbox;
    private final AuditService audit;

    public RefundService(RefundRepository refunds, PaymentRepository payments, LedgerService ledger,
                         OutboxService outbox, AuditService audit) {
        this.refunds = refunds;
        this.payments = payments;
        this.ledger = ledger;
        this.outbox = outbox;
        this.audit = audit;
    }

    /**
     * Refund a captured payment, fully or partially. Concurrency-safe:
     * {@code findByIdForUpdate} serialises concurrent refunds on the same payment, so the
     * "sum of succeeded refunds + this one <= captured amount" check can never be raced.
     * Also idempotent on {@code idemKey} (unique column).
     */
    @Transactional
    public Refund refund(UUID merchantId, UUID paymentId, Long amountMinor, String reason, String idemKey) {
        if (idemKey != null) {
            var existing = refunds.findByIdemKey(idemKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        Payment payment = payments.findByIdForUpdate(paymentId)
                .orElseThrow(() -> ApiException.notFound("Payment"));
        if (!payment.getMerchantId().equals(merchantId)) {
            throw ApiException.notFound("Payment");
        }
        if (payment.getStatus() != PaymentState.CAPTURED && payment.getStatus() != PaymentState.REFUNDED) {
            throw ApiException.conflict("Only captured payments can be refunded (status=" + payment.getStatus() + ")");
        }

        long already = refunds.succeededTotal(paymentId);
        long remaining = payment.getAmountMinor() - already;
        long amount = amountMinor == null ? remaining : amountMinor;
        if (amount <= 0) {
            throw ApiException.validation("Refund amount must be positive");
        }
        if (amount > remaining) {
            throw ApiException.validation("Refund exceeds refundable balance (remaining=" + remaining + ")");
        }

        Refund refund = new Refund();
        refund.setPaymentId(paymentId);
        refund.setMerchantId(merchantId);
        refund.setAmountMinor(amount);
        refund.setCurrency(payment.getCurrency());
        refund.setReason(reason);
        refund.setIdemKey(idemKey);
        refunds.save(refund);

        ledger.postRefund(refund.getId(), paymentId, merchantId, amount, payment.getCurrency());
        refund.setStatus(Refund.Status.SUCCEEDED);

        if (already + amount == payment.getAmountMinor()) {
            payment.getStatus().assertCanTransitionTo(PaymentState.REFUNDED);
            payment.setStatus(PaymentState.REFUNDED);
        }

        outbox.publish("REFUND", refund.getId(), "refund.succeeded", Map.of(
                "refundId", refund.getId().toString(),
                "paymentId", paymentId.toString(),
                "amountMinor", amount,
                "currency", payment.getCurrency(),
                "fullyRefunded", already + amount == payment.getAmountMinor()));

        audit.record("MERCHANT", merchantId.toString(), "REFUND_SUCCEEDED", "REFUND", refund.getId().toString(),
                null, Map.of("paymentId", paymentId.toString(), "amountMinor", amount));

        return refund;
    }

    @Transactional(readOnly = true)
    public java.util.List<Refund> forPayment(UUID merchantId, UUID paymentId) {
        payments.findByIdAndMerchantId(paymentId, merchantId).orElseThrow(() -> ApiException.notFound("Payment"));
        return refunds.findByPaymentId(paymentId);
    }
}
