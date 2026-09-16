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
@Table(name = "analyst_reviews")
@Getter
@Setter
@NoArgsConstructor
public class AnalystReview {

    public enum QueueStatus { PENDING, DECIDED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    /** Inherited from the payment that queued this review; null outside a simulation run (V16). */
    @Column(name = "run_id")
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "queue_status", nullable = false)
    private QueueStatus queueStatus = QueueStatus.PENDING;

    /** HUMAN | SIMULATED_ANALYST | AUTO_CLEAR — who made the terminal decision. */
    @Column(name = "reviewer_type")
    private String reviewerType;

    /** CLEAR | REJECT */
    private String decision;

    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features_at_review", nullable = false)
    private String featuresAtReview = "{}";

    @Column(name = "risk_score_at_review")
    private BigDecimal riskScoreAtReview;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** Caller id once claimed — a real analyst's subject id, or the simulated-analyst bot's. */
    @Column(name = "assigned_to")
    private String assignedTo;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "sla_due_at")
    private Instant slaDueAt;
}
