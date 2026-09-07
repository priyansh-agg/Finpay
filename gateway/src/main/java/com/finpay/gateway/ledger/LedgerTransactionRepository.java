package com.finpay.gateway.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {
    List<LedgerTransaction> findByReferenceTypeAndReferenceId(String referenceType, UUID referenceId);
}
