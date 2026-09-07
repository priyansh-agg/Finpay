package com.finpay.gateway.order;

import com.finpay.gateway.auth.CurrentCaller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/orders")
@PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_ADMIN')")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    public record CreateOrderRequest(
            @NotBlank String customerRef,
            String customerEmail,
            @Positive long amountMinor,
            String currency,
            String receipt,
            Map<String, Object> notes) {}

    public record OrderResponse(UUID id, UUID customerId, long amountMinor, String currency,
                                String status, String receipt, Instant createdAt) {
        static OrderResponse of(Order o) {
            return new OrderResponse(o.getId(), o.getCustomerId(), o.getAmountMinor(), o.getCurrency(),
                    o.getStatus(), o.getReceipt(), o.getCreatedAt());
        }
    }

    @PostMapping
    public OrderResponse create(@RequestBody @Valid CreateOrderRequest req) {
        UUID merchantId = CurrentCaller.requireMerchantId();
        return OrderResponse.of(service.create(merchantId, req.customerRef(), req.customerEmail(),
                req.amountMinor(), req.currency(), req.receipt(), req.notes()));
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        return OrderResponse.of(service.get(CurrentCaller.requireMerchantId(), id));
    }
}
