package com.finpay.gateway.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByLedgerTxId(UUID ledgerTxId);

    List<LedgerEntry> findByLedgerTxIdIn(java.util.Collection<UUID> ledgerTxIds);

    List<LedgerEntry> findByAccountId(UUID accountId);

    /** Per-transaction balance check: returns tx ids where debits != credits (should be empty). */
    @Query(value = """
            SELECT ledger_tx_id
            FROM ledger_entries
            GROUP BY ledger_tx_id
            HAVING SUM(CASE WHEN direction = 'DEBIT'  THEN amount_minor ELSE 0 END)
                 <> SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor ELSE 0 END)
            """, nativeQuery = true)
    List<UUID> findUnbalancedTransactionIds();

    @Query(value = "SELECT COALESCE(SUM(CASE WHEN direction='DEBIT'  THEN amount_minor ELSE 0 END),0) FROM ledger_entries", nativeQuery = true)
    long totalDebits();

    @Query(value = "SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount_minor ELSE 0 END),0) FROM ledger_entries", nativeQuery = true)
    long totalCredits();
}
