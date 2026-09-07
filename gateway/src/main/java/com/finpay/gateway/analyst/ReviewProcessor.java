package com.finpay.gateway.analyst;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drains the analyst review queue on a schedule. Each item is decided in its own
 * transaction by {@link ReviewDecider} so one failure can't stall the queue.
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
        List<AnalystReview> pending = reviews.findQueue(AnalystReview.QueueStatus.PENDING, PageRequest.of(0, 50));
        for (AnalystReview r : pending) {
            try {
                decider.decide(r.getId());
            } catch (Exception e) {
                log.warn("review {} failed to decide: {}", r.getId(), e.toString());
            }
        }
    }
}
