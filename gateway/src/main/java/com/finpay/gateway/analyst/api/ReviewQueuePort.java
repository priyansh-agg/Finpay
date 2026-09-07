package com.finpay.gateway.analyst.api;

import java.util.Map;
import java.util.UUID;

/**
 * Called by the payment flow when a payment needs analyst review (decision REVIEW, or
 * AUTHORIZED->HELD for MEDIUM risk). Phase 3 implements the real review queue + auto-clear;
 * a no-op is used before then.
 */
public interface ReviewQueuePort {

    /**
     * @param reason        why it landed in review (e.g. "risk.decision=REVIEW", "held:MEDIUM")
     * @param riskScore     final risk score at decision time
     * @param featureSnapshot features used, for the audit trail
     */
    void enqueue(UUID paymentId, String reason, double riskScore, Map<String, Object> featureSnapshot);
}
