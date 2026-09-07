package com.finpay.gateway.ledger;

import com.finpay.gateway.order.Order;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.payment.PaymentService;
import com.finpay.gateway.refund.RefundService;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerReconciliationTest extends AbstractIntegrationTest {

    @Autowired PaymentService payments;
    @Autowired RefundService refunds;
    @Autowired LedgerService ledger;
    @Autowired TestFixtures fixtures;

    @Test
    void ledgerBalancesAfterAnArbitrarySequenceOfCapturesAndRefunds() {
        UUID m = fixtures.newMerchant();
        for (long amt : new long[]{10_000, 25_000, 99_999, 500, 7_777}) {
            Order o = fixtures.newOrder(m, amt);
            Payment p = fixtures.pay(m, o.getId(), "CARD", "tok_valid");
            if (amt % 2 == 0) {
                refunds.refund(m, p.getId(), amt / 2, "partial", "rf-" + p.getId());
            }
        }
        LedgerService.Reconciliation rec = ledger.reconcile();
        assertThat(rec.unbalancedTxIds()).isEmpty();
        assertThat(rec.totalDebits()).isEqualTo(rec.totalCredits());
        assertThat(rec.balanced()).isTrue();
    }

    @Test
    void ledgerEntriesAreAppendOnly_dbTriggerBlocksUpdateAndDelete() {
        UUID m = fixtures.newMerchant();
        fixtures.newCapturedPayment(m, 12_000);

        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount_minor = 1"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries"))
                .hasMessageContaining("append-only");
    }

    @Test
    void doubleCaptureOfSamePaymentIsRejected() {
        UUID m = fixtures.newMerchant();
        Payment p = fixtures.newCapturedPayment(m, 8_000);
        // already auto-captured; an explicit capture call must fail (not CAPTURED-able)
        assertThatThrownBy(() -> payments.capture(m, p.getId()))
                .hasMessageContaining("not capturable");
    }
}
