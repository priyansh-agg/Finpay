package com.finpay.gateway.groundtruth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.analyst.AutoClearDecision;
import com.finpay.gateway.analyst.AutoClearDecisionRepository;
import com.finpay.gateway.risk.FraudVectorStore;
import com.finpay.gateway.risk.RiskEvaluation;
import com.finpay.gateway.risk.RiskEvaluationRepository;
import com.finpay.gateway.risk.RiskFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Simulates the chargeback/dispute window: once a payment's {@code resolve_at} passes, the
 * hidden {@code actually_fraudulent} label is set (here: equal to what the simulator planted).
 * Confirmed-fraud payments are then embedded into the pgvector store so future risk
 * evaluations can retrieve them.
 *
 * <p>This label is used for EVALUATION ONLY — never as a training feature (data-leakage guard,
 * architecture-freeze Doubt 3).
 */
@Component
public class GroundTruthResolver {

    private static final Logger log = LoggerFactory.getLogger(GroundTruthResolver.class);

    private final FraudGroundTruthRepository repo;
    private final RiskEvaluationRepository evaluations;
    private final AutoClearDecisionRepository autoClearDecisions;
    private final FraudVectorStore vectors;
    private final com.finpay.gateway.common.metrics.FinPayMetrics metrics;
    private final ObjectMapper json;

    public GroundTruthResolver(FraudGroundTruthRepository repo, RiskEvaluationRepository evaluations,
                               AutoClearDecisionRepository autoClearDecisions,
                               FraudVectorStore vectors, com.finpay.gateway.common.metrics.FinPayMetrics metrics,
                               ObjectMapper json) {
        this.repo = repo;
        this.evaluations = evaluations;
        this.autoClearDecisions = autoClearDecisions;
        this.vectors = vectors;
        this.metrics = metrics;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${finpay.groundtruth.poll-ms:5000}")
    @Transactional
    public void resolveDue() {
        List<FraudGroundTruth> due = repo.findDueForResolution(java.time.Instant.now(), PageRequest.of(0, 100));
        for (FraudGroundTruth g : due) {
            boolean fraud = g.isFraudLabel();
            g.setActuallyFraudulent(fraud);
            g.setResolved(true);
            if (fraud) {
                embed(g);
            }
            // Reconcile the Phase-3 safety metric: mark whether any auto-clear for this
            // payment turned out to be a FALSE auto-clear (a critical bug, tracked first-class).
            for (AutoClearDecision d : autoClearDecisions.findByPaymentId(g.getPaymentId())) {
                if (d.isAutoCleared()) {
                    d.setLaterConfirmedFraud(fraud);
                    if (fraud) {
                        metrics.falseAutoClear();
                        log.error("FALSE AUTO-CLEAR: payment {} was auto-cleared but is confirmed fraudulent", g.getPaymentId());
                    }
                }
            }
        }
        if (!due.isEmpty()) {
            log.info("resolved {} ground-truth rows ({} confirmed fraud)",
                    due.size(), due.stream().filter(FraudGroundTruth::isFraudLabel).count());
        }
    }

    private void embed(FraudGroundTruth g) {
        RiskEvaluation eval = evaluations.findFirstByPaymentIdOrderByCreatedAtDesc(g.getPaymentId()).orElse(null);
        if (eval == null) return;
        try {
            RiskFeatures f = json.readValue(eval.getFeatures(), RiskFeatures.class);
            vectors.storeConfirmedFraud(g.getPaymentId(), vectors.encode(f),
                    g.getFraudType() == null ? "unknown" : g.getFraudType());
        } catch (Exception e) {
            log.warn("could not embed confirmed fraud {}: {}", g.getPaymentId(), e.toString());
        }
    }
}
