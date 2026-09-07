package com.finpay.gateway.checkout;

import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.common.ratelimit.RateLimiter;
import com.finpay.gateway.idempotency.IdempotencyService;
import com.finpay.gateway.idempotency.IdempotencyService.ResponsePair;
import com.finpay.gateway.identity.Merchant;
import com.finpay.gateway.identity.MerchantRepository;
import com.finpay.gateway.order.Order;
import com.finpay.gateway.order.OrderRepository;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.payment.PaymentService;
import com.finpay.gateway.payment.PaymentView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The customer-facing "payment link" surface (Razorpay/Stripe Checkout style): the merchant
 * creates an order server-side (authenticated, {@code POST /v1/orders}) and shares a link
 * built from its id — {@code https://.../pay/{orderId}}. Whoever opens that link can pay it;
 * NO account or API key is required, exactly like a real hosted checkout page. The order id
 * itself is the capability token, so it is rate-limited by caller IP to blunt brute-forcing.
 */
@RestController
@RequestMapping("/v1/checkout")
public class CheckoutController {

    private final OrderRepository orders;
    private final MerchantRepository merchants;
    private final PaymentService payments;
    private final IdempotencyService idempotency;
    private final RateLimiter rateLimiter;

    public CheckoutController(OrderRepository orders, MerchantRepository merchants, PaymentService payments,
                              IdempotencyService idempotency, RateLimiter rateLimiter) {
        this.orders = orders;
        this.merchants = merchants;
        this.payments = payments;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
    }

    public record CheckoutView(UUID orderId, String merchantName, long amountMinor, String currency,
                               String receipt, String status) {}

    @GetMapping("/{orderId}")
    public CheckoutView get(@PathVariable UUID orderId) {
        Order o = orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        Merchant m = merchants.findById(o.getMerchantId()).orElseThrow(() -> ApiException.notFound("Merchant"));
        return new CheckoutView(o.getId(), m.getName(), o.getAmountMinor(), o.getCurrency(), o.getReceipt(), o.getStatus());
    }

    public record PayRequest(
            @NotBlank @Pattern(regexp = "CARD|UPI|card|upi") String method,
            @NotBlank String token,
            String deviceFingerprint) {}

    @PostMapping("/{orderId}/pay")
    public ResponseEntity<PaymentView> pay(@PathVariable UUID orderId, @RequestBody @Valid PayRequest req,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                                           HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        if (!rateLimiter.tryAcquire("checkout:" + ip).allowed()) {
            throw ApiException.rateLimited("Too many payment attempts from this address; try again shortly");
        }
        Order order = orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        String key = (idemKey == null || idemKey.isBlank()) ? "checkout-auto-" + UUID.randomUUID() : idemKey;

        var outcome = idempotency.run(key, order.getMerchantId(), "POST /v1/checkout/pay", req, PaymentView.class, () -> {
            Payment p = payments.createPayment(new PaymentService.CreatePaymentCommand(
                    order.getMerchantId(), order.getId(), req.method(), req.token(), req.deviceFingerprint(), ip));
            int status = switch (p.getStatus()) {
                case FAILED, VOIDED -> 200;
                case PENDING_REVIEW, HELD -> 202;
                default -> 201;
            };
            return new ResponsePair<>(PaymentView.of(p), status);
        });
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }
}
