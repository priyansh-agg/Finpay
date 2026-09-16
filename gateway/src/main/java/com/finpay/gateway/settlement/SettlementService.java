package com.finpay.gateway.settlement;

import com.finpay.gateway.common.audit.AuditService;
import com.finpay.gateway.common.metrics.FinPayMetrics;
import com.finpay.gateway.ledger.LedgerService;
import com.finpay.gateway.outbox.OutboxService;
import com.finpay.gateway.payment.PaymentRepository;
import com.finpay.gateway.payment.PaymentRepository.SettlementCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Settlement batch <b>simulation</b> (plan V1 stretch). On a schedule (default every 5 min
 * for demo purposes; a real gateway runs this nightly) it pays out every merchant's
 * <b>settlement-eligible</b> captures: a {@link Settlement} record + a balanced ledger
 * transaction moving the funds to {@code MERCHANT_SETTLED}. No real bank rails.
 *
 * <p><b>Settlement holdback (interview sprint Day 9).</b> A capture used to be payable the
 * instant it posted — including one auto-captured via the confidently-LOW shortcut and never
 * reviewed at all (Day 7's finding: a perfect stealth-fraud transaction satisfies that shortcut
 * by construction). A payment is now eligible only once its hidden fraud ground truth has
 * actually resolved as legitimate ({@link PaymentRepository#findSettlementEligible}) — giving
 * the simulated dispute window a real chance to catch it before the money leaves the platform.
 * One confirmed fraudulent instead ({@link PaymentRepository#findConfirmedFraudPendingWriteOff})
 * is written off every batch, whatever path captured it — review-based auto-clear or the
 * auto-capture bypass alike — so the merchant is never paid for it.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final PaymentRepository payments;
    private final LedgerService ledger;
    private final SettlementRepository settlements;
    private final SettlementBatchRepository batches;
    private final OutboxService outbox;
    private final AuditService audit;
    private final FinPayMetrics metrics;

    public SettlementService(PaymentRepository payments, LedgerService ledger,
                             SettlementRepository settlements, SettlementBatchRepository batches,
                             OutboxService outbox, AuditService audit, FinPayMetrics metrics) {
        this.payments = payments;
        this.ledger = ledger;
        this.settlements = settlements;
        this.batches = batches;
        this.outbox = outbox;
        this.audit = audit;
        this.metrics = metrics;
    }

    public record BatchResult(UUID batchId, int merchantCount, long totalMinor,
                              int writtenOffCount, long writtenOffMinor) {}

    @Scheduled(fixedDelayString = "${finpay.settlement.interval-ms:300000}", initialDelay = 60000)
    public void scheduledRun() {
        BatchResult r = runBatch();
        if (r.merchantCount() > 0 || r.writtenOffCount() > 0) {
            log.info("settlement batch {} paid {} merchants ({} minor), wrote off {} payments ({} minor)",
                    r.batchId(), r.merchantCount(), r.totalMinor(), r.writtenOffCount(), r.writtenOffMinor());
        }
    }

    @Transactional
    public BatchResult runBatch() {
        WriteOffResult wo = writeOffConfirmedFraud();

        SettlementBatch batch = batches.save(new SettlementBatch());
        int count = 0;
        long total = 0;

        Map<MerchantCurrency, List<SettlementCandidate>> eligibleByMerchant = payments.findSettlementEligible().stream()
                .collect(Collectors.groupingBy(p -> new MerchantCurrency(p.getMerchantId(), p.getCurrency())));

        for (var entry : eligibleByMerchant.entrySet()) {
            UUID merchantId = entry.getKey().merchantId();
            String currency = entry.getKey().currency();
            List<SettlementCandidate> eligible = entry.getValue();
            long amount = eligible.stream().mapToLong(SettlementCandidate::getNetAmountMinor).sum();
            if (amount <= 0) continue;

            Settlement s = new Settlement();
            s.setBatchId(batch.getId());
            s.setMerchantId(merchantId);
            s.setAmountMinor(amount);
            s.setCurrency(currency);
            settlements.save(s);
            s.setLedgerTxId(ledger.postSettlement(s.getId(), merchantId, amount, currency));

            payments.markSettled(eligible.stream().map(SettlementCandidate::getPaymentId).toList(),
                    s.getId(), Instant.now());

            outbox.publish("SETTLEMENT", s.getId(), merchantId, "settlement.paid", Map.of(
                    "settlementId", s.getId().toString(),
                    "merchantId", merchantId.toString(),
                    "amountMinor", amount,
                    "currency", currency));
            audit.record("SYSTEM", "settlement-batch", "SETTLEMENT_PAID", "SETTLEMENT", s.getId().toString(),
                    null, Map.of("merchantId", merchantId.toString(), "amountMinor", amount));
            count++;
            total += amount;
        }

        batch.setMerchantCount(count);
        batch.setTotalMinor(total);
        return new BatchResult(batch.getId(), count, total, wo.count(), wo.amountMinor());
    }

    private record MerchantCurrency(UUID merchantId, String currency) {}

    private record WriteOffResult(int count, long amountMinor) {}

    private WriteOffResult writeOffConfirmedFraud() {
        List<SettlementCandidate> fraud = payments.findConfirmedFraudPendingWriteOff();
        if (fraud.isEmpty()) return new WriteOffResult(0, 0);

        long total = 0;
        for (SettlementCandidate p : fraud) {
            ledger.postWriteOff(p.getPaymentId(), p.getMerchantId(), p.getNetAmountMinor(), p.getCurrency());
            metrics.fraudWriteOff(p.getNetAmountMinor());
            audit.record("SYSTEM", "settlement-batch", "FRAUD_WRITTEN_OFF", "PAYMENT", p.getPaymentId().toString(),
                    null, Map.of("merchantId", p.getMerchantId().toString(), "amountMinor", p.getNetAmountMinor()));
            log.error("FRAUD WRITE-OFF: payment {} confirmed fraudulent, withheld from merchant payout ({} minor)",
                    p.getPaymentId(), p.getNetAmountMinor());
            total += p.getNetAmountMinor();
        }
        payments.markWrittenOff(fraud.stream().map(SettlementCandidate::getPaymentId).toList(), Instant.now());
        return new WriteOffResult(fraud.size(), total);
    }

    @Transactional(readOnly = true)
    public Page<Settlement> forMerchant(UUID merchantId, Pageable pageable) {
        return settlements.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
    }
}
