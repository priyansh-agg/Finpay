package com.finpay.gateway.settlement;

import com.finpay.gateway.ledger.LedgerService;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan V1 stretch: settlement batch. After capturing payments a merchant has an accrued
 * payable balance; a settlement run sweeps it to a payout, leaves the payable at zero, and
 * keeps the ledger balanced.
 */
class SettlementTest extends AbstractIntegrationTest {

    @Autowired SettlementService settlements;
    @Autowired LedgerService ledger;
    @Autowired TestFixtures fixtures;

    @Test
    void batchSweepsPayableToPayout_andLedgerStaysBalanced() {
        UUID m1 = fixtures.newMerchant();
        UUID m2 = fixtures.newMerchant();
        fixtures.newCapturedPayment(m1, 40_000);
        fixtures.newCapturedPayment(m1, 15_000);
        fixtures.newCapturedPayment(m2, 90_000);

        assertThat(ledger.merchantPayableBalance(m1, "INR")).isEqualTo(55_000);
        assertThat(ledger.merchantPayableBalance(m2, "INR")).isEqualTo(90_000);

        SettlementService.BatchResult r = settlements.runBatch();

        assertThat(r.merchantCount()).isEqualTo(2);
        assertThat(r.totalMinor()).isEqualTo(145_000);
        assertThat(ledger.merchantPayableBalance(m1, "INR")).isZero();
        assertThat(ledger.merchantPayableBalance(m2, "INR")).isZero();

        LedgerService.Reconciliation rec = ledger.reconcile();
        assertThat(rec.balanced()).isTrue();
        assertThat(rec.totalDebits()).isEqualTo(rec.totalCredits());

        assertThat(settlements.forMerchant(m1, org.springframework.data.domain.PageRequest.of(0, 10)))
                .singleElement()
                .satisfies(s -> assertThat(s.getAmountMinor()).isEqualTo(55_000));
    }

    @Test
    void emptyBatchIsANoOp() {
        SettlementService.BatchResult r = settlements.runBatch();
        assertThat(r.merchantCount()).isZero();
        assertThat(r.totalMinor()).isZero();
    }
}
