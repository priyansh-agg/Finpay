package com.finpay.gateway.settlement;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlements")
@Getter
@Setter
@NoArgsConstructor
public class Settlement {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "ledger_tx_id")
    private UUID ledgerTxId;

    @Column(nullable = false)
    private String status = "PAID";

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
