package com.finpay.gateway.refund;

import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.ledger.LedgerService;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Refunds carry their own idempotency key, separate from the payment idempotency store, and
 * it had the same flaw as that store did before V10: the key was globally unique and looked
 * up without the merchant. A second merchant reusing a key string got the FIRST merchant's
 * refund back as its 201 response — and its own refund never ran.
 */
class RefundIdempotencyTest extends AbstractIntegrationTest {

    @Autowired RefundService refunds;
    @Autowired LedgerService ledger;
    @Autowired TestFixtures fixtures;

    @Test
    void sameRefundKeyFromTwoMerchants_refundsBothPayments() {
        UUID merchantA = fixtures.newMerchant();
        UUID merchantB = fixtures.newMerchant();
        Payment a = fixtures.newCapturedPayment(merchantA, 30_000);
        Payment b = fixtures.newCapturedPayment(merchantB, 80_000);

        Refund ra = refunds.refund(merchantA, a.getId(), 10_000L, "damaged", "refund-1");
        Refund rb = refunds.refund(merchantB, b.getId(), 20_000L, "late", "refund-1");

        assertThat(rb.getId()).as("merchant B gets its own refund, not merchant A's").isNotEqualTo(ra.getId());
        assertThat(rb.getPaymentId()).isEqualTo(b.getId());
        assertThat(rb.getAmountMinor()).isEqualTo(20_000);
        assertThat(ledger.merchantPayableBalance(merchantA, "INR")).isEqualTo(20_000);
        assertThat(ledger.merchantPayableBalance(merchantB, "INR")).isEqualTo(60_000);
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void replayingTheSameKey_returnsTheOriginalRefund_andMovesNoMoreMoney() {
        UUID merchantId = fixtures.newMerchant();
        Payment p = fixtures.newCapturedPayment(merchantId, 30_000);

        Refund first = refunds.refund(merchantId, p.getId(), 10_000L, "partial", "rf-same");
        Refund again = refunds.refund(merchantId, p.getId(), 10_000L, "partial", "rf-same");
        Refund unspecifiedAmount = refunds.refund(merchantId, p.getId(), null, "partial", "rf-same");

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(unspecifiedAmount.getId()).isEqualTo(first.getId());
        assertThat(ledger.merchantPayableBalance(merchantId, "INR")).isEqualTo(20_000);
    }

    @Test
    void sameKeyWithADifferentAmount_isRefused_notSilentlyAnsweredWithTheOldRefund() {
        UUID merchantId = fixtures.newMerchant();
        Payment p = fixtures.newCapturedPayment(merchantId, 30_000);
        refunds.refund(merchantId, p.getId(), 10_000L, "partial", "rf-amt");

        assertThatThrownBy(() -> refunds.refund(merchantId, p.getId(), 5_000L, "partial", "rf-amt"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("different refund amount");
        assertThat(ledger.merchantPayableBalance(merchantId, "INR")).isEqualTo(20_000);
    }

    @Test
    void sameKeyOnAnotherPayment_isRefused() {
        UUID merchantId = fixtures.newMerchant();
        Payment p1 = fixtures.newCapturedPayment(merchantId, 30_000);
        Payment p2 = fixtures.newCapturedPayment(merchantId, 30_000);
        refunds.refund(merchantId, p1.getId(), 10_000L, "partial", "rf-pay");

        assertThatThrownBy(() -> refunds.refund(merchantId, p2.getId(), 10_000L, "partial", "rf-pay"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("different payment");
    }

    @Test
    void concurrentRequestsWithTheSameKey_produceOneRefund_andEveryCallerGetsIt() throws Exception {
        UUID merchantId = fixtures.newMerchant();
        Payment p = fixtures.newCapturedPayment(merchantId, 100_000);

        int n = 10;
        var pool = Executors.newFixedThreadPool(n);
        var gate = new CountDownLatch(1);
        Set<UUID> returned = ConcurrentHashMap.newKeySet();

        // Before the fix the key was checked BEFORE the payment lock: all ten missed it, then
        // queued on the lock, and nine of them hit the unique index as a 500.
        var futures = IntStream.range(0, n).mapToObj(i -> pool.submit(() -> {
            gate.await();
            returned.add(refunds.refund(merchantId, p.getId(), 25_000L, "dup-click", "rf-race").getId());
            return null;
        })).toList();

        gate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        for (var f : futures) f.get();   // rethrows if any caller failed

        assertThat(returned).as("every caller got the same refund").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refunds WHERE payment_id = ?", Long.class, p.getId()))
                .isEqualTo(1);
        assertThat(ledger.merchantPayableBalance(merchantId, "INR")).isEqualTo(75_000);
    }
}
