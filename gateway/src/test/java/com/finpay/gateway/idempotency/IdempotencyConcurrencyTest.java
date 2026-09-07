package com.finpay.gateway.idempotency;

import com.finpay.gateway.idempotency.IdempotencyService.ResponsePair;
import com.finpay.gateway.order.Order;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.payment.PaymentRepository;
import com.finpay.gateway.payment.PaymentService;
import com.finpay.gateway.payment.PaymentView;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 exit criterion: 20 concurrent requests carrying the SAME Idempotency-Key must
 * create EXACTLY ONE payment. Every caller must observe the same payment id.
 */
class IdempotencyConcurrencyTest extends AbstractIntegrationTest {

    @Autowired IdempotencyService idempotency;
    @Autowired PaymentService payments;
    @Autowired PaymentRepository paymentRepo;
    @Autowired TestFixtures fixtures;

    @Test
    void twentyConcurrentIdenticalRequests_createExactlyOnePayment() throws Exception {
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 50_000);
        String key = "race-" + UUID.randomUUID();
        var body = new PaymentService.CreatePaymentCommand(merchantId, order.getId(), "CARD", "tok_valid", "dev-1", "1.1.1.1");

        int n = 20;
        var pool = Executors.newFixedThreadPool(n);
        var startGate = new CountDownLatch(1);

        List<Future<UUID>> futures = IntStream.range(0, n).mapToObj(i -> pool.submit(() -> {
            startGate.await();
            var outcome = idempotency.run(key, merchantId, "POST /v1/payments", body, PaymentView.class, () -> {
                Payment p = payments.createPayment(body);
                return new ResponsePair<>(PaymentView.of(p), 201);
            });
            return outcome.body().id();
        })).toList();

        startGate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<UUID> ids = futures.stream().map(f -> {
            try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); }
        }).toList();

        assertThat(ids).hasSize(n);
        assertThat(ids).allMatch(id -> id.equals(ids.get(0)));
        assertThat(paymentRepo.findByMerchantIdOrderByCreatedAtDesc(merchantId,
                org.springframework.data.domain.PageRequest.of(0, 100)).getTotalElements())
                .isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBody_isRejected() {
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 50_000);
        String key = "reuse-" + UUID.randomUUID();
        var first = new PaymentService.CreatePaymentCommand(merchantId, order.getId(), "CARD", "tok_valid", null, null);

        idempotency.run(key, merchantId, "POST /v1/payments", first, PaymentView.class,
                () -> new ResponsePair<>(PaymentView.of(payments.createPayment(first)), 201));

        var different = new PaymentService.CreatePaymentCommand(merchantId, order.getId(), "UPI", "tok_valid", null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                idempotency.run(key, merchantId, "POST /v1/payments", different, PaymentView.class,
                        () -> new ResponsePair<>(PaymentView.of(payments.createPayment(different)), 201)))
                .hasMessageContaining("different request body");
    }
}
