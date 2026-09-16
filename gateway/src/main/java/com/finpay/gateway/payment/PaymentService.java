package com.finpay.gateway.payment;

import com.finpay.gateway.analyst.api.ReviewQueuePort;
import com.finpay.gateway.common.audit.AuditService;
import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.groundtruth.GroundTruthLabel;
import com.finpay.gateway.groundtruth.GroundTruthService;
import com.finpay.gateway.identity.Device;
import com.finpay.gateway.identity.DeviceRepository;
import com.finpay.gateway.ledger.LedgerService;
import com.finpay.gateway.order.Order;
import com.finpay.gateway.order.OrderRepository;
import com.finpay.gateway.order.OrderService;
import com.finpay.gateway.outbox.OutboxService;
import com.finpay.gateway.payment.simulator.PaymentSimulator;
import com.finpay.gateway.risk.CustomerPaymentStats;
import com.finpay.gateway.risk.CustomerPaymentStatsRepository;
import com.finpay.gateway.risk.FeatureStore;
import com.finpay.gateway.risk.api.RiskGate;
import com.finpay.gateway.risk.api.RiskGate.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** Attempts that ended without moving money; they never block another attempt on the order. */
    static final java.util.Set<PaymentState> DEAD_STATES =
            java.util.EnumSet.of(PaymentState.FAILED, PaymentState.VOIDED, PaymentState.CANCELLED);

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final DeviceRepository devices;
    private final PaymentSimulator simulator;
    private final RiskGate riskGate;
    private final LedgerService ledger;
    private final OutboxService outbox;
    private final ReviewQueuePort reviewQueue;
    private final GroundTruthService groundTruth;
    private final AuditService audit;
    private final FeatureStore featureStore;
    private final CustomerPaymentStatsRepository customerStats;
    private final com.finpay.gateway.common.metrics.FinPayMetrics metrics;
    private final int autoCaptureMax;

    public PaymentService(PaymentRepository payments, PaymentAttemptRepository attempts, OrderRepository orders,
                          OrderService orderService, DeviceRepository devices, PaymentSimulator simulator,
                          RiskGate riskGate, LedgerService ledger, OutboxService outbox, ReviewQueuePort reviewQueue,
                          GroundTruthService groundTruth, AuditService audit, FeatureStore featureStore,
                          CustomerPaymentStatsRepository customerStats,
                          com.finpay.gateway.common.metrics.FinPayMetrics metrics,
                          @Value("${finpay.risk.auto-capture-max:15}") int autoCaptureMax) {
        this.metrics = metrics;
        this.payments = payments;
        this.attempts = attempts;
        this.orders = orders;
        this.orderService = orderService;
        this.devices = devices;
        this.simulator = simulator;
        this.riskGate = riskGate;
        this.ledger = ledger;
        this.outbox = outbox;
        this.reviewQueue = reviewQueue;
        this.groundTruth = groundTruth;
        this.audit = audit;
        this.featureStore = featureStore;
        this.customerStats = customerStats;
        this.autoCaptureMax = autoCaptureMax;
    }

    /**
     * @param label      hidden ground truth, set ONLY by the simulation harness. The API and the
     *                   hosted checkout build this command from request DTOs that have no such
     *                   field, so no outside caller can author a fraud label. Null for real traffic.
     * @param simulation which simulation run this payment belongs to, and whether it is
     *                   population or warm-up scenery — also simulation-harness-only, for the
     *                   same reason. Null for real traffic.
     */
    public record CreatePaymentCommand(UUID merchantId, UUID orderId, String method, String token,
                                       String deviceFingerprint, String ipAddress, GroundTruthLabel label,
                                       SimulationContext simulation) {

        /** Real traffic — API or hosted checkout. Carries no ground-truth label or run. */
        public CreatePaymentCommand(UUID merchantId, UUID orderId, String method, String token,
                                    String deviceFingerprint, String ipAddress) {
            this(merchantId, orderId, method, token, deviceFingerprint, ipAddress, null, null);
        }
    }

    /**
     * The full create-payment flow. Runs inside the IdempotencyService transaction, so
     * either the whole thing commits (payment + attempts + ledger + outbox + ground truth)
     * or none of it does.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment createPayment(CreatePaymentCommand cmd) {
        // Lock the order for the rest of this transaction. The two checks below are
        // check-then-insert; without the lock, two requests carrying DIFFERENT idempotency
        // keys could both pass them before either inserts, and one order would take two
        // payments. (Idempotency only dedupes the SAME key.)
        Order order = orders.findByIdAndMerchantIdForUpdate(cmd.orderId(), cmd.merchantId())
                .orElseThrow(() -> ApiException.notFound("Order"));
        if (!"CREATED".equals(order.getStatus())) {
            throw ApiException.conflict("Order is not payable (status=" + order.getStatus() + ")");
        }
        // The order only turns PAID on capture, so a status check alone lets a second payment
        // through while the first is HELD, in review, or authorised-awaiting-capture. A failed,
        // voided or cancelled attempt does NOT block a retry — a customer whose card was
        // declined must be able to try another one.
        payments.findFirstByOrderIdAndStatusNotIn(order.getId(), DEAD_STATES).ifPresent(live -> {
            throw ApiException.conflict("Order already has a payment in progress ("
                    + live.getId() + ", status=" + live.getStatus() + ")");
        });

        Payment p = new Payment();
        p.setOrderId(order.getId());
        p.setMerchantId(order.getMerchantId());
        p.setCustomerId(order.getCustomerId());
        p.setAmountMinor(order.getAmountMinor());
        p.setCurrency(order.getCurrency());
        p.setMethod(cmd.method().toUpperCase());
        p.setPaymentToken(cmd.token());
        p.setDeviceFingerprint(cmd.deviceFingerprint());
        p.setIpAddress(cmd.ipAddress());
        p.setStatus(PaymentState.CREATED);
        if (cmd.simulation() != null) {
            p.setRunId(cmd.simulation().runId());
            p.setWarmup(cmd.simulation().warmup());
        }
        payments.save(p);
        recordAttempt(p, null, PaymentState.CREATED, "payment created");
        audit.record("MERCHANT", cmd.merchantId().toString(), "PAYMENT_CREATED", "PAYMENT", p.getId().toString(),
                null, Map.of("orderId", order.getId().toString(), "amountMinor", p.getAmountMinor(),
                        "currency", p.getCurrency(), "method", p.getMethod()));

        transition(p, PaymentState.PENDING, "queued");
        transition(p, PaymentState.RISK_EVALUATING, "risk gate");

        // --- Fraud gate BEFORE the processor is touched (Doubt 6) ---
        RiskGate.RiskResult risk = riskGate.evaluate(new RiskGate.RiskContext(
                p.getId(), p.getMerchantId(), p.getCustomerId(), p.getAmountMinor(), p.getCurrency(),
                p.getMethod(), p.getDeviceFingerprint(), p.getIpAddress(), p.getPaymentToken()));
        p.setRiskDecision(risk.decision().name());
        p.setRiskBucket(risk.bucket().name());
        p.setRiskScore(BigDecimal.valueOf(risk.finalScore()));
        metrics.riskDecision(risk.decision().name(), risk.bucket().name());

        seenDevice(p);
        plantGroundTruth(p, cmd.label());

        if (risk.decision() == Decision.BLOCK) {
            fail(p, "risk_blocked");
            return p;
        }
        if (risk.decision() == Decision.REVIEW) {
            transition(p, PaymentState.PENDING_REVIEW, "risk decision REVIEW");
            reviewQueue.enqueue(p.getId(), "risk.decision=REVIEW", risk.finalScore(), riskSnapshot(p, risk), p.getRunId());
            outbox.publish("PAYMENT", p.getId(), p.getMerchantId(), "risk.flagged", eventPayload(p, "REVIEW"));
            metrics.paymentOutcome("review");
            return p;
        }

        // --- ALLOW: call the simulated processor ---
        transition(p, PaymentState.PROCESSING, "processor call");
        PaymentSimulator.Result result = simulator.process(p.getMethod(), p.getPaymentToken(), p.getAmountMinor(), p.getCurrency());
        if (result.outcome() == PaymentSimulator.Outcome.DECLINED) {
            fail(p, result.declineReason());
            return p;
        }
        p.setProcessorRef(result.processorRef());
        transition(p, PaymentState.AUTHORIZED, "authorized by processor");
        p.setAuthorizedAt(Instant.now());

        // --- Capture decision by risk tier (architecture-freeze Doubt 4) ---
        // Only a confidently-LOW score auto-captures. Everything else is AUTHORISED but the
        // funds stay held until a decision is made — so a wrong "clear" costs nothing (the
        // authorisation is just voided). The loss-bearing auto-clear path is the LOW bucket only.
        boolean autoCapture = risk.autoCaptureEligible()
                && risk.bucket() == RiskGate.Bucket.LOW
                && risk.finalScore() <= autoCaptureMax;

        if (autoCapture) {
            doCapture(p, "auto-capture (LOW risk, score " + risk.finalScore() + ")");
            outbox.publish("PAYMENT", p.getId(), p.getMerchantId(), "payment.captured", eventPayload(p, "AUTO"));
        } else if (risk.bucket() == RiskGate.Bucket.LOW || risk.bucket() == RiskGate.Bucket.MEDIUM) {
            transition(p, PaymentState.HELD, risk.bucket() + " risk — funds held pending review");
            reviewQueue.enqueue(p.getId(), "held:" + risk.bucket(), risk.finalScore(), riskSnapshot(p, risk), p.getRunId());
            outbox.publish("PAYMENT", p.getId(), p.getMerchantId(), "payment.held", eventPayload(p, "HELD"));
            metrics.paymentOutcome("held");
            audit.record("SYSTEM", "payment-service", "PAYMENT_HELD", "PAYMENT", p.getId().toString(),
                    Map.of("status", "AUTHORIZED"),
                    Map.of("status", "HELD", "riskBucket", risk.bucket().name(), "riskScore", risk.finalScore()));
        } else {
            // HIGH / CRITICAL that got authorised -> void the authorisation immediately
            transition(p, PaymentState.VOIDED, risk.bucket() + " risk — authorization voided");
            outbox.publish("PAYMENT", p.getId(), p.getMerchantId(), "payment.voided", eventPayload(p, "VOID"));
            metrics.paymentOutcome("voided");
            audit.record("SYSTEM", "payment-service", "PAYMENT_VOIDED", "PAYMENT", p.getId().toString(),
                    Map.of("status", "AUTHORIZED"),
                    Map.of("status", "VOIDED", "riskBucket", risk.bucket().name(), "riskScore", risk.finalScore()));
        }
        return p;
    }

    // --- explicit capture (AUTHORIZED->CAPTURED, or HELD->CAPTURED after analyst clear) ---

    @Transactional
    public Payment capture(UUID merchantId, UUID paymentId) {
        Payment p = lockOwned(merchantId, paymentId);
        if (p.getStatus() != PaymentState.AUTHORIZED && p.getStatus() != PaymentState.HELD) {
            throw ApiException.conflict("Payment is not capturable (status=" + p.getStatus() + ")");
        }
        doCapture(p, "manual capture");
        outbox.publish("PAYMENT", p.getId(), p.getMerchantId(), "payment.captured", eventPayload(p, "MANUAL"));
        return p;
    }

    /**
     * Used by the analyst module to complete a HELD/PENDING_REVIEW payment.
     *
     * @param actor who decided: {@code "AUTO_CLEAR"} (the proposer), {@code "SIMULATED_ANALYST"}
     *              (the demo-only stand-in) — both algorithmic — or {@code "HUMAN"} (a
     *              dashboard user). Every call is one
     *              review decision and is audited as one, whatever it resolves to — including
     *              the case below where a review-cleared payment is still declined by the
     *              (re-run) processor.
     */
    @Transactional
    public Payment resolveReview(UUID paymentId, boolean clear, String actor, String reason) {
        Payment p = payments.findByIdForUpdate(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        PaymentState before = p.getStatus();
        if (before != PaymentState.HELD && before != PaymentState.PENDING_REVIEW) {
            throw ApiException.conflict("Payment is not under review (status=" + before + ")");
        }
        if (clear) {
            if (before == PaymentState.PENDING_REVIEW) {
                // resume the processor path
                transition(p, PaymentState.PROCESSING, "review cleared by " + actor);
                PaymentSimulator.Result r = simulator.process(p.getMethod(), p.getPaymentToken(), p.getAmountMinor(), p.getCurrency());
                if (r.outcome() == PaymentSimulator.Outcome.DECLINED) {
                    fail(p, r.declineReason());
                    auditReviewDecision(p, before, actor, reason, "CLEAR");
                    return p;
                }
                p.setProcessorRef(r.processorRef());
                transition(p, PaymentState.AUTHORIZED, "authorized after review");
                p.setAuthorizedAt(Instant.now());
            }
            doCapture(p, "review cleared: " + reason);
            outbox.publish("PAYMENT", p.getId(), p.getMerchantId(), "payment.captured", eventPayload(p, "REVIEW_CLEAR"));
        } else {
            PaymentState from = p.getStatus();
            transition(p, from == PaymentState.HELD ? PaymentState.VOIDED : PaymentState.FAILED, "review rejected: " + reason);
            p.setFailedReason("review_rejected");
            outbox.publish("PAYMENT", p.getId(), p.getMerchantId(), "payment.rejected", eventPayload(p, "REVIEW_REJECT"));
        }
        auditReviewDecision(p, before, actor, reason, clear ? "CLEAR" : "REJECT");
        return p;
    }

    private void auditReviewDecision(Payment p, PaymentState before, String actor, String reason, String decision) {
        String actorType = "HUMAN".equals(actor) ? "USER" : "SYSTEM";
        audit.record(actorType, actor, "REVIEW_DECIDED", "PAYMENT", p.getId().toString(),
                Map.of("status", before.name()),
                Map.of("decision", decision, "reviewedBy", actor, "reason", reason == null ? "" : reason,
                        "resultingStatus", p.getStatus().name()));
    }

    // --- reads ---

    @Transactional(readOnly = true)
    public Payment get(UUID merchantId, UUID paymentId) {
        return payments.findByIdAndMerchantId(paymentId, merchantId).orElseThrow(() -> ApiException.notFound("Payment"));
    }

    @Transactional(readOnly = true)
    public Payment getInternal(UUID paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
    }

    // --- internals ---

    private void doCapture(Payment p, String memo) {
        PaymentState from = p.getStatus();
        from.assertCanTransitionTo(PaymentState.CAPTURED);
        ledger.postCapture(p.getId(), p.getMerchantId(), p.getAmountMinor(), p.getCurrency());
        transition(p, PaymentState.CAPTURED, memo);
        p.setCapturedAt(Instant.now());
        metrics.paymentOutcome("captured");
        orderService.markPaid(p.getOrderId());
        CustomerPaymentStats stats = customerStats.findById(p.getCustomerId())
                .orElseGet(() -> new CustomerPaymentStats(p.getCustomerId()));
        stats.addCapture(p.getAmountMinor());
        customerStats.save(stats);
        audit.record("SYSTEM", "payment-service", "PAYMENT_CAPTURED", "PAYMENT", p.getId().toString(),
                Map.of("status", from.name()), Map.of("status", "CAPTURED", "amountMinor", p.getAmountMinor()));
    }

    private void fail(Payment p, String reason) {
        PaymentState from = p.getStatus();
        transition(p, PaymentState.FAILED, "failed: " + reason);
        p.setFailedReason(reason);
        metrics.paymentOutcome("failed");
        featureStore.recordFailedAttempt(p.getCustomerId(), p.getId());
        outbox.publish("PAYMENT", p.getId(), p.getMerchantId(), "payment.failed", eventPayload(p, reason));
        audit.record("SYSTEM", "payment-service", "PAYMENT_FAILED", "PAYMENT", p.getId().toString(),
                Map.of("status", from.name()), Map.of("status", "FAILED", "reason", reason));
    }

    private void transition(Payment p, PaymentState target, String detail) {
        PaymentState from = p.getStatus();
        from.assertCanTransitionTo(target);
        p.setStatus(target);
        payments.save(p);
        recordAttempt(p, from, target, detail);
    }

    private void recordAttempt(Payment p, PaymentState from, PaymentState to, String detail) {
        attempts.save(new PaymentAttempt(p.getId(), from == null ? null : from.name(), to.name(), detail));
    }

    private Payment lockOwned(UUID merchantId, UUID paymentId) {
        Payment p = payments.findByIdForUpdate(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        if (!p.getMerchantId().equals(merchantId)) {
            throw ApiException.notFound("Payment");
        }
        return p;
    }

    private void seenDevice(Payment p) {
        if (p.getDeviceFingerprint() == null || p.getDeviceFingerprint().isBlank()) return;
        devices.findByCustomerIdAndFingerprint(p.getCustomerId(), p.getDeviceFingerprint())
                .ifPresentOrElse(
                        d -> d.setLastSeenAt(Instant.now()),
                        () -> {
                            Device d = new Device();
                            d.setCustomerId(p.getCustomerId());
                            d.setFingerprint(p.getDeviceFingerprint());
                            d.setLastSeenAt(Instant.now());
                            devices.save(d);
                        });
    }

    /**
     * Plant the hidden label: authored by the simulation harness through the command, or
     * absent for real traffic, which is then recorded as LIVE and assumed legitimate.
     *
     * <p>This used to be {@code "tok_fraud".equals(token)} with a hardcoded fraud type of
     * "velocity_abuse". The token was an option on the public checkout page, so a tester could
     * attach a fraud label to a clean payment and manufacture a "false" auto-clear; and every
     * confirmed fraud case reached the pgvector store and the analyst's explanation labelled as
     * velocity abuse, whatever the pattern really was.
     */
    private void plantGroundTruth(Payment p, GroundTruthLabel label) {
        groundTruth.plant(p.getId(), label);
    }

    private Map<String, Object> eventPayload(Payment p, String note) {
        return Map.of(
                "paymentId", p.getId().toString(),
                "orderId", p.getOrderId().toString(),
                "merchantId", p.getMerchantId().toString(),
                "status", p.getStatus().name(),
                "amountMinor", p.getAmountMinor(),
                "currency", p.getCurrency(),
                "riskBucket", p.getRiskBucket() == null ? "UNKNOWN" : p.getRiskBucket(),
                "note", note);
    }

    private Map<String, Object> riskSnapshot(Payment p, RiskGate.RiskResult risk) {
        return Map.of(
                "amountMinor", p.getAmountMinor(),
                "method", p.getMethod(),
                "deviceFingerprint", p.getDeviceFingerprint() == null ? "" : p.getDeviceFingerprint(),
                "finalScore", risk.finalScore(),
                "bucket", risk.bucket().name(),
                "decision", risk.decision().name());
    }
}
