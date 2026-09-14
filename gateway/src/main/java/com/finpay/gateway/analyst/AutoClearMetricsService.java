package com.finpay.gateway.analyst;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutoClearMetricsService {

    private final JdbcTemplate jdbc;

    public AutoClearMetricsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The Phase-3 headline metrics.
     *
     * @param flaggedTotal        payments that ever entered the review queue
     * @param decided             reviews that have been decided
     * @param autoCleared         decided by the Auto-Clear Engine (no human needed)
     * @param referenceReviewed   decided by the reference analyst
     * @param autoClearRatePct    autoCleared / decided — a real, measured number
     * @param manualReviewReductionPct  workload actually removed from a HUMAN reviewer,
     *        i.e. {@code autoCleared / flaggedEligibleForAHuman}. There is currently no
     *        recorded human baseline to divide by — the scheduler decides every review
     *        itself (interview sprint Day 8 gives the queue a real human path) — so this is
     *        {@code null} rather than a number. It used to silently equal
     *        {@code autoClearRatePct}, which is a different question ("what fraction of
     *        decided reviews were auto-cleared?") wearing this one's label.
     * @param autoClearsResolved  auto-clears whose ground truth is now known
     * @param falseAutoClears     auto-clears later confirmed fraudulent (MUST be ~0)
     * @param falseAutoClearRatePct falseAutoClears / autoClearsResolved
     */
    public record Metrics(
            long flaggedTotal, long decided, long autoCleared, long referenceReviewed,
            double autoClearRatePct, Double manualReviewReductionPct,
            long autoClearsResolved, long falseAutoClears, double falseAutoClearRatePct,
            double threshold) {}

    @Transactional(readOnly = true)
    public Metrics compute(double threshold) {
        return compute(threshold, null);
    }

    /** @param since if non-null, only count reviews/decisions created at or after this instant. */
    @Transactional(readOnly = true)
    public Metrics compute(double threshold, java.time.Instant since) {
        String rvGuard = since == null ? "" : " AND created_at >= '" + since + "'";
        String acGuard = since == null ? "" : " AND created_at >= '" + since + "'";
        long flaggedTotal = count("SELECT count(DISTINCT payment_id) FROM analyst_reviews WHERE 1=1" + rvGuard);
        long decided = count("SELECT count(*) FROM analyst_reviews WHERE queue_status = 'DECIDED'" + rvGuard);
        long autoCleared = count("SELECT count(*) FROM autoclear_decisions WHERE auto_cleared = true" + acGuard);
        long referenceReviewed = count(
                "SELECT count(*) FROM analyst_reviews WHERE queue_status = 'DECIDED' AND reviewer_type = 'REFERENCE_ANALYST'" + rvGuard);
        long autoClearsResolved = count(
                "SELECT count(*) FROM autoclear_decisions WHERE auto_cleared = true AND later_confirmed_fraud IS NOT NULL" + acGuard);
        long falseAutoClears = count(
                "SELECT count(*) FROM autoclear_decisions WHERE auto_cleared = true AND later_confirmed_fraud = true" + acGuard);

        double autoClearRate = decided == 0 ? 0 : 100.0 * autoCleared / decided;
        double falseRate = autoClearsResolved == 0 ? 0 : 100.0 * falseAutoClears / autoClearsResolved;

        return new Metrics(flaggedTotal, decided, autoCleared, referenceReviewed,
                round(autoClearRate), null,
                autoClearsResolved, falseAutoClears, round(falseRate), threshold);
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
