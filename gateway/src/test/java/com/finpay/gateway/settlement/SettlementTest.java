package com.finpay.gateway.settlement;

import com.finpay.gateway.groundtruth.GroundTruthResolver;
import com.finpay.gateway.ledger.LedgerService;
import com.finpay.gateway.payment.Payment;
import com.finpay.gateway.payment.PaymentRepository;
import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan V1 stretch: settlement batch. After capturing payments a merchant accrues a payable
 * balance; a settlement run pays it out and keeps the ledger balanced.
 *
 * <p><b>Settlement holdback (interview sprint Day 9).</b> A fresh capture used to be payable
 * the instant it posted, whatever path captured it — including the LOW-bucket auto-capture
 * shortcut Day 7 found, which never goes through review at all. A capture is now eligible for
 * payout only once its hidden fraud ground truth has actually resolved as legitimate; one that
 * resolves as fraud is written off before it can ever reach the merchant.
 */
class SettlementTest extends AbstractIntegrationTest {

    @Autowired SettlementService settlements;
    @Autowired LedgerService ledger;
    @Autowired TestFixtures fixtures;
    @Autowired PaymentRepository paymentRepo;
    @Autowired GroundTruthResolver groundTruthResolver;

    @Test
    void freshCaptures_areHeldBack_untilGroundTruthResolves_thenPayOutNormally() {
        UUID m1 = fixtures.newMerchant();
        UUID m2 = fixtures.newMerchant();
        Payment p1 = fixtures.newCapturedPayment(m1, 40_000);
        Payment p2 = fixtures.newCapturedPayment(m1, 15_000);
        Payment p3 = fixtures.newCapturedPayment(m2, 90_000);

        // The money is real (the ledger owes it), but none of it has cleared its dispute
        // window yet — a batch run right after capture must pay out nothing.
        assertThat(ledger.merchantPayableBalance(m1, "INR")).isEqualTo(55_000);
        SettlementService.BatchResult held = settlements.runBatch();
        assertThat(held.merchantCount()).as("nothing is eligible the instant it captures").isZero();
        assertThat(held.totalMinor()).isZero();
        assertThat(ledger.merchantPayableBalance(m1, "INR")).as("still owed, just not paid yet").isEqualTo(55_000);
        assertThat(ledger.merchantPayableBalance(m2, "INR")).isEqualTo(90_000);

        forceGroundTruthResolved(p1.getId(), p2.getId(), p3.getId());

        SettlementService.BatchResult r = settlements.runBatch();

        assertThat(r.merchantCount()).isEqualTo(2);
        assertThat(r.totalMinor()).isEqualTo(145_000);
        assertThat(r.writtenOffCount()).isZero();
        assertThat(ledger.merchantPayableBalance(m1, "INR")).isZero();
        assertThat(ledger.merchantPayableBalance(m2, "INR")).isZero();

        LedgerService.Reconciliation rec = ledger.reconcile();
        assertThat(rec.balanced()).isTrue();
        assertThat(rec.totalDebits()).isEqualTo(rec.totalCredits());

        assertThat(settlements.forMerchant(m1, PageRequest.of(0, 10)))
                .singleElement()
                .satisfies(s -> assertThat(s.getAmountMinor()).isEqualTo(55_000));

        assertThat(paymentRepo.findById(p1.getId()).orElseThrow().getSettledAt()).isNotNull();
        assertThat(paymentRepo.findById(p1.getId()).orElseThrow().getSettlementId()).isNotNull();

        // Settled money doesn't get offered again.
        SettlementService.BatchResult again = settlements.runBatch();
        assertThat(again.merchantCount()).isZero();
    }

    @Test
    void confirmedFraud_isWrittenOff_neverReachesTheMerchant() {
        UUID merchantId = fixtures.newMerchant();
        Payment legit = fixtures.newCapturedPayment(merchantId, 20_000);
        Payment fraud = fixtures.newCapturedPayment(merchantId, 75_000);

        forceGroundTruthResolved(legit.getId());
        forceGroundTruthFraud(fraud.getId());

        SettlementService.BatchResult r = settlements.runBatch();

        assertThat(r.writtenOffCount()).isEqualTo(1);
        assertThat(r.writtenOffMinor()).isEqualTo(75_000);
        assertThat(r.merchantCount()).as("the legit capture still pays out").isEqualTo(1);
        assertThat(r.totalMinor()).as("only the legit amount — never the fraud amount").isEqualTo(20_000);

        Payment reloaded = paymentRepo.findById(fraud.getId()).orElseThrow();
        assertThat(reloaded.getWrittenOffAt()).isNotNull();
        assertThat(reloaded.getSettledAt()).as("written off, not settled").isNull();
        assertThat(reloaded.getSettlementId()).isNull();

        assertThat(settlements.forMerchant(merchantId, PageRequest.of(0, 10)))
                .singleElement()
                .satisfies(s -> assertThat(s.getAmountMinor()).as("the payout never includes the written-off amount")
                        .isEqualTo(20_000));

        assertThat(ledger.merchantPayableBalance(merchantId, "INR"))
                .as("write-off clears the payable the same as a payout would").isZero();

        LedgerService.Reconciliation rec = ledger.reconcile();
        assertThat(rec.balanced()).as("the write-off is a real, balanced ledger transaction, not a deletion").isTrue();

        // A written-off payment is settled by neither this nor a later batch.
        SettlementService.BatchResult again = settlements.runBatch();
        assertThat(again.writtenOffCount()).isZero();
        assertThat(again.merchantCount()).isZero();
    }

    @Test
    void emptyBatchIsANoOp() {
        SettlementService.BatchResult r = settlements.runBatch();
        assertThat(r.merchantCount()).isZero();
        assertThat(r.totalMinor()).isZero();
        assertThat(r.writtenOffCount()).isZero();
        assertThat(r.writtenOffMinor()).isZero();
    }

    private void forceGroundTruthResolved(UUID... paymentIds) {
        for (UUID id : paymentIds) {
            jdbc.update("UPDATE fraud_ground_truth SET resolve_at = now() WHERE payment_id = ?", id);
        }
        groundTruthResolver.resolveDue();
    }

    private void forceGroundTruthFraud(UUID paymentId) {
        jdbc.update("UPDATE fraud_ground_truth SET intended_class = 'FRAUD', resolve_at = now() WHERE payment_id = ?",
                paymentId);
        groundTruthResolver.resolveDue();
    }
}
