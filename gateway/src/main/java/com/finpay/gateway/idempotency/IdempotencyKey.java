package com.finpay.gateway.idempotency;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One claimed Idempotency-Key. Uniqueness is per merchant, not global (see V10) — the key
 * string is chosen by the caller, so scoping it globally would let one merchant's
 * "order-42" collide with another's.
 */
@Entity
@Table(name = "idempotency_keys",
       uniqueConstraints = @UniqueConstraint(name = "ux_idem_merchant_key",
                                             columnNames = {"merchant_id", "idem_key"}))
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyKey {

    public enum Status { PROCESSING, COMPLETED, FAILED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "idem_key", nullable = false)
    private String idemKey;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false)
    private String endpoint;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PROCESSING;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
