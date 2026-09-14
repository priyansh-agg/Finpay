package com.finpay.gateway.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OutboxService {

    private final OutboxEventRepository repo;
    private final ObjectMapper mapper;

    public OutboxService(OutboxEventRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    /**
     * Enqueue a domain event. MUST be called inside the transaction that performs the
     * state change, so the event and the change commit atomically (or not at all).
     *
     * @param merchantId whose webhooks receive the event. Required, and deliberately a
     *                   parameter rather than something the dispatcher digs out of the payload:
     *                   the refund payload once omitted it, and every refund webhook was
     *                   silently dropped.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String aggregateType, UUID aggregateId, UUID merchantId, String eventType, Object payload) {
        if (merchantId == null) {
            throw new IllegalArgumentException("outbox event " + eventType + " has no merchant to route to");
        }
        OutboxEvent e = new OutboxEvent();
        e.setAggregateType(aggregateType);
        e.setAggregateId(aggregateId);
        e.setMerchantId(merchantId);
        e.setEventType(eventType);
        e.setPayload(toJson(payload));
        return repo.save(e).getId();
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot serialise outbox payload", ex);
        }
    }
}
