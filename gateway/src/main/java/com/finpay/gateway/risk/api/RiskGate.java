package com.finpay.gateway.risk.api;

import java.util.UUID;

/**
 * SPI the payment flow calls to gate a payment BEFORE the processor is touched (Doubt 6).
 * Phase 1 ships {@code AllowAllRiskGate}; Phase 2 replaces it with the real rule + ML engine.
 */
public interface RiskGate {

    enum Decision { ALLOW, REVIEW, BLOCK }

    enum Bucket { LOW, MEDIUM, HIGH, CRITICAL }

    record RiskContext(
            UUID paymentId, UUID merchantId, UUID customerId,
            long amountMinor, String currency, String method,
            String deviceFingerprint, String ipAddress, String token) {}

    record RiskResult(Decision decision, Bucket bucket, double finalScore, boolean autoCaptureEligible) {
        public static RiskResult allowLow() {
            return new RiskResult(Decision.ALLOW, Bucket.LOW, 0.0, true);
        }
    }

    RiskResult evaluate(RiskContext ctx);
}
