package com.finpay.gateway.analyst;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.groundtruth.FraudGroundTruthRepository;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.payment.PaymentRepository;
import com.finpay.gateway.payment.PaymentService;
import com.finpay.gateway.risk.RiskEvaluationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;

/**
 * Decides ONE review as the demo-only simulated analyst, in its own transaction (interview
 * sprint Day 8) — split out from {@link SimulatedAnalystWorker} for the same reason
 * {@link ReviewDecider} is split from {@link ReviewProcessor}: {@code @Transactional} is applied
 * by a Spring AOP proxy, which only intercepts calls that arrive from OUTSIDE the bean.
 * {@link SimulatedAnalystWorker#drain()} looping and calling {@code decide(...)} on itself
 * would bypass that proxy entirely — the transaction annotation would be silently ignored, and
 * this class's own mutations to the {@link AnalystReview} row (as opposed to
 * {@link PaymentService#resolveReview}, a genuinely separate bean called correctly) would never
 * flush: the payment would be voided or captured for real, but the review would stay PENDING
 * forever, looking un-decided while the money had already moved. Calling through a real
 * injected bean, as {@code SimulatedAnalystWorker} does here, keeps the proxy — and the
 * transaction — in the loop.
 *
 * <p>Its judgment IS the deterministic {@link ReferenceAnalyst} rule check, i.e. what the
 * auto-clear proposer already used as its hard safety gate — optionally made imperfect by
 * {@code accuracy} (a demo knob; 1.0 by default, meaning it always agrees with the rules),
 * flipped by a seed derived from the review's own id so the same review always mistakes the
 * same way.
 */
@Component
public class SimulatedAnalystDecider {

    private static final Logger log = LoggerFactory.getLogger(SimulatedAnalystDecider.class);

    static final String ACTOR = "SIMULATED_ANALYST";
    private static final String BOT_ID = "simulated-analyst-bot";

    private final AnalystReviewRepository reviews;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final RiskEvaluationRepository evaluations;
    private final FraudGroundTruthRepository groundTruth;
    private final ReferenceAnalyst referenceAnalyst;
    private final ObjectMapper json;
    private final double accuracy;

    public SimulatedAnalystDecider(AnalystReviewRepository reviews, PaymentRepository payments,
                                   PaymentService paymentService, RiskEvaluationRepository evaluations,
                                   FraudGroundTruthRepository groundTruth, ReferenceAnalyst referenceAnalyst,
                                   ObjectMapper json,
                                   @Value("${finpay.simulated-analyst.accuracy:1.0}") double accuracy) {
        this.reviews = reviews;
        this.payments = payments;
        this.paymentService = paymentService;
        this.evaluations = evaluations;
        this.groundTruth = groundTruth;
        this.referenceAnalyst = referenceAnalyst;
        this.json = json;
        this.accuracy = accuracy;
    }

    @Transactional
    public void decide(UUID reviewId) {
        AnalystReview review = reviews.findById(reviewId).orElse(null);
        if (review == null || review.getQueueStatus() == AnalystReview.QueueStatus.DECIDED
                || review.getAssignedTo() != null) {
            return; // already decided, or a human beat us to claiming it
        }

        Payment payment = payments.findById(review.getPaymentId()).orElse(null);
        if (payment == null) { review.setQueueStatus(AnalystReview.QueueStatus.DECIDED); return; }

        AnalystReviewInputs in = AnalystReviewInputs.resolve(payment, evaluations, groundTruth, json);
        ReferenceAnalyst.Verdict verdict = referenceAnalyst.decide(in.riskScore, in.featuresOrEmpty(), in.priorFraud);
        boolean clear = verdict.decision() == ReferenceAnalyst.Decision.CLEAR;
        if (accuracy < 1.0 && seededMistake(reviewId)) {
            clear = !clear;
        }

        paymentService.resolveReview(payment.getId(), clear, ACTOR, verdict.reason());

        review.setQueueStatus(AnalystReview.QueueStatus.DECIDED);
        review.setReviewerType(ACTOR);
        review.setDecision(clear ? "CLEAR" : "REJECT");
        review.setReason(verdict.reason());
        review.setDecidedAt(Instant.now());
        review.setAssignedTo(BOT_ID);
        if (review.getClaimedAt() == null) {
            review.setClaimedAt(Instant.now());
        }

        log.info("simulated analyst review {} payment {} decision={} accuracy={}",
                reviewId, payment.getId(), clear ? "CLEAR" : "REJECT", accuracy);
    }

    /** Deterministic per review: the same review id always mistakes (or doesn't) the same way. */
    private boolean seededMistake(UUID reviewId) {
        return new Random(reviewId.getMostSignificantBits()).nextDouble() >= accuracy;
    }
}
