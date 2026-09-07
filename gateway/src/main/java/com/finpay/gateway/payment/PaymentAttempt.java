package com.finpay.gateway.payment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Append-only record of every state transition for a payment. */
@Entity
@Table(name = "payment_attempts")
@Getter
@Setter
@NoArgsConstructor
public class PaymentAttempt {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status", nullable = false)
    private String toStatus;

    private String detail;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public PaymentAttempt(UUID paymentId, String fromStatus, String toStatus, String detail) {
        this.paymentId = paymentId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.detail = detail;
    }
}
