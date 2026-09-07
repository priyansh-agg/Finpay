package com.finpay.gateway.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.risk.api.RiskGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * The real risk gate (Phase 2). Combines three independent signals — a deterministic rule
 * layer, the ML sidecar's probability, and pgvector similarity to confirmed-fraud cases —
 * into one {@code final_score} (0–100), a bucket, and an ALLOW / REVIEW / BLOCK decision.
 * Runs synchronously before the processor is called (architecture-freeze Doubt 6).
 */
@Component
@Profile("!phase1")
public class RiskEngine implements RiskGate {

    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);

    private final FeatureExtractor extractor;
    private final RuleEngine rules;
    private final MlClient ml;
    private final FraudVectorStore vectors;
    private final FeatureStore featureStore;
    private final RiskEvaluationRepository evaluations;
    private final com.finpay.gateway.common.metrics.FinPayMetrics metrics;
    private final ObjectMapper json;

    private final double wRule, wMl, wVector;
    private final int lowMax, mediumMax, highMax, autoCaptureMax;

    public RiskEngine(FeatureExtractor extractor, RuleEngine rules, MlClient ml, FraudVectorStore vectors,
                      FeatureStore featureStore, RiskEvaluationRepository evaluations,
                      com.finpay.gateway.common.metrics.FinPayMetrics metrics, ObjectMapper json,
                      @Value("${finpay.risk.rule-weight:0.5}") double wRule,
                      @Value("${finpay.risk.ml-weight:0.4}") double wMl,
                      @Value("${finpay.risk.vector-weight:0.1}") double wVector,
                      @Value("${finpay.risk.low-max:20}") int lowMax,
                      @Value("${finpay.risk.medium-max:50}") int mediumMax,
                      @Value("${finpay.risk.high-max:80}") int highMax,
                      @Value("${finpay.risk.auto-capture-max:15}") int autoCaptureMax) {
        this.extractor = extractor;
        this.rules = rules;
        this.ml = ml;
        this.vectors = vectors;
        this.featureStore = featureStore;
        this.evaluations = evaluations;
        this.metrics = metrics;
        this.json = json;
        this.wRule = wRule;
        this.wMl = wMl;
        this.wVector = wVector;
        this.lowMax = lowMax;
        this.mediumMax = mediumMax;
        this.highMax = highMax;
        this.autoCaptureMax = autoCaptureMax;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RiskResult evaluate(RiskContext ctx) {
        long t0 = System.nanoTime();

        RiskFeatures features = extractor.extract(ctx);
        RuleEngine.RuleResult rule = rules.evaluate(features);

        float[] vector = vectors.encode(features);
        double similarity = vectors.avgSimilarityToKnownFraud(vector);
        features = features.withVectorSimilarity(similarity);

        Optional<MlClient.MlScore> mlScore = ml.predict(features);

        double ruleComponent = wRule * rule.score();
        double vectorComponent = wVector * (similarity * 100.0);
        double activeWeight = wRule + wVector;
        double mlComponent = 0;
        if (mlScore.isPresent()) {
            mlComponent = wMl * (mlScore.get().probability() * 100.0);
            activeWeight += wMl;
        }
        double finalScore = activeWeight == 0 ? 0 : (ruleComponent + mlComponent + vectorComponent) / activeWeight;
        finalScore = Math.max(0, Math.min(100, finalScore));

        Bucket bucket = bucketFor(finalScore);
        Decision decision = decisionFor(bucket);
        boolean autoCapture = bucket == Bucket.LOW && finalScore <= autoCaptureMax;

        // record device<->account association AFTER scoring (so this payment isn't counted against itself)
        featureStore.registerDeviceAccount(ctx.deviceFingerprint(), ctx.customerId());
        featureStore.recordPaymentAttempt(ctx.customerId(), ctx.paymentId());

        long elapsedNanos = System.nanoTime() - t0;
        int latencyMs = (int) (elapsedNanos / 1_000_000);
        metrics.riskLatency(java.time.Duration.ofNanos(elapsedNanos));
        persist(ctx, features, rule, mlScore, similarity, finalScore, bucket, decision, latencyMs);

        log.info("risk[{}] score={} bucket={} decision={} rules={} ml={} sim={} {}ms",
                ctx.paymentId(), round(finalScore), bucket, decision,
                rule.flags().stream().map(RuleEngine.RuleFlag::code).toList(),
                mlScore.map(s -> round(s.probability() * 100)).orElse(null), round(similarity), latencyMs);

        return new RiskResult(decision, bucket, round(finalScore), autoCapture);
    }

    private Bucket bucketFor(double score) {
        if (score <= lowMax) return Bucket.LOW;
        if (score <= mediumMax) return Bucket.MEDIUM;
        if (score <= highMax) return Bucket.HIGH;
        return Bucket.CRITICAL;
    }

    private Decision decisionFor(Bucket b) {
        return switch (b) {
            case LOW, MEDIUM -> Decision.ALLOW;   // MEDIUM is authorised then HELD, not captured
            case HIGH -> Decision.REVIEW;
            case CRITICAL -> Decision.BLOCK;
        };
    }

    private void persist(RiskContext ctx, RiskFeatures features, RuleEngine.RuleResult rule,
                         Optional<MlClient.MlScore> mlScore, double similarity, double finalScore,
                         Bucket bucket, Decision decision, int latencyMs) {
        RiskEvaluation e = new RiskEvaluation();
        e.setPaymentId(ctx.paymentId());
        e.setRuleFlags(writeJson(rule.flags()));
        e.setRuleScore(bd(rule.score()));
        mlScore.ifPresent(s -> {
            e.setMlScore(bd(s.probability() * 100.0));
            e.setMlModelVersion(s.modelVersion());
        });
        e.setVectorSimilarity(BigDecimal.valueOf(similarity).setScale(4, RoundingMode.HALF_UP));
        e.setFinalScore(bd(finalScore));
        e.setBucket(bucket.name());
        e.setDecision(decision.name());
        e.setLatencyMs(latencyMs);
        e.setFeatures(writeJson(features));   // the RiskFeatures record itself, for later re-encoding
        evaluations.save(e);
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception ex) { return "null"; }
    }

    private static BigDecimal bd(double d) {
        return BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP);
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
