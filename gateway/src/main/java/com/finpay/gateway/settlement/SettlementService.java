package com.finpay.gateway.settlement;

import com.finpay.gateway.common.audit.AuditService;
import com.finpay.gateway.ledger.LedgerAccount;
import com.finpay.gateway.ledger.LedgerAccountRepository;
import com.finpay.gateway.ledger.LedgerService;
import com.finpay.gateway.outbox.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Settlement batch <b>simulation</b> (plan V1 stretch). On a schedule (default every 5 min
 * for demo purposes; a real gateway runs this nightly) it sweeps every merchant's accrued
 * {@code MERCHANT_PAYABLE} balance into a payout: a {@link Settlement} record + a balanced
 * ledger transaction moving the funds to {@code MERCHANT_SETTLED}. No real bank rails.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final LedgerAccountRepository accounts;
    private final LedgerService ledger;
    private final SettlementRepository settlements;
    private final SettlementBatchRepository batches;
    private final OutboxService outbox;
    private final AuditService audit;

    public SettlementService(LedgerAccountRepository accounts, LedgerService ledger,
                             SettlementRepository settlements, SettlementBatchRepository batches,
                             OutboxService outbox, AuditService audit) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.settlements = settlements;
        this.batches = batches;
        this.outbox = outbox;
        this.audit = audit;
    }

    public record BatchResult(UUID batchId, int merchantCount, long totalMinor) {}

    @Scheduled(fixedDelayString = "${finpay.settlement.interval-ms:300000}", initialDelay = 60000)
    public void scheduledRun() {
        BatchResult r = runBatch();
        if (r.merchantCount() > 0) {
            log.info("settlement batch {} paid {} merchants, total {} minor", r.batchId(), r.merchantCount(), r.totalMinor());
        }
    }

    @Transactional
    public BatchResult runBatch() {
        SettlementBatch batch = batches.save(new SettlementBatch());
        int count = 0;
        long total = 0;

        for (LedgerAccount payable : accounts.findByAccountType("MERCHANT_PAYABLE")) {
            if (payable.getOwnerId() == null) continue;
            long bal = ledger.balance(payable.getId()); // CREDIT - DEBIT: what we still owe
            if (bal <= 0) continue;

            Settlement s = new Settlement();
            s.setBatchId(batch.getId());
            s.setMerchantId(payable.getOwnerId());
            s.setAmountMinor(bal);
            s.setCurrency(payable.getCurrency());
            settlements.save(s);
            s.setLedgerTxId(ledger.postSettlement(s.getId(), payable.getOwnerId(), bal, payable.getCurrency()));

            outbox.publish("SETTLEMENT", s.getId(), "settlement.paid", Map.of(
                    "settlementId", s.getId().toString(),
                    "merchantId", payable.getOwnerId().toString(),
                    "amountMinor", bal,
                    "currency", payable.getCurrency()));
            audit.record("SYSTEM", "settlement-batch", "SETTLEMENT_PAID", "SETTLEMENT", s.getId().toString(),
                    null, Map.of("merchantId", payable.getOwnerId().toString(), "amountMinor", bal));
            count++;
            total += bal;
        }

        batch.setMerchantCount(count);
        batch.setTotalMinor(total);
        return new BatchResult(batch.getId(), count, total);
    }

    @Transactional(readOnly = true)
    public Page<Settlement> forMerchant(UUID merchantId, Pageable pageable) {
        return settlements.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
    }
}
