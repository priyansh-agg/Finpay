package com.finpay.gateway.support;

import com.finpay.gateway.identity.Merchant;
import com.finpay.gateway.identity.MerchantRepository;
import com.finpay.gateway.order.Order;
import com.finpay.gateway.order.OrderService;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.payment.PaymentService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

@Component
public class TestFixtures {

    private final MerchantRepository merchants;
    private final OrderService orders;
    private final PaymentService payments;
    private final TransactionTemplate tx;

    public TestFixtures(MerchantRepository merchants, OrderService orders, PaymentService payments,
                        TransactionTemplate tx) {
        this.merchants = merchants;
        this.orders = orders;
        this.payments = payments;
        this.tx = tx;
    }

    public UUID newMerchant() {
        return merchants.save(new Merchant("Test " + UUID.randomUUID(), UUID.randomUUID() + "@test.local")).getId();
    }

    public Order newOrder(UUID merchantId, long amountMinor) {
        return orders.create(merchantId, "cust-" + UUID.randomUUID(), "c@test.local",
                amountMinor, "INR", "rcpt", Map.of());
    }

    /** createPayment requires an ambient transaction (propagation MANDATORY); wrap it. */
    public Payment pay(UUID merchantId, UUID orderId, String method, String token) {
        return tx.execute(s -> payments.createPayment(
                new PaymentService.CreatePaymentCommand(merchantId, orderId, method, token, null, null)));
    }

    public Payment newCapturedPayment(UUID merchantId, long amountMinor) {
        Order o = newOrder(merchantId, amountMinor);
        return pay(merchantId, o.getId(), "CARD", "tok_valid");
    }
}
