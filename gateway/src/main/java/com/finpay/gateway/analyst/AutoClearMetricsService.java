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
     * @param pendingHuman        reviews still PENDING right now — awaiting a human or, in a
     *        demo, {@code SimulatedAnalystWorker}
     * @param decided             reviews that have been decided
     * @param autoCleared         decided by the Auto-Clear Engine (no human needed)
     * @param humanReviewed       decided by an actual human via {@code /reviews/{id}/decide}
     *        (interview sprint Day 8) — the real baseline manualReviewReductionPct divides by
     * @param simulatedAnalystReviewed  decided by the demo-only {@code SimulatedAnalystWorker};
     *        reported for transparency but deliberately excluded from every "reviewed by an
     *        analyst" count above, so it can never be mistaken for real review workload
     * @param autoClearRatePct    autoCleared / decided — a real, measured number
     * @param manualReviewReductionPct  workload actually removed from a HUMAN reviewer, i.e.
     *        {@code autoCleared / (autoCleared + humanReviewed)}. {@code null} until at least
     *        one review has actually been decided by a human — there is no real baseline to
     *        divide by before that. It used to silently equal {@code autoClearRatePct} (a
     *        different question — "what fraction of decided reviews were auto-cleared?" — the
     *        scheduler decided every one of them itself, so there was no human path to reduce
     *        volume from at all) wearing this one's label.
     * @param autoClearsResolved  auto-clears whose ground truth is now known
     * @param falseAutoClears     auto-clears later confirmed fraudulent (MUST be ~0)
     * @param falseAutoClearRatePct falseAutoClears / autoClearsResolved
     */
    public record Metrics(
            long flaggedTotal, long pendingHuman, long decided, long autoCleared,
            long humanReviewed, long simulatedAnalystReviewed,
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
        long pendingHuman = count("SELECT count(*) FROM analyst_reviews WHERE queue_status = 'PENDING'" + rvGuard);
        long decided = count("SELECT count(*) FROM analyst_reviews WHERE queue_status = 'DECIDED'" + rvGuard);
        long autoCleared = count("SELECT count(*) FROM autoclear_decisions WHERE auto_cleared = true" + acGuard);
        long humanReviewed = count(
                "SELECT count(*) FROM analyst_reviews WHERE queue_status = 'DECIDED' AND reviewer_type = 'HUMAN'" + rvGuard);
        long simulatedAnalystReviewed = count(
                "SELECT count(*) FROM analyst_reviews WHERE queue_status = 'DECIDED' AND reviewer_type = 'SIMULATED_ANALYST'" + rvGuard);
        long autoClearsResolved = count(
                "SELECT count(*) FROM autoclear_decisions WHERE auto_cleared = true AND later_confirmed_fraud IS NOT NULL" + acGuard);
        long falseAutoClears = count(
                "SELECT count(*) FROM autoclear_decisions WHERE auto_cleared = true AND later_confirmed_fraud = true" + acGuard);

        double autoClearRate = decided == 0 ? 0 : 100.0 * autoCleared / decided;
        double falseRate = autoClearsResolved == 0 ? 0 : 100.0 * falseAutoClears / autoClearsResolved;
        Double manualReviewReductionPct = humanReviewed == 0 ? null
                : round(100.0 * autoCleared / (autoCleared + humanReviewed));

        return new Metrics(flaggedTotal, pendingHuman, decided, autoCleared,
                humanReviewed, simulatedAnalystReviewed,
                round(autoClearRate), manualReviewReductionPct,
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
