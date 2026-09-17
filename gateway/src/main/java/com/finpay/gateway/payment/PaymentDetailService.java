package com.finpay.gateway.payment;

import com.finpay.gateway.analyst.AutoClearDecision;
import com.finpay.gateway.analyst.AutoClearDecisionRepository;
import com.finpay.gateway.common.audit.AuditLog;
import com.finpay.gateway.common.audit.AuditLogRepository;
import com.finpay.gateway.identity.Customer;
import com.finpay.gateway.identity.CustomerRepository;
import com.finpay.gateway.ledger.LedgerAccount;
import com.finpay.gateway.ledger.LedgerAccountRepository;
import com.finpay.gateway.ledger.LedgerEntry;
import com.finpay.gateway.ledger.LedgerEntryRepository;
import com.finpay.gateway.ledger.LedgerTransaction;
import com.finpay.gateway.ledger.LedgerTransactionRepository;
import com.finpay.gateway.outbox.OutboxEvent;
import com.finpay.gateway.outbox.OutboxEventRepository;
import com.finpay.gateway.refund.Refund;
import com.finpay.gateway.refund.RefundRepository;
import com.finpay.gateway.risk.CustomerPaymentStats;
import com.finpay.gateway.risk.CustomerPaymentStatsRepository;
import com.finpay.gateway.webhook.WebhookDelivery;
import com.finpay.gateway.webhook.WebhookDeliveryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Assembles the facets a Payment Detail view needs (interview sprint Day 12 — the audit's
 * §C.2: "4 of ~10 facets are retrievable and 0 are assembled"). Every method here is a read;
 * none of this ever feeds a decision — it exists to answer "what happened to this payment,"
 * the number-one gap the audit found.
 */
@Service
public class PaymentDetailService {

    private final PaymentAttemptRepository attempts;
    private final RefundRepository refunds;
    private final LedgerTransactionRepository ledgerTx;
    private final LedgerEntryRepository ledgerEntries;
    private final LedgerAccountRepository ledgerAccounts;
    private final OutboxEventRepository outboxEvents;
    private final WebhookDeliveryRepository webhookDeliveries;
    private final AuditLogRepository auditLogs;
    private final AutoClearDecisionRepository autoClearDecisions;
    private final CustomerRepository customers;
    private final CustomerPaymentStatsRepository customerStats;

    public PaymentDetailService(PaymentAttemptRepository attempts, RefundRepository refunds,
                                LedgerTransactionRepository ledgerTx, LedgerEntryRepository ledgerEntries,
                                LedgerAccountRepository ledgerAccounts, OutboxEventRepository outboxEvents,
                                WebhookDeliveryRepository webhookDeliveries, AuditLogRepository auditLogs,
                                AutoClearDecisionRepository autoClearDecisions, CustomerRepository customers,
                                CustomerPaymentStatsRepository customerStats) {
        this.attempts = attempts;
        this.refunds = refunds;
        this.ledgerTx = ledgerTx;
        this.ledgerEntries = ledgerEntries;
        this.ledgerAccounts = ledgerAccounts;
        this.outboxEvents = outboxEvents;
        this.webhookDeliveries = webhookDeliveries;
        this.auditLogs = auditLogs;
        this.autoClearDecisions = autoClearDecisions;
        this.customers = customers;
        this.customerStats = customerStats;
    }

    // --- timeline ---------------------------------------------------------

    public record TimelineEntry(String fromStatus, String toStatus, String detail, Instant createdAt) {}

    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(UUID paymentId) {
        return attempts.findByPaymentIdOrderByCreatedAtAsc(paymentId).stream()
                .map(a -> new TimelineEntry(a.getFromStatus(), a.getToStatus(), a.getDetail(), a.getCreatedAt()))
                .toList();
    }

    // --- ledger impact ------------------------------------------------------
    // A payment's ledger footprint isn't one lookup: a CAPTURE (and a FRAUD_WRITEOFF, if
    // confirmed fraud caught it before settlement — Day 9) reference the payment directly, but
    // a REFUND transaction references the refund's own id, and a SETTLEMENT transaction
    // references the settlement's id — so this walks payment -> refunds -> settlement to
    // collect every transaction that ever touched this payment's money.

    public record LedgerEntryView(String accountType, String ownerType, UUID ownerId, String direction,
                                  long amountMinor, String currency) {}
    public record LedgerTxView(UUID id, String txType, String memo, Instant createdAt, List<LedgerEntryView> entries) {}

    @Transactional(readOnly = true)
    public List<LedgerTxView> ledgerImpact(Payment payment) {
        List<LedgerTransaction> txs = new java.util.ArrayList<>(
                ledgerTx.findByReferenceTypeAndReferenceId("PAYMENT", payment.getId()));
        for (Refund r : refunds.findByPaymentId(payment.getId())) {
            txs.addAll(ledgerTx.findByReferenceTypeAndReferenceId("REFUND", r.getId()));
        }
        if (payment.getSettlementId() != null) {
            txs.addAll(ledgerTx.findByReferenceTypeAndReferenceId("SETTLEMENT", payment.getSettlementId()));
        }
        if (txs.isEmpty()) return List.of();

        List<UUID> txIds = txs.stream().map(LedgerTransaction::getId).toList();
        Map<UUID, List<LedgerEntry>> entriesByTx = ledgerEntries.findByLedgerTxIdIn(txIds).stream()
                .collect(Collectors.groupingBy(LedgerEntry::getLedgerTxId));
        Map<UUID, LedgerAccount> accountsById = ledgerAccounts.findAllById(
                entriesByTx.values().stream().flatMap(List::stream).map(LedgerEntry::getAccountId).distinct().toList()
        ).stream().collect(Collectors.toMap(LedgerAccount::getId, a -> a));

        return txs.stream()
                .sorted(Comparator.comparing(LedgerTransaction::getCreatedAt))
                .map(tx -> new LedgerTxView(tx.getId(), tx.getTxType(), tx.getMemo(), tx.getCreatedAt(),
                        entriesByTx.getOrDefault(tx.getId(), List.of()).stream()
                                .map(e -> {
                                    LedgerAccount a = accountsById.get(e.getAccountId());
                                    return new LedgerEntryView(
                                            a == null ? null : a.getAccountType(), a == null ? null : a.getOwnerType(),
                                            a == null ? null : a.getOwnerId(),
                                            e.getDirection().name(), e.getAmountMinor(), e.getCurrency());
                                })
                                .toList()))
                .toList();
    }

    // --- webhook activity ---------------------------------------------------

    public record DeliveryView(UUID endpointId, String status, int attempts, Integer lastStatusCode,
                               String lastError, Instant deliveredAt) {}
    public record OutboxEventView(UUID id, String eventType, String status, int attempts,
                                  Instant createdAt, Instant publishedAt, List<DeliveryView> deliveries) {}

    @Transactional(readOnly = true)
    public List<OutboxEventView> webhookActivity(UUID paymentId) {
        List<OutboxEvent> events = outboxEvents.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("PAYMENT", paymentId);
        if (events.isEmpty()) return List.of();

        List<UUID> eventIds = events.stream().map(OutboxEvent::getId).toList();
        Map<UUID, List<WebhookDelivery>> deliveriesByEvent = webhookDeliveries.findByOutboxEventIdIn(eventIds).stream()
                .collect(Collectors.groupingBy(WebhookDelivery::getOutboxEventId));

        return events.stream()
                .map(e -> new OutboxEventView(e.getId(), e.getEventType(), e.getStatus().name(), e.getAttempts(),
                        e.getCreatedAt(), e.getPublishedAt(),
                        deliveriesByEvent.getOrDefault(e.getId(), List.of()).stream()
                                .map(d -> new DeliveryView(d.getEndpointId(), d.getStatus().name(), d.getAttempts(),
                                        d.getLastStatusCode(), d.getLastError(), d.getDeliveredAt()))
                                .toList()))
                .toList();
    }

    // --- audit trail ---------------------------------------------------

    public record AuditEntryView(Long seq, String actorType, String actorId, String action,
                                 Map<String, Object> beforeState, Map<String, Object> afterState, Instant createdAt) {}

    @Transactional(readOnly = true)
    public List<AuditEntryView> auditTrail(UUID paymentId) {
        return auditLogs.findByEntityTypeAndEntityIdOrderBySeqAsc("PAYMENT", paymentId.toString(), PageRequest.of(0, 200))
                .map(a -> new AuditEntryView(a.getSeq(), a.getActorType(), a.getActorId(), a.getAction(),
                        a.getBeforeState(), a.getAfterState(), a.getCreatedAt()))
                .getContent();
    }

    // --- auto-clear status ---------------------------------------------------

    public record AutoClearDecisionView(UUID reviewId, double confidence, double threshold, boolean autoCleared,
                                        String modelVersion, Boolean laterConfirmedFraud, Instant createdAt) {}

    @Transactional(readOnly = true)
    public List<AutoClearDecisionView> autoClearStatus(UUID paymentId) {
        return autoClearDecisions.findByPaymentIdOrderByCreatedAtDesc(paymentId).stream()
                .map(d -> new AutoClearDecisionView(d.getReviewId(), d.getConfidence().doubleValue(),
                        d.getThreshold().doubleValue(), d.isAutoCleared(), d.getModelVersion(),
                        d.getLaterConfirmedFraud(), d.getCreatedAt()))
                .toList();
    }

    // --- customer ---------------------------------------------------

    public record CustomerView(UUID id, String externalRef, String email, Instant firstSeenAt,
                               long capturedCount, long capturedSumMinor, long rollingAvgMinor) {}

    @Transactional(readOnly = true)
    public CustomerView customer(UUID customerId) {
        Customer c = customers.findById(customerId).orElse(null);
        if (c == null) return null;
        CustomerPaymentStats stats = customerStats.findById(customerId).orElse(null);
        return new CustomerView(c.getId(), c.getExternalRef(), c.getEmail(), c.getFirstSeenAt(),
                stats == null ? 0 : stats.getCapturedCount(),
                stats == null ? 0 : stats.getCapturedSumMinor(),
                stats == null ? 0 : stats.getRollingAvgMinor());
    }
}
