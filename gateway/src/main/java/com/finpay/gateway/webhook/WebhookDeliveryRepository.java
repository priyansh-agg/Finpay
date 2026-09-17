package com.finpay.gateway.webhook;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    boolean existsByEndpointIdAndOutboxEventId(UUID endpointId, UUID outboxEventId);

    @Query("""
            select d from WebhookDelivery d
            where d.status in :statuses
              and d.nextAttemptAt <= :now
            order by d.nextAttemptAt asc
            """)
    List<WebhookDelivery> findDue(@Param("statuses") java.util.Collection<WebhookDelivery.Status> statuses,
                                  @Param("now") Instant now, Pageable pageable);

    default List<WebhookDelivery> findDue(Instant now, Pageable pageable) {
        return findDue(java.util.List.of(WebhookDelivery.Status.PENDING, WebhookDelivery.Status.FAILED), now, pageable);
    }

    List<WebhookDelivery> findByOutboxEventId(UUID outboxEventId);

    List<WebhookDelivery> findByOutboxEventIdIn(java.util.Collection<UUID> outboxEventIds);
}
