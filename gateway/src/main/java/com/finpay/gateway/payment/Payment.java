package com.finpay.gateway.payment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String method;

    @Column(name = "payment_token", nullable = false)
    private String paymentToken;

    @Column(name = "device_fingerprint")
    private String deviceFingerprint;

    @Column(name = "ip_address")
    private String ipAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentState status = PaymentState.CREATED;

    @Column(name = "processor_ref")
    private String processorRef;

    @Column(name = "risk_decision")
    private String riskDecision;

    @Column(name = "risk_bucket")
    private String riskBucket;

    @Column(name = "risk_score")
    private BigDecimal riskScore;

    @Column(name = "failed_reason")
    private String failedReason;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Version
    private long version;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
