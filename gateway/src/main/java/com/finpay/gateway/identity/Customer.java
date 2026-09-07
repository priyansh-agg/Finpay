package com.finpay.gateway.identity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
public class Customer {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "external_ref", nullable = false)
    private String externalRef;

    private String email;

    @Column(name = "first_seen_at", insertable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Customer(UUID merchantId, String externalRef, String email) {
        this.merchantId = merchantId;
        this.externalRef = externalRef;
        this.email = email;
    }
}
