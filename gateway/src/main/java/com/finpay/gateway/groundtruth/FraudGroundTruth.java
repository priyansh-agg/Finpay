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

    /** For confirmed fraud: the pattern name the pgvector store and the analyst's explanation use. */
    @Column(name = "fraud_type")
    private String fraudType;

    public enum LabelSource { SIMULATION, LIVE, LEGACY_TOKEN }

    /** What the transaction's author says it is. Drives resolution (V14). */
    @Enumerated(EnumType.STRING)
    @Column(name = "intended_class", nullable = false)
    private GroundTruthLabel.IntendedClass intendedClass = GroundTruthLabel.IntendedClass.LEGIT;

    /** Who authored the label: the simulation harness, or nobody (LIVE traffic, assumed legitimate). */
    @Enumerated(EnumType.STRING)
    @Column(name = "label_source", nullable = false)
    private LabelSource labelSource = LabelSource.LIVE;

    @Column(name = "scenario_pattern")
    private String scenarioPattern;

    /** 0..1 — how much the transaction looks like the other class. */
    @Column(name = "disguise_level")
    private Double disguiseLevel;

    public boolean isFraudLabel() {
        return intendedClass == GroundTruthLabel.IntendedClass.FRAUD;
    }

    @Column(name = "resolve_at", nullable = false)
    private Instant resolveAt;

    @Column(nullable = false)
    private boolean resolved = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
