package com.finpay.gateway.risk;

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
@Table(name = "risk_evaluations")
@Getter
@Setter
@NoArgsConstructor
public class RiskEvaluation {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_flags", nullable = false)
    private String ruleFlags = "[]";

    @Column(name = "rule_score", nullable = false)
    private BigDecimal ruleScore = BigDecimal.ZERO;

    @Column(name = "ml_score")
    private BigDecimal mlScore;

    @Column(name = "ml_model_version")
    private String mlModelVersion;

    @Column(name = "vector_similarity")
    private BigDecimal vectorSimilarity;

    @Column(name = "final_score", nullable = false)
    private BigDecimal finalScore;

    @Column(nullable = false)
    private String bucket;

    @Column(nullable = false)
    private String decision;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String features = "{}";

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
