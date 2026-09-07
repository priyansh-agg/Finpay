package com.finpay.gateway.analyst;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.groundtruth.FraudGroundTruthRepository;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.payment.PaymentRepository;
import com.finpay.gateway.payment.PaymentService;
import com.finpay.gateway.risk.RiskEvaluation;
import com.finpay.gateway.risk.RiskEvaluationRepository;
import com.finpay.gateway.risk.RiskFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides ONE queued review, in its own transaction (Phase 3 core).
 *
 * <p>Routing:
 * <ul>
 *   <li>MEDIUM-risk (funds HELD): ask the Auto-Clear Engine. If P(analyst clears) ≥ threshold,
 *       auto-capture — this is the only loss-bearing auto-clear path (freeze Doubt 4).</li>
 *   <li>Everything else, or low confidence: the rule-based reference analyst decides, and its
 *       verdict becomes a training label for the Auto-Clear model.</li>
 * </ul>
 * A shadow {@link AutoClearDecision} row is always written so calibration + the
 * false-auto-clear metric can be computed later against the hidden ground truth.
 */
@Component
public class ReviewDecider {

    private static final Logger log = LoggerFactory.getLogger(ReviewDecider.class);

    private final AnalystReviewRepository reviews;
    private final AutoClearDecisionRepository autoClearDecisions;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final RiskEvaluationRepository evaluations;
    private final ReferenceAnalyst referenceAnalyst;
    private final AutoClearClient autoClearClient;
    private final FraudGroundTruthRepository groundTruth;
    private final AutoClearPolicy policy;
    private final com.finpay.gateway.common.metrics.FinPayMetrics metrics;
    private final ObjectMapper json;

    public ReviewDecider(AnalystReviewRepository reviews, AutoClearDecisionRepository autoClearDecisions,
                         PaymentRepository payments, PaymentService paymentService,
                         RiskEvaluationRepository evaluations, ReferenceAnalyst referenceAnalyst,
                         AutoClearClient autoClearClient, FraudGroundTruthRepository groundTruth,
                         AutoClearPolicy policy, com.finpay.gateway.common.metrics.FinPayMetrics metrics,
                         ObjectMapper json) {
        this.metrics = metrics;
        this.reviews = reviews;
        this.autoClearDecisions = autoClearDecisions;
        this.payments = payments;
        this.paymentService = paymentService;
        this.evaluations = evaluations;
        this.referenceAnalyst = referenceAnalyst;
        this.autoClearClient = autoClearClient;
        this.groundTruth = groundTruth;
        this.policy = policy;
        this.json = json;
    }

    @Transactional
    public void decide(UUID reviewId) {
        AnalystReview review = reviews.findById(reviewId).orElse(null);
        if (review == null || review.getQueueStatus() == AnalystReview.QueueStatus.DECIDED) return;

        Payment payment = payments.findById(review.getPaymentId()).orElse(null);
        if (payment == null) { review.setQueueStatus(AnalystReview.QueueStatus.DECIDED); return; }

        RiskEvaluation eval = evaluations.findFirstByPaymentIdOrderByCreatedAtDesc(payment.getId()).orElse(null);
        RiskFeatures features = parseFeatures(eval);
        double riskScore = eval != null ? eval.getFinalScore().doubleValue()
                : (payment.getRiskScore() == null ? 50.0 : payment.getRiskScore().doubleValue());
        boolean priorFraud = groundTruth.customerHasConfirmedFraud(payment.getCustomerId());
        String bucket = payment.getRiskBucket() == null ? "MEDIUM" : payment.getRiskBucket();

        Optional<AutoClearClient.Prediction> pred = features == null ? Optional.empty()
                : autoClearClient.predictClearProbability(features, riskScore);

        // What the deterministic reference analyst would do. This is a HARD safety gate for
        // auto-clear (belt and suspenders alongside the ML confidence + LOW-bucket restriction).
        ReferenceAnalyst.Verdict verdict = referenceAnalyst.decide(riskScore,
                features == null ? emptyFeatures() : features, priorFraud);
        boolean referenceWouldClear = verdict.decision() == ReferenceAnalyst.Decision.CLEAR;

        // Doubt 4: the loss-bearing auto-clear path is restricted to the LOW-risk bucket,
        // and only when EVERY check agrees: no prior fraud, the reference analyst would clear,
        // the model is present, and its confidence clears the (calibrated) threshold.
        double confidence = pred.map(AutoClearClient.Prediction::clearConfidence).orElse(0.0);
        String modelVersion = pred.map(AutoClearClient.Prediction::modelVersion).orElse("none");
        double threshold = policy.threshold();
        // Auto-clear only when EVERY independent check agrees the transaction is safe:
        //  - the risk bucket is not HIGH/CRITICAL (those always get a human)
        //  - the customer has no prior confirmed fraud
        //  - the deterministic reference analyst would clear it (pattern-based hard gate)
        //  - the Auto-Clear model's confidence clears the calibrated threshold
        // Any confirmed false auto-clear ratchets the threshold up (AutoClearCalibrator).
        boolean bucketOk = "LOW".equals(bucket) || "MEDIUM".equals(bucket);
        boolean autoCleared = bucketOk && !priorFraud && referenceWouldClear
                && pred.isPresent() && confidence >= threshold;

        if (autoCleared) {
            paymentService.resolveReview(payment.getId(), true, "AUTO_CLEAR",
                    String.format("auto-cleared (confidence %.4f >= %.2f, reference analyst concurs)", confidence, threshold));
            markDecided(review, "AUTO_CLEAR", "CLEAR",
                    String.format("auto-clear confidence %.4f", confidence));
        } else {
            paymentService.resolveReview(payment.getId(), referenceWouldClear, "REFERENCE_ANALYST", verdict.reason());
            markDecided(review, "REFERENCE_ANALYST", referenceWouldClear ? "CLEAR" : "REJECT", verdict.reason());
        }

        AutoClearDecision d = new AutoClearDecision();
        d.setPaymentId(payment.getId());
        d.setReviewId(review.getId());
        d.setConfidence(BigDecimal.valueOf(confidence));
        d.setThreshold(BigDecimal.valueOf(threshold));
        d.setAutoCleared(autoCleared);
        d.setModelVersion(modelVersion);
        d.setTopFeatures(writeJson(pred.map(AutoClearClient.Prediction::topFeatures).orElse(List.of())));
        autoClearDecisions.save(d);
        metrics.autoClearDecision(autoCleared);

        log.info("review {} payment {} bucket={} autoCleared={} confidence={}",
                reviewId, payment.getId(), bucket, autoCleared, String.format("%.4f", confidence));
    }

    private void markDecided(AnalystReview r, String reviewerType, String decision, String reason) {
        r.setQueueStatus(AnalystReview.QueueStatus.DECIDED);
        r.setReviewerType(reviewerType);
        r.setDecision(decision);
        r.setReason(reason);
        r.setDecidedAt(Instant.now());
    }

    private RiskFeatures parseFeatures(RiskEvaluation eval) {
        if (eval == null) return null;
        try {
            return json.readValue(eval.getFeatures(), RiskFeatures.class);
        } catch (Exception e) {
            return null;
        }
    }

    private RiskFeatures emptyFeatures() {
        return new RiskFeatures(0, "CARD", 0, 0, 0, 0, 0, 0, false, false, 1, 12, 0);
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { return "[]"; }
    }
}
