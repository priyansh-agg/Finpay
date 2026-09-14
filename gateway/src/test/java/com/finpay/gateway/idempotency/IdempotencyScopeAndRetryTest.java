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
import org.springframework.data.domain.PageRequest;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two properties of the idempotency store that the original implementation got wrong.
 *
 * <p><b>Scope.</b> Key strings are chosen by the caller, so they are only unique within one
 * merchant. V1 made {@code idem_key} globally unique, which let one merchant's "order-42"
 * collide with another's — leaking the first merchant's cached response or rejecting a key
 * the second merchant had never used.
 *
 * <p><b>Retry after failure.</b> When the work throws, its transaction rolls back and
 * nothing took effect, so the same key must be runnable again. Refusing it ("use a new
 * key") fails the client in exactly the case the key exists for: an ambiguous outcome where
 * they cannot know whether the original applied.
 */
class IdempotencyScopeAndRetryTest extends AbstractIntegrationTest {

    @Autowired IdempotencyService idempotency;
    @Autowired IdempotencyKeyRepository keys;
    @Autowired PaymentService payments;
    @Autowired PaymentRepository paymentRepo;
    @Autowired TestFixtures fixtures;

    @Test
    void sameKeyStringFromTwoMerchants_createsTwoIndependentPayments() {
        UUID merchantA = fixtures.newMerchant();
        UUID merchantB = fixtures.newMerchant();
        Order orderA = fixtures.newOrder(merchantA, 50_000);
        Order orderB = fixtures.newOrder(merchantB, 70_000);

        // The collision that matters in practice: both merchants number their own orders.
        String sharedKey = "order-42";

        PaymentView a = pay(merchantA, orderA, sharedKey).body();
        PaymentView b = pay(merchantB, orderB, sharedKey).body();

        assertThat(a.id()).as("each merchant gets its own payment").isNotEqualTo(b.id());
        assertThat(a.amountMinor()).isEqualTo(50_000);
        assertThat(b.amountMinor()).isEqualTo(70_000);

        assertThat(countPayments(merchantA)).isEqualTo(1);
        assertThat(countPayments(merchantB)).isEqualTo(1);

        // Two rows, same key string, different merchants — the composite index allows it.
        assertThat(keys.findByMerchantIdAndIdemKey(merchantA, sharedKey)).isPresent();
        assertThat(keys.findByMerchantIdAndIdemKey(merchantB, sharedKey)).isPresent();
    }

    @Test
    void replayIsStillScopedToTheMerchant() {
        UUID merchantA = fixtures.newMerchant();
        UUID merchantB = fixtures.newMerchant();
        Order orderA = fixtures.newOrder(merchantA, 50_000);
        Order orderB = fixtures.newOrder(merchantB, 70_000);
        String sharedKey = "shared-" + UUID.randomUUID();

        UUID first = pay(merchantA, orderA, sharedKey).body().id();
        pay(merchantB, orderB, sharedKey);

        // Merchant A replaying its own key must get ITS payment back, not merchant B's.
        var replay = pay(merchantA, orderA, sharedKey);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body().id()).isEqualTo(first);
    }

    @Test
    void failedAttempt_rollsEverythingBack_andTheSameKeyCanBeRetried() {
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 50_000);
        String key = "retry-" + UUID.randomUUID();
        var attempts = new AtomicInteger();

        // First attempt: create the payment, then blow up. Both must be undone.
        assertThatThrownBy(() -> idempotency.run(key, merchantId, "POST /v1/payments", body(order),
                PaymentView.class, () -> {
                    attempts.incrementAndGet();
                    payments.createPayment(command(merchantId, order));
                    throw new IllegalStateException("processor exploded");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("processor exploded");

        assertThat(countPayments(merchantId))
                .as("the payment rolled back with the failed transaction")
                .isZero();
        assertThat(keys.findByMerchantIdAndIdemKey(merchantId, key))
                .as("the failure is recorded durably, from a transaction of its own")
                .get()
                .extracting(IdempotencyKey::getStatus)
                .isEqualTo(IdempotencyKey.Status.FAILED);

        // Second attempt, SAME key: nothing took effect last time, so it must run again.
        PaymentView recovered = idempotency.run(key, merchantId, "POST /v1/payments", body(order),
                PaymentView.class, () -> {
                    attempts.incrementAndGet();
                    return new ResponsePair<>(PaymentView.of(payments.createPayment(command(merchantId, order))), 201);
                }).body();

        assertThat(attempts.get()).as("the work really did run twice").isEqualTo(2);
        assertThat(recovered.id()).isNotNull();
        assertThat(countPayments(merchantId)).isEqualTo(1);
        assertThat(keys.findByMerchantIdAndIdemKey(merchantId, key))
                .get().extracting(IdempotencyKey::getStatus)
                .isEqualTo(IdempotencyKey.Status.COMPLETED);

        // And a third call replays the cached response rather than paying a third time.
        var replay = idempotency.run(key, merchantId, "POST /v1/payments", body(order),
                PaymentView.class, () -> { throw new AssertionError("must not run again"); });
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body().id()).isEqualTo(recovered.id());
        assertThat(countPayments(merchantId)).isEqualTo(1);
    }

    @Test
    void sameKeyOnADifferentEndpoint_isRejectedWithAClearReason() {
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 50_000);
        String key = "cross-endpoint-" + UUID.randomUUID();

        pay(merchantId, order, key);

        assertThatThrownBy(() -> idempotency.run(key, merchantId, "POST /v1/checkout/pay", body(order),
                PaymentView.class, () -> { throw new AssertionError("must not run"); }))
                .hasMessageContaining("already used on POST /v1/payments");
    }

    @Test
    void aRejectedRequest_doesNotDowngradeAnAlreadyCompletedKey() {
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 50_000);
        String key = "guard-" + UUID.randomUUID();

        UUID original = pay(merchantId, order, key).body().id();

        // Same key, different body — a rejection of THIS request, not a failure of the work.
        assertThatThrownBy(() -> idempotency.run(key, merchantId, "POST /v1/payments",
                java.util.Map.of("orderId", order.getId().toString(), "method", "UPI", "token", "tok_valid"),
                PaymentView.class, () -> { throw new AssertionError("must not run"); }))
                .hasMessageContaining("different request body");

        assertThat(keys.findByMerchantIdAndIdemKey(merchantId, key))
                .as("the completed row survives a bad retry — its cached response is still owed to the caller")
                .get().extracting(IdempotencyKey::getStatus)
                .isEqualTo(IdempotencyKey.Status.COMPLETED);

        // ...and the correct body still replays the original response.
        var replay = pay(merchantId, order, key);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body().id()).isEqualTo(original);
    }

    @Test
    void anExpiredKey_isFreeAgain_andTheNextRequestUnderItRunsAsNew() {
        UUID merchantId = fixtures.newMerchant();
        Order first = fixtures.newOrder(merchantId, 50_000);
        Order second = fixtures.newOrder(merchantId, 60_000);
        String key = "ttl-" + UUID.randomUUID();

        UUID original = pay(merchantId, first, key).body().id();
        jdbc.update("UPDATE idempotency_keys SET expires_at = now() - interval '1 minute' "
                + "WHERE merchant_id = ? AND idem_key = ?", merchantId, key);

        // Different body under the same key: inside the TTL that is a 422; past it, a new request.
        var fresh = pay(merchantId, second, key);

        assertThat(fresh.replayed()).isFalse();
        assertThat(fresh.body().id()).isNotEqualTo(original);
        assertThat(fresh.body().amountMinor()).isEqualTo(60_000);
        assertThat(keys.findByMerchantIdAndIdemKey(merchantId, key).orElseThrow().getExpiresAt())
                .as("the claim was renewed with a fresh TTL")
                .isAfter(java.time.Instant.now());
    }

    @Test
    void manyCallersRetryingAFailedKeyAtOnce_runTheWorkOnce_andAllGetItsResponse() throws Exception {
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 50_000);
        String key = "retry-race-" + UUID.randomUUID();

        assertThatThrownBy(() -> idempotency.run(key, merchantId, "POST /v1/payments", body(order),
                PaymentView.class, () -> { throw new IllegalStateException("first attempt fails"); }));

        int n = 10;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        var gate = new java.util.concurrent.CountDownLatch(1);
        var ran = new AtomicInteger();
        java.util.Set<UUID> ids = java.util.concurrent.ConcurrentHashMap.newKeySet();

        var futures = java.util.stream.IntStream.range(0, n).mapToObj(i -> pool.submit(() -> {
            gate.await();
            ids.add(idempotency.run(key, merchantId, "POST /v1/payments", body(order), PaymentView.class, () -> {
                ran.incrementAndGet();
                return new ResponsePair<>(PaymentView.of(payments.createPayment(command(merchantId, order))), 201);
            }).body().id());
            return null;
        })).toList();

        gate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        for (var f : futures) f.get();   // no caller may error, including the losers of the takeover

        assertThat(ran.get()).as("exactly one caller re-ran the work").isEqualTo(1);
        assertThat(ids).as("the others replayed its response").hasSize(1);
        assertThat(countPayments(merchantId)).isEqualTo(1);
    }

    // --- helpers ---

    private IdempotencyService.Outcome<PaymentView> pay(UUID merchantId, Order order, String key) {
        return idempotency.run(key, merchantId, "POST /v1/payments", body(order), PaymentView.class,
                () -> new ResponsePair<>(PaymentView.of(payments.createPayment(command(merchantId, order))), 201));
    }

    private PaymentService.CreatePaymentCommand command(UUID merchantId, Order order) {
        return new PaymentService.CreatePaymentCommand(merchantId, order.getId(), "CARD", "tok_valid", "dev-1", "1.1.1.1");
    }

    /** The request body the hash is taken over — distinct per merchant/order, as in the API. */
    private Object body(Order order) {
        return java.util.Map.of("orderId", order.getId().toString(), "method", "CARD", "token", "tok_valid");
    }

    private long countPayments(UUID merchantId) {
        return paymentRepo.findByMerchantIdOrderByCreatedAtDesc(merchantId, PageRequest.of(0, 100)).getTotalElements();
    }
}
