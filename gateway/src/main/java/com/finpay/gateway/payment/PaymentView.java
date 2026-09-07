package com.finpay.gateway.payment;

import java.time.Instant;
import java.util.UUID;

/** Merchant-facing payment representation. Never includes hidden ground-truth fields. */
public record PaymentView(
        UUID id,
        UUID orderId,
        long amountMinor,
        String currency,
        String method,
        String status,
        String riskDecision,
        String riskBucket,
        Double riskScore,
        String processorRef,
        String failedReason,
        Instant authorizedAt,
        Instant capturedAt,
        Instant createdAt) {

    public static PaymentView of(Payment p) {
        return new PaymentView(
                p.getId(), p.getOrderId(), p.getAmountMinor(), p.getCurrency(), p.getMethod(),
                p.getStatus().name(), p.getRiskDecision(), p.getRiskBucket(),
                p.getRiskScore() == null ? null : p.getRiskScore().doubleValue(),
                p.getProcessorRef(), p.getFailedReason(), p.getAuthorizedAt(), p.getCapturedAt(), p.getCreatedAt());
    }
}
