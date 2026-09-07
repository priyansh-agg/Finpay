package com.finpay.gateway.ledger;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Append-only. A DB trigger (V2 migration) rejects UPDATE/DELETE on this table. */
@Entity
@Table(name = "ledger_entries")
@Getter
@Setter
@NoArgsConstructor
public class LedgerEntry {

    public enum Direction { DEBIT, CREDIT }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "ledger_tx_id", nullable = false)
    private UUID ledgerTxId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public LedgerEntry(UUID ledgerTxId, UUID accountId, Direction direction, long amountMinor, String currency) {
        this.ledgerTxId = ledgerTxId;
        this.accountId = accountId;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.currency = currency;
    }
}
