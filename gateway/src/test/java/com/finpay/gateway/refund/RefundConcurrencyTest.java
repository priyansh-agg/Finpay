package com.finpay.gateway.refund;

import com.finpay.gateway.ledger.LedgerService;
import com.finpay.gateway.order.Order;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.payment.PaymentService;
import com.finpay.gateway.payment.PaymentState;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 exit criterion: concurrent refunds on the same payment must never over-refund.
 * The FOR UPDATE lock on the payment row serialises them; the sum of succeeded refunds
 * can never exceed the captured amount, and the ledger stays balanced.
 */
class RefundConcurrencyTest extends AbstractIntegrationTest {

    @Autowired PaymentService payments;
    @Autowired RefundService refunds;
    @Autowired LedgerService ledger;
    @Autowired TestFixtures fixtures;

    @Test
    void concurrentRefunds_neverExceedCapturedAmount() throws Exception {
        UUID merchantId = fixtures.newMerchant();
        Payment p = fixtures.newCapturedPayment(merchantId, 100_000);
        assertThat(p.getStatus()).isEqualTo(PaymentState.CAPTURED);

        int n = 15;               // each tries to refund 10_000 -> only 10 can succeed
        var pool = Executors.newFixedThreadPool(n);
        var gate = new CountDownLatch(1);
        var ok = new AtomicInteger();
        var rejected = new AtomicInteger();

        var futures = IntStream.range(0, n).mapToObj(i -> pool.submit(() -> {
            gate.await();
            try {
                refunds.refund(merchantId, p.getId(), 10_000L, "concurrent", "rf-" + i);
                ok.incrementAndGet();
            } catch (RuntimeException e) {
                rejected.incrementAndGet();
            }
            return null;
        })).toList();

        gate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        for (var f : futures) f.get();

        long refundedTotal = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor),0) FROM refunds WHERE payment_id = ? AND status = 'SUCCEEDED'",
                Long.class, p.getId());

        assertThat(refundedTotal).isEqualTo(100_000L);
        assertThat(ok.get()).isEqualTo(10);
        assertThat(rejected.get()).isEqualTo(5);

        LedgerService.Reconciliation rec = ledger.reconcile();
        assertThat(rec.balanced()).isTrue();
        assertThat(rec.totalDebits()).isEqualTo(rec.totalCredits());
    }

    @Test
    void doubleFullRefundViaIdempotencyKey_isDeduped() {
        UUID merchantId = fixtures.newMerchant();
        Payment p = fixtures.newCapturedPayment(merchantId, 30_000);

        var r1 = refunds.refund(merchantId, p.getId(), null, "full", "dedupe-1");
        var r2 = refunds.refund(merchantId, p.getId(), null, "full", "dedupe-1");
        assertThat(r1.getId()).isEqualTo(r2.getId());

        long count = jdbc.queryForObject("SELECT count(*) FROM refunds WHERE payment_id = ?", Long.class, p.getId());
        assertThat(count).isEqualTo(1);
    }
}
