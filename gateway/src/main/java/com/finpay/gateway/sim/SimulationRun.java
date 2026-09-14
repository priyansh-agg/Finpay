package com.finpay.gateway.sim;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One call to {@code POST /v1/admin/simulate}, as a durable record (V16). {@code scenario} is
 * the request verbatim, so the run is reproducible from this row alone; {@code summary} is
 * filled in once generation finishes and is what {@code SimulationService.SimSummary} reports
 * back to the caller.
 */
@Entity
@Table(name = "simulation_runs")
@Getter
@Setter
@NoArgsConstructor
public class SimulationRun {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false)
    private long seed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String scenario;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "started_at", insertable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    private String summary;
}
