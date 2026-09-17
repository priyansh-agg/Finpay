package com.finpay.gateway.payment;

import com.finpay.gateway.auth.CurrentCaller;
import com.finpay.gateway.auth.FinPayPrincipal;
import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.idempotency.IdempotencyService;
import com.finpay.gateway.idempotency.IdempotencyService.ResponsePair;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * {@code /v1/payments} — payment creation/capture stay merchant-only (method-level
 * {@code @PreAuthorize} overrides the class-level one below), but reading a payment and its
 * facets (interview sprint Day 12's Payment Detail view) is open to a RISK_ANALYST/SUPER_ADMIN
 * too: the audit found {@code GET /v1/payments/{id}} 403ing an analyst who needs to look up any
 * payment while investigating fraud, not just their own merchant's. Scoping now happens inside
 * {@link #resolveViewable}, the same pattern {@code RiskController} already used — 404, not
 * 403, for a merchant who doesn't own the payment, so existence isn't leaked either way.
 */
@RestController
@RequestMapping("/v1/payments")
@PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_ADMIN','RISK_ANALYST','SUPER_ADMIN')")
public class PaymentController {

    private final PaymentService payments;
    private final PaymentDetailService details;
    private final IdempotencyService idempotency;

    public PaymentController(PaymentService payments, PaymentDetailService details, IdempotencyService idempotency) {
        this.payments = payments;
        this.details = details;
        this.idempotency = idempotency;
    }

    public record CreatePaymentRequest(
            @NotNull UUID orderId,
            @NotBlank @Pattern(regexp = "CARD|UPI|card|upi") String method,
            @NotBlank String token,
            String deviceFingerprint,
            String ipAddress) {}

    @PostMapping
    @PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_ADMIN')")
    public ResponseEntity<PaymentView> create(@RequestBody @Valid CreatePaymentRequest req,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                                              HttpServletRequest http) {
        UUID merchantId = CurrentCaller.requireMerchantId();
        String ip = req.ipAddress() != null ? req.ipAddress() : http.getRemoteAddr();

        var outcome = idempotency.run(idemKey, merchantId, "POST /v1/payments", req, PaymentView.class, () -> {
            Payment p = payments.createPayment(new PaymentService.CreatePaymentCommand(
                    merchantId, req.orderId(), req.method(), req.token(), req.deviceFingerprint(), ip));
            int status = switch (p.getStatus()) {
                case FAILED, VOIDED -> 200;
                case PENDING_REVIEW, HELD -> 202;
                default -> 201;
            };
            return new ResponsePair<>(PaymentView.of(p), status);
        });

        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }

    @GetMapping("/{id}")
    public PaymentView get(@PathVariable UUID id) {
        return PaymentView.of(resolveViewable(id));
    }

    @PostMapping("/{id}/capture")
    @PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_ADMIN')")
    public PaymentView capture(@PathVariable UUID id) {
        return PaymentView.of(payments.capture(CurrentCaller.requireMerchantId(), id));
    }

    // --- Payment Detail facets (interview sprint Day 12) ---------------------------------

    @GetMapping("/{id}/timeline")
    public List<PaymentDetailService.TimelineEntry> timeline(@PathVariable UUID id) {
        resolveViewable(id);
        return details.timeline(id);
    }

    @GetMapping("/{id}/ledger")
    public List<PaymentDetailService.LedgerTxView> ledger(@PathVariable UUID id) {
        return details.ledgerImpact(resolveViewable(id));
    }

    @GetMapping("/{id}/webhooks")
    public List<PaymentDetailService.OutboxEventView> webhooks(@PathVariable UUID id) {
        resolveViewable(id);
        return details.webhookActivity(id);
    }

    @GetMapping("/{id}/audit")
    public List<PaymentDetailService.AuditEntryView> audit(@PathVariable UUID id) {
        resolveViewable(id);
        return details.auditTrail(id);
    }

    @GetMapping("/{id}/autoclear")
    public List<PaymentDetailService.AutoClearDecisionView> autoClear(@PathVariable UUID id) {
        resolveViewable(id);
        return details.autoClearStatus(id);
    }

    @GetMapping("/{id}/customer")
    public PaymentDetailService.CustomerView customer(@PathVariable UUID id) {
        Payment p = resolveViewable(id);
        PaymentDetailService.CustomerView view = details.customer(p.getCustomerId());
        if (view == null) throw ApiException.notFound("Customer");
        return view;
    }

    /**
     * Any role that can reach this controller may fetch a payment, but a merchant only their
     * own — 404, not 403, so a merchant probing another merchant's id learns nothing.
     */
    private Payment resolveViewable(UUID id) {
        FinPayPrincipal caller = CurrentCaller.get();
        boolean analyst = caller.role().equals("RISK_ANALYST") || caller.role().equals("SUPER_ADMIN");
        Payment p = payments.getInternal(id);
        if (!analyst && !p.getMerchantId().equals(caller.merchantId())) {
            throw ApiException.notFound("Payment");
        }
        return p;
    }
}
