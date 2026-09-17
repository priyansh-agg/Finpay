package com.finpay.gateway.refund;

import com.finpay.gateway.auth.CurrentCaller;
import com.finpay.gateway.auth.FinPayPrincipal;
import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.payment.PaymentRepository;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Refunding stays merchant-only, but reading a payment's refunds is open to a
 * RISK_ANALYST/SUPER_ADMIN too (interview sprint Day 12 — the Payment Detail view's refunds
 * facet), matching the same widening {@code PaymentController} got.
 */
@RestController
@RequestMapping("/v1/payments/{paymentId}/refund")
@PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_ADMIN','RISK_ANALYST','SUPER_ADMIN')")
public class RefundController {

    private final RefundService service;
    private final PaymentRepository payments;

    public RefundController(RefundService service, PaymentRepository payments) {
        this.service = service;
        this.payments = payments;
    }

    /** null amountMinor => full remaining refund. */
    public record RefundRequest(@Positive Long amountMinor, String reason) {}

    public record RefundResponse(UUID id, UUID paymentId, long amountMinor, String currency,
                                 String status, String reason, Instant createdAt) {
        static RefundResponse of(Refund r) {
            return new RefundResponse(r.getId(), r.getPaymentId(), r.getAmountMinor(), r.getCurrency(),
                    r.getStatus().name(), r.getReason(), r.getCreatedAt());
        }
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public RefundResponse refund(@PathVariable UUID paymentId,
                                 @RequestBody(required = false) RefundRequest req,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        UUID merchantId = CurrentCaller.requireMerchantId();
        Long amount = req == null ? null : req.amountMinor();
        String reason = req == null ? null : req.reason();
        return RefundResponse.of(service.refund(merchantId, paymentId, amount, reason, idemKey));
    }

    @GetMapping
    public List<RefundResponse> list(@PathVariable UUID paymentId) {
        FinPayPrincipal caller = CurrentCaller.get();
        boolean analyst = caller.role().equals("RISK_ANALYST") || caller.role().equals("SUPER_ADMIN");
        List<Refund> result;
        if (analyst) {
            if (!payments.existsById(paymentId)) throw ApiException.notFound("Payment");
            result = service.forPaymentUnscoped(paymentId);
        } else {
            result = service.forPayment(CurrentCaller.requireMerchantId(), paymentId);
        }
        return result.stream().map(RefundResponse::of).toList();
    }
}
