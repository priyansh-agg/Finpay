package com.finpay.gateway.common.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Structured audit trail for every state-changing action. Writes participate in the
 * caller's transaction (so an audit row and the change it describes commit together).
 */
@Service
public class AuditService {

    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actorType, String actorId, String action,
                       String entityType, String entityId,
                       Map<String, Object> before, Map<String, Object> after) {
        AuditLog row = new AuditLog();
        row.setActorType(actorType);
        row.setActorId(actorId);
        row.setAction(action);
        row.setEntityType(entityType);
        row.setEntityId(entityId);
        row.setBeforeState(before);
        row.setAfterState(after);
        repo.save(row);
    }
}
