package com.finpay.gateway.analyst;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.analyst.api.ReviewQueuePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Real review queue (Phase 3). A flagged/held payment becomes a PENDING {@link AnalystReview} row. */
@Component
@Profile("!phase1")
public class ReviewQueue implements ReviewQueuePort {

    private final AnalystReviewRepository reviews;
    private final ObjectMapper json;

    public ReviewQueue(AnalystReviewRepository reviews, ObjectMapper json) {
        this.reviews = reviews;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID paymentId, String reason, double riskScore, Map<String, Object> featureSnapshot, UUID runId) {
        AnalystReview r = new AnalystReview();
        r.setPaymentId(paymentId);
        r.setRunId(runId);
        r.setReason(reason);
        r.setRiskScoreAtReview(BigDecimal.valueOf(riskScore));
        r.setFeaturesAtReview(writeJson(featureSnapshot));
        reviews.save(r);
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }
}
