package com.finpay.gateway.settlement;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlement_batches")
@Getter
@Setter
@NoArgsConstructor
public class SettlementBatch {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "run_at", insertable = false, updatable = false)
    private Instant runAt;

    @Column(name = "merchant_count", nullable = false)
    private int merchantCount;

    @Column(name = "total_minor", nullable = false)
    private long totalMinor;

    @Column(nullable = false, length = 3)
    private String currency = "INR";
}
