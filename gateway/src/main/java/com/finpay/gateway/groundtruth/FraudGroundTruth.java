package com.finpay.gateway.groundtruth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * HIDDEN ground truth (Doubt 2). NEVER exposed through a merchant-facing or standard
 * dashboard API — only the offline analytics / evaluation pipeline reads it.
 * {@code actuallyFraudulent} stays null until {@code resolveAt} passes (simulated
 * chargeback / dispute window), then a scheduled job resolves it.
 */
@Entity
@Table(name = "fraud_ground_truth")
@Getter
@Setter
@NoArgsConstructor
public class FraudGroundTruth {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "payment_id", nullable = false, unique = true)
    private UUID paymentId;

    @Column(name = "actually_fraudulent")
    private Boolean actuallyFraudulent;

    @Column(name = "fraud_type")
    private String fraudType;

    /** What the simulator planted for this transaction. Drives resolution. */
    @Column(name = "injected_fraud", nullable = false)
    private boolean injectedFraud = false;

    @Column(name = "resolve_at", nullable = false)
    private Instant resolveAt;

    @Column(nullable = false)
    private boolean resolved = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
