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
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        OutboxEvent e = new OutboxEvent();
        e.setAggregateType(aggregateType);
        e.setAggregateId(aggregateId);
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
