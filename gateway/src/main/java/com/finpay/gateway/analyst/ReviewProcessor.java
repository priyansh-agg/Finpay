package com.finpay.gateway.analyst;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Proposes auto-clear for every freshly queued review, on a schedule (interview sprint Day 8).
 * Each item is evaluated in its own transaction by {@link ReviewDecider} so one failure can't
 * stall the rest, and each is proposed exactly once ({@link AnalystReviewRepository#findUnproposed}).
 * This never decides FOR a human — a review the proposer declines to auto-clear stays PENDING;
 * see {@link SimulatedAnalystWorker} for who drains that queue in a demo.
 */
@Component
@Profile("!phase1")
public class ReviewProcessor {

    private static final Logger log = LoggerFactory.getLogger(ReviewProcessor.class);

    private final AnalystReviewRepository reviews;
    private final ReviewDecider decider;

    public ReviewProcessor(AnalystReviewRepository reviews, ReviewDecider decider) {
        this.reviews = reviews;
        this.decider = decider;
    }

    @Scheduled(fixedDelayString = "${finpay.autoclear.poll-ms:4000}")
    public void drain() {
        List<AnalystReview> pending = reviews.findUnproposed(AnalystReview.QueueStatus.PENDING, PageRequest.of(0, 50));
        for (AnalystReview r : pending) {
            try {
                decider.decide(r.getId());
            } catch (Exception e) {
                log.warn("review {} failed to decide: {}", r.getId(), e.toString());
            }
        }
    }
}
