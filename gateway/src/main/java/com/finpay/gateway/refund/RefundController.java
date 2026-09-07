package com.finpay.gateway.refund;

import com.finpay.gateway.auth.CurrentCaller;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/payments/{paymentId}/refund")
@PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_ADMIN')")
public class RefundController {

    private final RefundService service;

    public RefundController(RefundService service) {
        this.service = service;
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
        return service.forPayment(CurrentCaller.requireMerchantId(), paymentId).stream()
                .map(RefundResponse::of).toList();
    }
}
