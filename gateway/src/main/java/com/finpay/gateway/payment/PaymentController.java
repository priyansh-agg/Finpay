package com.finpay.gateway.payment;

import com.finpay.gateway.auth.CurrentCaller;
import com.finpay.gateway.idempotency.IdempotencyService;
import com.finpay.gateway.idempotency.IdempotencyService.ResponsePair;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/payments")
@PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_ADMIN')")
public class PaymentController {

    private final PaymentService payments;
    private final IdempotencyService idempotency;

    public PaymentController(PaymentService payments, IdempotencyService idempotency) {
        this.payments = payments;
        this.idempotency = idempotency;
    }

    public record CreatePaymentRequest(
            @NotNull UUID orderId,
            @NotBlank @Pattern(regexp = "CARD|UPI|card|upi") String method,
            @NotBlank String token,
            String deviceFingerprint,
            String ipAddress) {}

    @PostMapping
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
        return PaymentView.of(payments.get(CurrentCaller.requireMerchantId(), id));
    }

    @PostMapping("/{id}/capture")
    public PaymentView capture(@PathVariable UUID id) {
        return PaymentView.of(payments.capture(CurrentCaller.requireMerchantId(), id));
    }
}
