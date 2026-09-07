package com.finpay.gateway.ledger;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_transactions")
@Getter
@Setter
@NoArgsConstructor
public class LedgerTransaction {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tx_type", nullable = false)
    private String txType;           // CAPTURE | REFUND | REVERSAL | ADJUSTMENT

    @Column(name = "reference_type", nullable = false)
    private String referenceType;    // PAYMENT | REFUND

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    private String memo;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public LedgerTransaction(String txType, String referenceType, UUID referenceId, String memo) {
        this.txType = txType;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.memo = memo;
    }
}
