package com.finpay.gateway.risk;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer_payment_stats")
@Getter
@Setter
@NoArgsConstructor
public class CustomerPaymentStats {

    @Id
    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "rolling_avg_minor", nullable = false)
    private long rollingAvgMinor = 0;

    @Column(name = "captured_count", nullable = false)
    private long capturedCount = 0;

    @Column(name = "captured_sum_minor", nullable = false)
    private long capturedSumMinor = 0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public CustomerPaymentStats(UUID customerId) {
        this.customerId = customerId;
    }

    public void addCapture(long amountMinor) {
        this.capturedCount += 1;
        this.capturedSumMinor += amountMinor;
        this.rollingAvgMinor = capturedSumMinor / Math.max(1, capturedCount);
        this.updatedAt = Instant.now();
    }
}
