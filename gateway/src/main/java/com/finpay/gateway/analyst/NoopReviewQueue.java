package com.finpay.gateway.analyst;

import com.finpay.gateway.analyst.api.ReviewQueuePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Used under the {@code phase1} profile, before the real review queue exists. */
@Component
@Profile("phase1")
public class NoopReviewQueue implements ReviewQueuePort {
    @Override
    public void enqueue(UUID paymentId, String reason, double riskScore, Map<String, Object> featureSnapshot, UUID runId) {
        // no-op
    }
}
