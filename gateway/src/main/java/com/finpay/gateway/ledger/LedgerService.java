package com.finpay.gateway.ledger;

import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.ledger.LedgerEntry.Direction;
import com.finpay.gateway.ledger.LedgerAccountRepository;
import com.finpay.gateway.ledger.LedgerEntryRepository;
import com.finpay.gateway.ledger.LedgerTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Double-entry ledger. Every posting writes a {@link LedgerTransaction} plus >= 2
 * {@link LedgerEntry} rows whose debits and credits are equal. Entries are append-only:
 * a reversal is a NEW offsetting transaction, never an edit.
 *
 * <p>Account model (all balances tracked as CREDIT - DEBIT):
 * <ul>
 *   <li>{@code CUSTOMER_CLEARING} (platform) — funds collected from customers, pre-settlement</li>
 *   <li>{@code MERCHANT_PAYABLE} (merchant) — what the platform owes this merchant</li>
 * </ul>
 */
@Service
public class LedgerService {

    private final LedgerAccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;

    public LedgerService(LedgerAccountRepository accounts, LedgerTransactionRepository transactions,
                         LedgerEntryRepository entries) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
    }

    /** Capture: customer clearing pays out, merchant payable is credited. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID postCapture(UUID paymentId, UUID merchantId, long amountMinor, String currency) {
        if (!transactions.findByReferenceTypeAndReferenceId("PAYMENT", paymentId).isEmpty()) {
            throw ApiException.conflict("Ledger already has a CAPTURE for this payment");
        }
        LedgerAccount clearing = platformAccount("CUSTOMER_CLEARING", currency);
        LedgerAccount payable = merchantAccount("MERCHANT_PAYABLE", merchantId, currency);

        LedgerTransaction tx = transactions.save(
                new LedgerTransaction("CAPTURE", "PAYMENT", paymentId, "capture of payment " + paymentId));
        post(tx.getId(), clearing.getId(), Direction.DEBIT, amountMinor, currency);
        post(tx.getId(), payable.getId(), Direction.CREDIT, amountMinor, currency);
        assertBalanced(tx.getId());
        return tx.getId();
    }

    /** Refund: reduce what we owe the merchant, clearing account owes the customer back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID postRefund(UUID refundId, UUID paymentId, UUID merchantId, long amountMinor, String currency) {
        if (!transactions.findByReferenceTypeAndReferenceId("REFUND", refundId).isEmpty()) {
            throw ApiException.conflict("Ledger already has a REFUND for this refund id");
        }
        LedgerAccount clearing = platformAccount("CUSTOMER_CLEARING", currency);
        LedgerAccount payable = merchantAccount("MERCHANT_PAYABLE", merchantId, currency);

        LedgerTransaction tx = transactions.save(
                new LedgerTransaction("REFUND", "REFUND", refundId, "refund of payment " + paymentId));
        post(tx.getId(), payable.getId(), Direction.DEBIT, amountMinor, currency);
        post(tx.getId(), clearing.getId(), Direction.CREDIT, amountMinor, currency);
        assertBalanced(tx.getId());
        return tx.getId();
    }

    /** Settlement: sweep a merchant's accrued payable balance into a payout. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID postSettlement(UUID settlementId, UUID merchantId, long amountMinor, String currency) {
        LedgerAccount payable = merchantAccount("MERCHANT_PAYABLE", merchantId, currency);
        LedgerAccount settled = platformAccount("MERCHANT_SETTLED", currency);
        LedgerTransaction tx = transactions.save(
                new LedgerTransaction("SETTLEMENT", "SETTLEMENT", settlementId, "payout to merchant " + merchantId));
        post(tx.getId(), payable.getId(), Direction.DEBIT, amountMinor, currency);
        post(tx.getId(), settled.getId(), Direction.CREDIT, amountMinor, currency);
        assertBalanced(tx.getId());
        return tx.getId();
    }

    @Transactional(readOnly = true)
    public long balance(UUID accountId) {
        long bal = 0;
        for (LedgerEntry e : entries.findByAccountId(accountId)) {
            bal += e.getDirection() == Direction.CREDIT ? e.getAmountMinor() : -e.getAmountMinor();
        }
        return bal;
    }

    @Transactional(readOnly = true)
    public long merchantPayableBalance(UUID merchantId, String currency) {
        return accounts.findByAccountTypeAndOwnerIdAndCurrency("MERCHANT_PAYABLE", merchantId, currency)
                .map(a -> balance(a.getId())).orElse(0L);
    }

    // --- reconciliation --------------------------------------------------

    public record Reconciliation(boolean balanced, long totalDebits, long totalCredits, List<UUID> unbalancedTxIds) {}

    @Transactional(readOnly = true)
    public Reconciliation reconcile() {
        long d = entries.totalDebits();
        long c = entries.totalCredits();
        List<UUID> bad = entries.findUnbalancedTransactionIds();
        return new Reconciliation(d == c && bad.isEmpty(), d, c, bad);
    }

    // --- internals -----------------------------------------------------

    private void post(UUID txId, UUID accountId, Direction dir, long amount, String currency) {
        if (amount <= 0) throw ApiException.validation("Ledger entry amount must be positive");
        entries.save(new LedgerEntry(txId, accountId, dir, amount, currency));
    }

    private void assertBalanced(UUID txId) {
        long debit = 0, credit = 0;
        for (LedgerEntry e : entries.findByLedgerTxId(txId)) {
            if (e.getDirection() == Direction.DEBIT) debit += e.getAmountMinor();
            else credit += e.getAmountMinor();
        }
        if (debit != credit) {
            throw new IllegalStateException("Unbalanced ledger transaction " + txId + " (D=" + debit + " C=" + credit + ")");
        }
    }

    private LedgerAccount platformAccount(String type, String currency) {
        return accounts.findPlatformAccount(type, currency)
                .orElseGet(() -> accounts.save(new LedgerAccount(type, "PLATFORM", null, currency)));
    }

    private LedgerAccount merchantAccount(String type, UUID merchantId, String currency) {
        return accounts.findByAccountTypeAndOwnerIdAndCurrency(type, merchantId, currency)
                .orElseGet(() -> accounts.save(new LedgerAccount(type, "MERCHANT", merchantId, currency)));
    }
}
