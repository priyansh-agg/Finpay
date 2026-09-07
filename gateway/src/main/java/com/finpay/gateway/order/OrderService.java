package com.finpay.gateway.order;

import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.identity.Customer;
import com.finpay.gateway.identity.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final CustomerRepository customers;

    public OrderService(OrderRepository orders, CustomerRepository customers) {
        this.orders = orders;
        this.customers = customers;
    }

    @Transactional
    public Order create(UUID merchantId, String customerExternalRef, String customerEmail,
                        long amountMinor, String currency, String receipt, Map<String, Object> notes) {
        if (amountMinor <= 0) {
            throw ApiException.validation("amountMinor must be positive");
        }
        Customer customer = customers.findByMerchantIdAndExternalRef(merchantId, customerExternalRef)
                .orElseGet(() -> customers.save(new Customer(merchantId, customerExternalRef, customerEmail)));

        Order order = new Order();
        order.setMerchantId(merchantId);
        order.setCustomerId(customer.getId());
        order.setAmountMinor(amountMinor);
        order.setCurrency(currency == null ? "INR" : currency.toUpperCase());
        order.setReceipt(receipt);
        order.setNotes(notes == null ? Map.of() : notes);
        return orders.save(order);
    }

    @Transactional(readOnly = true)
    public Order get(UUID merchantId, UUID orderId) {
        return orders.findByIdAndMerchantId(orderId, merchantId)
                .orElseThrow(() -> ApiException.notFound("Order"));
    }

    @Transactional
    public void markPaid(UUID orderId) {
        orders.findById(orderId).ifPresent(o -> o.setStatus("PAID"));
    }
}
