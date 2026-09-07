package com.finpay.gateway.analyst;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "autoclear_decisions")
@Getter
@Setter
@NoArgsConstructor
public class AutoClearDecision {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "review_id")
    private UUID reviewId;

    @Column(nullable = false)
    private BigDecimal confidence;

    @Column(nullable = false)
    private BigDecimal threshold;

    @Column(name = "auto_cleared", nullable = false)
    private boolean autoCleared;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "top_features", nullable = false)
    private String topFeatures = "[]";

    @Column(name = "model_version")
    private String modelVersion;

    /** Set later by the ground-truth reconciler. TRUE = a FALSE AUTO-CLEAR (critical bug). */
    @Column(name = "later_confirmed_fraud")
    private Boolean laterConfirmedFraud;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
