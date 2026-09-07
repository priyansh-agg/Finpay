package com.finpay.gateway.analyst;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "risk_explanations")
@Getter
@Setter
@NoArgsConstructor
public class RiskExplanation {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "payment_id", nullable = false, unique = true)
    private UUID paymentId;

    @Column(nullable = false, columnDefinition = "text")
    private String explanation;

    private String model;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "similar_cases", nullable = false)
    private String similarCases = "[]";

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
