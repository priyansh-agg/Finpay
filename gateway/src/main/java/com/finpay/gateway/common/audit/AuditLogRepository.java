package com.finpay.gateway.common.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * Ordered by {@code seq}, not {@code created_at}: several rows from one request commonly
     * share a timestamp (Postgres's {@code now()} is transaction start time), so only
     * {@code seq} — a real, gapless sequence — puts them in the order they actually happened.
     */
    Page<AuditLog> findByEntityTypeAndEntityIdOrderBySeqAsc(String entityType, String entityId, Pageable pageable);

    Page<AuditLog> findAllByOrderBySeqDesc(Pageable pageable);
}
