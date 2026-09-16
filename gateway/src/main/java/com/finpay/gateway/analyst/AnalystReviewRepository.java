package com.finpay.gateway.analyst;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalystReviewRepository extends JpaRepository<AnalystReview, UUID> {

    @Query("select r from AnalystReview r where r.queueStatus = :status order by r.createdAt asc")
    List<AnalystReview> findQueue(@Param("status") AnalystReview.QueueStatus status, Pageable pageable);

    /**
     * PENDING reviews the auto-clear proposer hasn't evaluated yet ({@link ReviewProcessor}'s
     * queue). Each review is proposed exactly once: it either auto-clears (terminal), or stays
     * PENDING with a shadow {@code AutoClearDecision} row recorded — this query excludes it
     * from being re-proposed every poll.
     */
    @Query("select r from AnalystReview r where r.queueStatus = :status "
            + "and not exists (select 1 from AutoClearDecision d where d.reviewId = r.id) "
            + "order by r.createdAt asc")
    List<AnalystReview> findUnproposed(@Param("status") AnalystReview.QueueStatus status, Pageable pageable);

    /**
     * PENDING, already-proposed-and-declined, unclaimed reviews old enough for the simulated
     * analyst worker to pick up — it never touches one a human has claimed.
     */
    @Query("select r from AnalystReview r where r.queueStatus = :status and r.assignedTo is null "
            + "and r.createdAt <= :readyBefore "
            + "and exists (select 1 from AutoClearDecision d where d.reviewId = r.id) "
            + "order by r.createdAt asc")
    List<AnalystReview> findReadyForSimulatedAnalyst(@Param("status") AnalystReview.QueueStatus status,
                                                      @Param("readyBefore") Instant readyBefore, Pageable pageable);

    Optional<AnalystReview> findFirstByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    List<AnalystReview> findByRunIdAndQueueStatus(UUID runId, AnalystReview.QueueStatus queueStatus);

    long countByQueueStatus(AnalystReview.QueueStatus status);

    long countByReviewerType(String reviewerType);

    long countByReviewerTypeAndDecision(String reviewerType, String decision);
}
