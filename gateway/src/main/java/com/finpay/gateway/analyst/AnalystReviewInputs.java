package com.finpay.gateway.analyst;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.groundtruth.FraudGroundTruthRepository;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.risk.RiskEvaluation;
import com.finpay.gateway.risk.RiskEvaluationRepository;
import com.finpay.gateway.risk.RiskFeatures;

/**
 * What any reviewer of a payment — the auto-clear proposer, the simulated analyst worker —
 * needs to judge it: the same feature snapshot, risk score, prior-fraud flag and bucket, read
 * once so every reviewer of the same payment sees an identical picture.
 */
final class AnalystReviewInputs {

    final RiskFeatures features;
    final double riskScore;
    final boolean priorFraud;
    final String bucket;

    private AnalystReviewInputs(RiskFeatures features, double riskScore, boolean priorFraud, String bucket) {
        this.features = features;
        this.riskScore = riskScore;
        this.priorFraud = priorFraud;
        this.bucket = bucket;
    }

    static AnalystReviewInputs resolve(Payment payment, RiskEvaluationRepository evaluations,
                                        FraudGroundTruthRepository groundTruth, ObjectMapper json) {
        RiskEvaluation eval = evaluations.findFirstByPaymentIdOrderByCreatedAtDesc(payment.getId()).orElse(null);
        RiskFeatures features = parseFeatures(eval, json);
        double riskScore = eval != null ? eval.getFinalScore().doubleValue()
                : (payment.getRiskScore() == null ? 50.0 : payment.getRiskScore().doubleValue());
        boolean priorFraud = groundTruth.customerHasConfirmedFraud(payment.getCustomerId());
        String bucket = payment.getRiskBucket() == null ? "MEDIUM" : payment.getRiskBucket();
        return new AnalystReviewInputs(features, riskScore, priorFraud, bucket);
    }

    RiskFeatures featuresOrEmpty() {
        return features != null ? features : new RiskFeatures(0, "CARD", 0, 0, 0, 0, 0, 0, false, false, 1, 12, 0);
    }

    private static RiskFeatures parseFeatures(RiskEvaluation eval, ObjectMapper json) {
        if (eval == null) return null;
        try {
            return json.readValue(eval.getFeatures(), RiskFeatures.class);
        } catch (Exception e) {
            return null;
        }
    }
}
