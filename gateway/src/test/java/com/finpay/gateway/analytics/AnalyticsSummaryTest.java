package com.finpay.gateway.analytics;

import com.finpay.gateway.support.AbstractIntegrationTest;
import com.finpay.gateway.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Overview roll-up (design round 2 — the audit's P2-1 and §I Phase 6 item 19). Two things
 * have to hold for this screen to be trustworthy, and both have bitten this project before:
 * one merchant's numbers must never include another's, and simulation warm-up scenery must
 * never be counted as real traffic (the same mistake interview-sprint Day 6 found in the run
 * summary and Day 10 found again in the fraud metrics).
 */
class AnalyticsSummaryTest extends AbstractIntegrationTest {

    @Autowired TestFixtures fixtures;
    @Autowired AnalyticsService analytics;

    @Test
    void summary_countsOnlyTheScopedMerchant_notThePlatform() {
        UUID merchantA = fixtures.newMerchant();
        UUID merchantB = fixtures.newMerchant();
        fixtures.newCapturedPayment(merchantA, 10_000);
        fixtures.newCapturedPayment(merchantA, 25_000);
        fixtures.newCapturedPayment(merchantB, 90_000);

        AnalyticsService.Summary scopedToA = analytics.summarize(merchantA);
        AnalyticsService.Summary platformWide = analytics.summarize(null);

        assertThat(scopedToA.totalPayments()).as("merchant A's own two payments, not B's").isEqualTo(2);
        assertThat(platformWide.totalPayments()).as("the unscoped roll-up sees all three")
                .isGreaterThanOrEqualTo(3);
        assertThat(scopedToA.totalPayments()).as("A's scope never leaks B's rows")
                .isLessThan(platformWide.totalPayments());
    }

    @Test
    void warmupScenery_isExcludedFromEveryNumber() {
        UUID merchantId = fixtures.newMerchant();
        fixtures.newCapturedPayment(merchantId, 10_000);
        UUID sceneryId = fixtures.newCapturedPayment(merchantId, 999_000).getId();
        jdbc.update("UPDATE payments SET is_warmup = true WHERE id = ?", sceneryId);

        AnalyticsService.Summary summary = analytics.summarize(merchantId);

        assertThat(summary.totalPayments()).as("the warm-up payment is not real traffic").isEqualTo(1);
        assertThat(summary.capturedVolumeMinor()).as("nor is its ₹9,990 counted as volume")
                .isLessThan(999_000);
        assertThat(summary.daily()).as("always a full 14-day window, gaps filled with zeroes")
                .hasSize(14);
    }

    /**
     * Design round 5 added a second chart series and a period-over-period delta. Both have a
     * failure mode worth pinning: a failed payment silently not appearing in the series it is
     * supposed to explain, and a delta inventing a percentage change out of an empty prior
     * window (every fixture here is created "now", so the previous 14 days hold nothing).
     */
    @Test
    void dailyCarriesTheFailedSeries_andTheDeltaStaysNullWithNoPriorPeriod() {
        UUID merchantId = fixtures.newMerchant();
        fixtures.newCapturedPayment(merchantId, 10_000);
        fixtures.pay(merchantId, fixtures.newOrder(merchantId, 20_000).getId(), "CARD", "tok_declined");

        AnalyticsService.Summary summary = analytics.summarize(merchantId);

        assertThat(summary.daily().stream().mapToLong(AnalyticsService.DailyPoint::failedCount).sum())
                .as("the declined payment lands in the failed series, not just the status counts")
                .isEqualTo(1);
        assertThat(summary.previousCapturedVolumeMinor())
                .as("nothing was created 14-28 days ago").isZero();
        assertThat(summary.capturedVolumeDeltaPct())
                .as("no honest percentage change exists from an empty prior window").isNull();
    }
}
