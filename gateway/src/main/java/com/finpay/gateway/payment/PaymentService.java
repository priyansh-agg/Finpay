package com.finpay.gateway.payment;

import com.finpay.gateway.analyst.api.ReviewQueuePort;
import com.finpay.gateway.common.audit.AuditService;
import com.finpay.gateway.common.error.ApiException;
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

    public record CreatePaymentCommand(UUID merchantId, UUID orderId, String method, String token,
                                       String deviceFingerprint, String ipAddress) {}

    /**
     * The full create-payment flow. Runs inside the IdempotencyService transaction, so
     * either the whole thing commits (payment + attempts + ledger + outbox + ground truth)
     * or none of it does.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment createPayment(CreatePaymentCommand cmd) {
        Order order = orders.findByIdAndMerchantId(cmd.orderId(), cmd.merchantId())
                .orElseThrow(() -> ApiException.notFound("Order"));
        if (!"CREATED".equals(order.getStatus())) {
            throw ApiException.conflict("Order is not payable (status=" + order.getStatus() + ")");
        }

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
        payments.save(p);
        recordAttempt(p, null, PaymentState.CREATED, "payment created");

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
        plantGroundTruth(p);

        if (risk.decision() == Decision.BLOCK) {
            fail(p, "risk_blocked");
            return p;
        }
        if (risk.decision() == Decision.REVIEW) {
            transition(p, PaymentState.PENDING_REVIEW, "risk decision REVIEW");
            reviewQueue.enqueue(p.getId(), "risk.decision=REVIEW", risk.finalScore(), riskSnapshot(p, risk));
            outbox.publish("PAYMENT", p.getId(), "risk.flagged", eventPayload(p, "REVIEW"));
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
            outbox.publish("PAYMENT", p.getId(), "payment.captured", eventPayload(p, "AUTO"));
        } else if (risk.bucket() == RiskGate.Bucket.LOW || risk.bucket() == RiskGate.Bucket.MEDIUM) {
            transition(p, PaymentState.HELD, risk.bucket() + " risk — funds held pending review");
            reviewQueue.enqueue(p.getId(), "held:" + risk.bucket(), risk.finalScore(), riskSnapshot(p, risk));
            outbox.publish("PAYMENT", p.getId(), "payment.held", eventPayload(p, "HELD"));
            metrics.paymentOutcome("held");
        } else {
            // HIGH / CRITICAL that got authorised -> void the authorisation immediately
            transition(p, PaymentState.VOIDED, risk.bucket() + " risk — authorization voided");
            outbox.publish("PAYMENT", p.getId(), "payment.voided", eventPayload(p, "VOID"));
            metrics.paymentOutcome("voided");
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
        outbox.publish("PAYMENT", p.getId(), "payment.captured", eventPayload(p, "MANUAL"));
        return p;
    }

    /** Used by the analyst module to complete a HELD/PENDING_REVIEW payment. */
    @Transactional
    public Payment resolveReview(UUID paymentId, boolean clear, String actor, String reason) {
        Payment p = payments.findByIdForUpdate(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        if (p.getStatus() != PaymentState.HELD && p.getStatus() != PaymentState.PENDING_REVIEW) {
            throw ApiException.conflict("Payment is not under review (status=" + p.getStatus() + ")");
        }
        if (clear) {
            if (p.getStatus() == PaymentState.PENDING_REVIEW) {
                // resume the processor path
                transition(p, PaymentState.PROCESSING, "review cleared by " + actor);
                PaymentSimulator.Result r = simulator.process(p.getMethod(), p.getPaymentToken(), p.getAmountMinor(), p.getCurrency());
                if (r.outcome() == PaymentSimulator.Outcome.DECLINED) {
                    fail(p, r.declineReason());
                    return p;
                }
                p.setProcessorRef(r.processorRef());
                transition(p, PaymentState.AUTHORIZED, "authorized after review");
                p.setAuthorizedAt(Instant.now());
            }
            doCapture(p, "review cleared: " + reason);
            outbox.publish("PAYMENT", p.getId(), "payment.captured", eventPayload(p, "REVIEW_CLEAR"));
        } else {
            PaymentState from = p.getStatus();
            transition(p, from == PaymentState.HELD ? PaymentState.VOIDED : PaymentState.FAILED, "review rejected: " + reason);
            p.setFailedReason("review_rejected");
            outbox.publish("PAYMENT", p.getId(), "payment.rejected", eventPayload(p, "REVIEW_REJECT"));
        }
        return p;
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
        transition(p, PaymentState.FAILED, "failed: " + reason);
        p.setFailedReason(reason);
        metrics.paymentOutcome("failed");
        featureStore.recordFailedAttempt(p.getCustomerId(), p.getId());
        outbox.publish("PAYMENT", p.getId(), "payment.failed", eventPayload(p, reason));
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

    private void plantGroundTruth(Payment p) {
        boolean injected = "tok_fraud".equals(p.getPaymentToken());
        groundTruth.plant(p.getId(), injected, injected ? "velocity_abuse" : null);
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
