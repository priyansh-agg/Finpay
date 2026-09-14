package com.finpay.gateway.common.audit;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue
    private UUID id;

    /**
     * Gapless creation order (V15), DB-generated. Several rows from one request commonly
     * share the same {@code created_at} — Postgres's {@code now()} is the transaction start
     * time, not the statement time — so {@code seq}, not {@code created_at}, is what orders
     * "what happened first" correctly.
     */
    @Column(insertable = false, updatable = false)
    private Long seq;

    @Column(name = "actor_type", nullable = false)
    private String actorType;

    @Column(name = "actor_id")
    private String actorId;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state")
    private Map<String, Object> beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state")
    private Map<String, Object> afterState;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
