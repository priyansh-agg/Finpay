package com.finpay.gateway.identity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_keys")
@Getter
@Setter
@NoArgsConstructor
public class ApiKey {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "key_prefix", nullable = false)
    private String keyPrefix;

    /** HMAC-SHA256(pepper, rawKey), hex. The raw key is never stored. */
    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;

    private String label;

    @Column(nullable = false)
    private String role = "MERCHANT";

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public boolean isActive() {
        return revokedAt == null;
    }
}
