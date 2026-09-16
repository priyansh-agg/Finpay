package com.finpay.gateway.analyst;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Demo stand-in for a human reviewer (interview sprint Day 8). On a schedule, drains whatever
 * the auto-clear proposer ({@link ReviewDecider}) declined and left PENDING, once it has sat
 * unclaimed for {@code latency-seconds} — a knob standing in for human response time — and
 * leaves alone anything a real analyst has claimed
 * ({@link AnalystReviewRepository#findReadyForSimulatedAnalyst}).
 *
 * <p>Each item is decided by the separately-injected {@link SimulatedAnalystDecider} bean, in
 * its own transaction, exactly the way {@link ReviewProcessor} delegates to {@link ReviewDecider}
 * — see that class's doc for why the decision logic can't just live in this loop.
 *
 * <p>Its decisions are tagged {@code reviewerType = SIMULATED_ANALYST}, never {@code HUMAN} —
 * {@link AutoClearMetricsService} excludes it from the human-reviewed baseline that "manual
 * review volume removed" is measured against, on purpose: it exists so a demo queue doesn't
 * pile up forever with nobody at the keyboard, not to manufacture a human review count.
 */
@Component
public class SimulatedAnalystWorker {

    private static final Logger log = LoggerFactory.getLogger(SimulatedAnalystWorker.class);

    private final AnalystReviewRepository reviews;
    private final SimulatedAnalystDecider decider;
    private final long latencySeconds;

    public SimulatedAnalystWorker(AnalystReviewRepository reviews, SimulatedAnalystDecider decider,
                                  @Value("${finpay.simulated-analyst.latency-seconds:20}") long latencySeconds) {
        this.reviews = reviews;
        this.decider = decider;
        this.latencySeconds = latencySeconds;
    }

    @Scheduled(fixedDelayString = "${finpay.simulated-analyst.poll-ms:6000}")
    public void drain() {
        Instant readyBefore = Instant.now().minusSeconds(latencySeconds);
        List<AnalystReview> ready = reviews.findReadyForSimulatedAnalyst(
                AnalystReview.QueueStatus.PENDING, readyBefore, PageRequest.of(0, 50));
        for (AnalystReview r : ready) {
            try {
                decider.decide(r.getId());
            } catch (Exception e) {
                log.warn("simulated analyst could not decide review {}: {}", r.getId(), e.toString());
            }
        }
    }
}
