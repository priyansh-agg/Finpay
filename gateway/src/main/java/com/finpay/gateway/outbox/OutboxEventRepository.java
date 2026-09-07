package com.finpay.gateway.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Claim a batch of due PENDING events, skipping rows already locked by another worker instance. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")) // SKIP LOCKED
    @Query("""
            select e from OutboxEvent e
            where e.status = :status
              and e.nextAttemptAt <= :now
            order by e.nextAttemptAt asc
            """)
    List<OutboxEvent> claimDue(@Param("status") OutboxEvent.Status status, @Param("now") Instant now, Pageable pageable);

    default List<OutboxEvent> claimDue(Instant now, Pageable pageable) {
        return claimDue(OutboxEvent.Status.PENDING, now, pageable);
    }

    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(String aggregateType, UUID aggregateId);
}
