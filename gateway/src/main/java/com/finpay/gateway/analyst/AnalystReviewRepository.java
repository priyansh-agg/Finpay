package com.finpay.gateway.analyst;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalystReviewRepository extends JpaRepository<AnalystReview, UUID> {

    @Query("select r from AnalystReview r where r.queueStatus = :status order by r.createdAt asc")
    List<AnalystReview> findQueue(@Param("status") AnalystReview.QueueStatus status, Pageable pageable);

    Optional<AnalystReview> findFirstByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    List<AnalystReview> findByRunIdAndQueueStatus(UUID runId, AnalystReview.QueueStatus queueStatus);

    long countByQueueStatus(AnalystReview.QueueStatus status);

    long countByReviewerType(String reviewerType);

    long countByReviewerTypeAndDecision(String reviewerType, String decision);
}
