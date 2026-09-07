package com.finpay.gateway.identity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "devices")
@Getter
@Setter
@NoArgsConstructor
public class Device {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false)
    private String fingerprint;

    @Column(name = "first_seen_at", insertable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;
}
