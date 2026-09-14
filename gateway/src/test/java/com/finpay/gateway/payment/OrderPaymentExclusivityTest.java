package com.finpay.gateway.payment;

import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.idempotency.IdempotencyService;
import com.finpay.gateway.idempotency.IdempotencyService.ResponsePair;
import com.finpay.gateway.order.Order;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One order, one charge.
 *
 * <p>Idempotency only dedupes requests that share a key. Two requests for the same order with
 * DIFFERENT keys — a double-clicked pay button, two open checkout tabs, a client that mints a
 * fresh key per retry — used to both pass the "is this order payable?" check before either
 * inserted, because nothing locked the order. This suite pins the fix: the order row is locked
 * for the duration of payment creation, a second payment is refused while the first is live,
 * and a partial unique index refuses it again at the database.
 */
class OrderPaymentExclusivityTest extends AbstractIntegrationTest {

    @Autowired IdempotencyService idempotency;
    @Autowired PaymentService payments;
    @Autowired TestFixtures fixtures;

    @Test
    void concurrentPaymentsOnOneOrder_withDifferentKeys_chargeItExactlyOnce() throws Exception {
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 50_000);

        int n = 10;
        var pool = Executors.newFixedThreadPool(n);
        var gate = new CountDownLatch(1);
        var charged = new AtomicInteger();
        var refused = new AtomicInteger();

        var futures = IntStream.range(0, n).mapToObj(i -> pool.submit(() -> {
            gate.await();
            try {
                // A distinct key per request: idempotency is no help here, by design. No device
                // or IP, so the winner scores 0 under the real rules and auto-captures — this
                // test is about the keys, and a clean capture makes "charged once" checkable
                // in the ledger.
                idempotency.run("tab-" + i + "-" + UUID.randomUUID(), merchantId, "POST /v1/payments",
                        java.util.Map.of("orderId", order.getId().toString(), "attempt", i), PaymentView.class,
                        () -> new ResponsePair<>(PaymentView.of(payments.createPayment(
                                new PaymentService.CreatePaymentCommand(merchantId, order.getId(), "CARD",
                                        "tok_valid", null, null))), 201));
                charged.incrementAndGet();
            } catch (ApiException e) {
                refused.incrementAndGet();
            }
            return null;
        })).toList();

        gate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        for (var f : futures) f.get();

        assertThat(charged.get()).as("exactly one request charges the order").isEqualTo(1);
        assertThat(refused.get()).isEqualTo(n - 1);
        assertThat(count("SELECT count(*) FROM payments WHERE order_id = ?", order.getId())).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM ledger_transactions t JOIN payments p ON p.id = t.reference_id
                WHERE t.tx_type = 'CAPTURE' AND p.order_id = ?""", order.getId()))
                .as("one capture in the ledger, not ten").isEqualTo(1);
    }

    @Test
    void secondPayment_whileTheFirstIsHeldForReview_isRefused() {
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 50_000);
        // The case a status check alone misses: a HELD payment leaves the order CREATED,
        // because the order only turns PAID on capture.
        UUID held = insertPayment(order, "HELD");

        assertThatThrownBy(() -> fixtures.pay(merchantId, order.getId(), "CARD", "tok_valid"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already has a payment in progress")
                .hasMessageContaining(held.toString());

        assertThat(count("SELECT count(*) FROM payments WHERE order_id = ?", order.getId())).isEqualTo(1);
    }

    @Test
    void aDeclinedAttempt_doesNotBlockTheCustomerFromTryingAnotherCard() {
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 50_000);

        Payment declined = fixtures.pay(merchantId, order.getId(), "CARD", "tok_declined");
        assertThat(declined.getStatus()).isEqualTo(PaymentState.FAILED);

        Payment retried = fixtures.pay(merchantId, order.getId(), "CARD", "tok_valid");
        assertThat(retried.getStatus()).isEqualTo(PaymentState.CAPTURED);
        assertThat(count("SELECT count(*) FROM payments WHERE order_id = ?", order.getId())).isEqualTo(2);
    }

    @Test
    void theDatabaseRefusesASecondLivePayment_evenForCodeThatSkipsTheService() {
        UUID merchantId = fixtures.newMerchant();
        Order order = fixtures.newOrder(merchantId, 50_000);
        insertPayment(order, "AUTHORIZED");

        assertThatThrownBy(() -> insertPayment(order, "PENDING_REVIEW"))
                .as("partial unique index ux_payments_one_live_per_order")
                .isInstanceOf(DataIntegrityViolationException.class);

        // Dead attempts sit outside the index, so any number of them can coexist.
        insertPayment(order, "FAILED");
        insertPayment(order, "VOIDED");
        assertThat(count("SELECT count(*) FROM payments WHERE order_id = ?", order.getId())).isEqualTo(3);
    }

    private UUID insertPayment(Order order, String status) {
        return jdbc.queryForObject("""
                INSERT INTO payments (order_id, merchant_id, customer_id, amount_minor, currency,
                                      method, payment_token, status)
                VALUES (?, ?, ?, ?, ?, 'CARD', 'tok_valid', ?) RETURNING id""",
                UUID.class, order.getId(), order.getMerchantId(), order.getCustomerId(),
                order.getAmountMinor(), order.getCurrency(), status);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
