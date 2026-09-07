package com.finpay.gateway.ledger;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_accounts")
@Getter
@Setter
@NoArgsConstructor
public class LedgerAccount {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "account_type", nullable = false)
    private String accountType;      // CUSTOMER_CLEARING | MERCHANT_PAYABLE | PLATFORM_FEES | REFUNDS_PAYABLE

    @Column(name = "owner_type", nullable = false)
    private String ownerType;        // MERCHANT | PLATFORM

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public LedgerAccount(String accountType, String ownerType, UUID ownerId, String currency) {
        this.accountType = accountType;
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.currency = currency;
    }
}
