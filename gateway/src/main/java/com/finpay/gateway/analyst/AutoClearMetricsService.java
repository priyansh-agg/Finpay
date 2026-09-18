package com.finpay.gateway.analyst;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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
        return compute(threshold, null, null);
    }

    /** @param since if non-null, only count reviews/decisions created at or after this instant. */
    @Transactional(readOnly = true)
    public Metrics compute(double threshold, java.time.Instant since) {
        return compute(threshold, since, null);
    }

    /**
     * @param since if non-null, only count reviews/decisions created at or after this instant
     * @param runId if non-null, only count reviews/decisions for payments from this simulation
     *        run (interview sprint Day 13 — the audit's §G.6 "add a run selector"). Composable
     *        with {@code since}, though the dashboard only ever uses one at a time in practice.
     *        {@code analyst_reviews} carries its own {@code run_id} (Day 6); {@code
     *        autoclear_decisions} doesn't, so those two queries join to {@code payments} for it.
     */
    @Transactional(readOnly = true)
    public Metrics compute(double threshold, java.time.Instant since, UUID runId) {
        String rvGuard = (since == null ? "" : " AND created_at >= '" + since + "'")
                + (runId == null ? "" : " AND run_id = '" + runId + "'");
        String acGuard = (since == null ? "" : " AND created_at >= '" + since + "'");
        String acRunJoin = runId == null ? ""
                : " AND payment_id IN (SELECT id FROM payments WHERE run_id = '" + runId + "')";
        long flaggedTotal = count("SELECT count(DISTINCT payment_id) FROM analyst_reviews WHERE 1=1" + rvGuard);
        long pendingHuman = count("SELECT count(*) FROM analyst_reviews WHERE queue_status = 'PENDING'" + rvGuard);
        long decided = count("SELECT count(*) FROM analyst_reviews WHERE queue_status = 'DECIDED'" + rvGuard);
        long autoCleared = count("SELECT count(*) FROM autoclear_decisions WHERE auto_cleared = true" + acGuard + acRunJoin);
        long humanReviewed = count(
                "SELECT count(*) FROM analyst_reviews WHERE queue_status = 'DECIDED' AND reviewer_type = 'HUMAN'" + rvGuard);
        long simulatedAnalystReviewed = count(
                "SELECT count(*) FROM analyst_reviews WHERE queue_status = 'DECIDED' AND reviewer_type = 'SIMULATED_ANALYST'" + rvGuard);
        long autoClearsResolved = count(
                "SELECT count(*) FROM autoclear_decisions WHERE auto_cleared = true AND later_confirmed_fraud IS NOT NULL" + acGuard + acRunJoin);
        long falseAutoClears = count(
                "SELECT count(*) FROM autoclear_decisions WHERE auto_cleared = true AND later_confirmed_fraud = true" + acGuard + acRunJoin);

        double autoClearRate = decided == 0 ? 0 : 100.0 * autoCleared / decided;
        double falseRate = autoClearsResolved == 0 ? 0 : 100.0 * falseAutoClears / autoClearsResolved;
        Double manualReviewReductionPct = humanReviewed == 0 ? null
                : round(100.0 * autoCleared / (autoCleared + humanReviewed));

        return new Metrics(flaggedTotal, pendingHuman, decided, autoCleared,
                humanReviewed, simulatedAnalystReviewed,
                round(autoClearRate), manualReviewReductionPct,
                autoClearsResolved, falseAutoClears, round(falseRate), threshold);
    }

    /**
     * Ground-truth-scored effectiveness of the WHOLE fraud-prevention pipeline, not just the
     * Auto-Clear Engine — a rejection before capture (review, or a hard risk block) and a
     * write-off after capture but before settlement (interview sprint Day 9 — the closer for
     * the Day 7 auto-capture-bypass finding) both count as "caught". EVALUATION ONLY: computed
     * from the hidden {@code fraud_ground_truth} table, never used to decide anything
     * (architecture-freeze Doubt 3) — this is a reporting surface, not a decision path.
     *
     * <p>"Caught by review" is deliberately narrower than "any failed payment": a plain
     * simulated processor decline ({@code failed_reason} anything other than
     * {@code review_rejected}/{@code risk_blocked}) means the card was declined for an ordinary
     * reason, not that the fraud pipeline did anything — counting it would inflate recall for
     * catches nobody made.
     *
     * @param totalFraud / totalLegit     resolved ground-truth population in scope
     * @param fraudCaughtByReview   actual fraud rejected before capture — VOIDED, or FAILED
     *        with a review/risk reason
     * @param fraudCaughtByWriteOff actual fraud captured, then written off before settlement —
     *        the auto-capture bypass, closed downstream instead of upstream
     * @param fraudCaught           fraudCaughtByReview + fraudCaughtByWriteOff
     * @param fraudCaughtMinor      amount (minor units) of fraudCaught
     * @param fraudMissed           actual fraud captured and NOT (yet) written off — money that
     *        has reached, or is still on track to reach, the merchant right now
     * @param fraudMissedMinor      amount (minor units) of fraudMissed
     * @param fraudStillPending     actual fraud whose payment hasn't reached a terminal
     *        capture/reject state yet — excluded from recall/precision, reported for transparency
     * @param legitWronglyRejected  actual legit rejected by review or a risk block — friction
     *        with no fraud behind it
     * @param legitCorrectlyCleared actual legit that captured
     * @param recallPct     fraudCaught / (fraudCaught + fraudMissed) — of all the fraud that
     *        existed, how much did the whole pipeline actually stop
     * @param precisionPct  fraudCaught / (fraudCaught + legitWronglyRejected) — of everything
     *        the pipeline blocked, how much of it was actually fraud
     * @param falsePositiveRatePct  legitWronglyRejected / totalLegit
     */
    public record FraudMetrics(
            long totalFraud, long totalLegit,
            long fraudCaughtByReview, long fraudCaughtByWriteOff, long fraudCaught, long fraudCaughtMinor,
            long fraudMissed, long fraudMissedMinor, long fraudStillPending,
            long legitWronglyRejected, long legitCorrectlyCleared,
            Double recallPct, Double precisionPct, Double falsePositiveRatePct) {}

    private static final String REJECTED_BY_PIPELINE =
            "(p.status = 'VOIDED' OR (p.status = 'FAILED' AND p.failed_reason IN ('review_rejected','risk_blocked')))";

    @Transactional(readOnly = true)
    public FraudMetrics computeFraudMetrics(UUID runId) {
        String scope = runId == null ? "" : " AND p.run_id = ?";

        long totalFraud = scoped("g.actually_fraudulent = true", scope, runId);
        long totalLegit = scoped("g.actually_fraudulent = false", scope, runId);

        long fraudCaughtByReview = scoped("g.actually_fraudulent = true AND " + REJECTED_BY_PIPELINE, scope, runId);
        long fraudCaughtByWriteOff = scoped("g.actually_fraudulent = true AND p.written_off_at IS NOT NULL", scope, runId);
        long fraudMissed = scoped(
                "g.actually_fraudulent = true AND p.status = 'CAPTURED' AND p.written_off_at IS NULL", scope, runId);
        long fraudStillPending = totalFraud - fraudCaughtByReview - fraudCaughtByWriteOff - fraudMissed;

        long legitWronglyRejected = scoped("g.actually_fraudulent = false AND " + REJECTED_BY_PIPELINE, scope, runId);
        long legitCorrectlyCleared = scoped("g.actually_fraudulent = false AND p.status = 'CAPTURED'", scope, runId);

        long fraudCaught = fraudCaughtByReview + fraudCaughtByWriteOff;
        long fraudCaughtMinor = scopedAmount("g.actually_fraudulent = true AND (" + REJECTED_BY_PIPELINE
                + " OR p.written_off_at IS NOT NULL)", scope, runId);
        long fraudMissedMinor = scopedAmount(
                "g.actually_fraudulent = true AND p.status = 'CAPTURED' AND p.written_off_at IS NULL", scope, runId);

        Double recallPct = (fraudCaught + fraudMissed) == 0 ? null
                : round(100.0 * fraudCaught / (fraudCaught + fraudMissed));
        Double precisionPct = (fraudCaught + legitWronglyRejected) == 0 ? null
                : round(100.0 * fraudCaught / (fraudCaught + legitWronglyRejected));
        Double falsePositiveRatePct = totalLegit == 0 ? null : round(100.0 * legitWronglyRejected / totalLegit);

        return new FraudMetrics(totalFraud, totalLegit,
                fraudCaughtByReview, fraudCaughtByWriteOff, fraudCaught, fraudCaughtMinor,
                fraudMissed, fraudMissedMinor, fraudStillPending,
                legitWronglyRejected, legitCorrectlyCleared,
                recallPct, precisionPct, falsePositiveRatePct);
    }

    /**
     * {@code is_warmup = false} always, not just when scoped to one run: simulation warm-up
     * scenery (customer-history seeding) is guaranteed-legit padding with no adversarial intent
     * behind it — mixing it into precision/recall/FP-rate dilutes exactly the way a "1,000
     * transactions" run silently becoming 1,712 payments distorted every other summary before
     * Day 6 excluded it (interview sprint, same lesson: population, not scenery).
     */
    private long scoped(String condition, String scope, UUID runId) {
        String sql = "SELECT count(*) FROM payments p JOIN fraud_ground_truth g ON g.payment_id = p.id "
                + "WHERE g.resolved = true AND p.is_warmup = false AND " + condition + scope;
        Long n = runId == null ? jdbc.queryForObject(sql, Long.class)
                : jdbc.queryForObject(sql, Long.class, runId);
        return n == null ? 0 : n;
    }

    private long scopedAmount(String condition, String scope, UUID runId) {
        String sql = "SELECT coalesce(sum(p.amount_minor), 0) FROM payments p JOIN fraud_ground_truth g ON g.payment_id = p.id "
                + "WHERE g.resolved = true AND p.is_warmup = false AND " + condition + scope;
        Long n = runId == null ? jdbc.queryForObject(sql, Long.class)
                : jdbc.queryForObject(sql, Long.class, runId);
        return n == null ? 0 : n;
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
