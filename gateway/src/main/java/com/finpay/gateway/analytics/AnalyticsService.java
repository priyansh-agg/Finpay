package com.finpay.gateway.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The console's Overview roll-up — the audit's P2-1 ("no merchant overview: no balance, volume,
 * success rate, or attention queue") and §I Phase 6 item 19, {@code GET /v1/analytics/summary}.
 *
 * <p>Aggregates read straight through {@link JdbcTemplate}, the way {@code AutoClearMetricsService}
 * already does — these are reporting rollups over whole tables, not entity graphs worth hydrating
 * through JPA.
 *
 * <p>Simulation warm-up scenery ({@code is_warmup = true}) is excluded from every number here, for
 * the same reason interview-sprint Day 6 excluded it from the fraud metrics: it is traffic the
 * generator needed in order to build a population, not traffic a merchant actually took. A demo
 * that counted it would overstate volume by roughly half.
 */
@Service
public class AnalyticsService {

    /** Days shown on the chart, and therefore the width of the period-over-period comparison. */
    private static final int WINDOW_DAYS = 14;

    private final JdbcTemplate jdbc;

    public AnalyticsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One day on the volume chart. {@code date} is ISO {@code yyyy-MM-dd}, in the server's zone.
     * Failed covers {@code FAILED} and {@code VOIDED} — money that was attempted and did not land,
     * which is the series worth showing against captured volume.
     */
    public record DailyPoint(String date, long capturedMinor, long count,
                             long failedMinor, long failedCount) {}

    public record StatusSlice(String status, long count) {}

    /**
     * @param awaitingSettlementMinor captured, not yet paid out, not written off as fraud — the
     *        holdback window interview-sprint Day 9 introduced, which is exactly the number a
     *        merchant cares about ("what is owed to me but not yet sent")
     * @param capturedVolumeMinor lifetime captured volume, all time
     * @param successRatePct captured over every payment attempted; {@code null} until there is at
     *        least one payment to divide by, rather than a fabricated 0%
     * @param windowCapturedVolumeMinor captured within the charted window only
     * @param previousCapturedVolumeMinor captured in the equally-long window immediately before it
     * @param capturedVolumeDeltaPct window over previous window. Deliberately describes the
     *        <em>window</em>, not the lifetime figure — a lifetime total compared against a
     *        14-day period would be a meaningless number that happens to look like insight.
     *        {@code null} when the previous window is empty, since there is no honest percentage
     *        change from zero.
     */
    public record Summary(
            long awaitingSettlementMinor,
            long capturedVolumeMinor,
            long capturedCount,
            long totalPayments,
            Double successRatePct,
            long heldCount,
            long pendingReviewCount,
            long failedCount,
            long windowCapturedVolumeMinor,
            long previousCapturedVolumeMinor,
            Double capturedVolumeDeltaPct,
            List<DailyPoint> daily,
            List<StatusSlice> statusCounts) {}

    /**
     * @param merchantId scope to one merchant, or {@code null} for the platform-wide roll-up an
     *        analyst/admin sees. Concatenated into the SQL rather than bound: it is a
     *        {@link UUID}, so it cannot carry injection, and an optional bind parameter whose only
     *        use is {@code ? IS NULL} is precisely what PostgreSQL could not infer a type for on
     *        interview-sprint Day 11.
     */
    @Transactional(readOnly = true)
    public Summary summarize(UUID merchantId) {
        String scope = merchantId == null ? "" : " AND merchant_id = '" + merchantId + "'";
        String captured = "status IN ('CAPTURED', 'REFUNDED')";
        int windowStart = WINDOW_DAYS - 1;          // 13 days back, inclusive of today = 14 days
        int previousStart = WINDOW_DAYS * 2 - 1;    // 27 days back

        long awaitingSettlement = sum("""
                SELECT COALESCE(SUM(amount_minor), 0) FROM payments
                WHERE %s
                  AND settled_at IS NULL AND written_off_at IS NULL AND is_warmup = false
                """.formatted(captured) + scope);
        long capturedVolume = sum("""
                SELECT COALESCE(SUM(amount_minor), 0) FROM payments
                WHERE %s AND is_warmup = false
                """.formatted(captured) + scope);
        long capturedCount = sum("SELECT count(*) FROM payments WHERE " + captured + " AND is_warmup = false" + scope);
        long total = sum("SELECT count(*) FROM payments WHERE is_warmup = false" + scope);
        long held = sum("SELECT count(*) FROM payments WHERE status = 'HELD' AND is_warmup = false" + scope);
        long pendingReview = sum("SELECT count(*) FROM payments WHERE status = 'PENDING_REVIEW' AND is_warmup = false" + scope);
        long failed = sum("SELECT count(*) FROM payments WHERE status IN ('FAILED', 'VOIDED') AND is_warmup = false" + scope);

        Double successRate = total == 0 ? null : round2(100.0 * capturedCount / total);

        long windowCaptured = sum("""
                SELECT COALESCE(SUM(amount_minor), 0) FROM payments
                WHERE %s AND is_warmup = false
                  AND created_at >= date_trunc('day', now()) - interval '%d days'
                """.formatted(captured, windowStart) + scope);
        long previousCaptured = sum("""
                SELECT COALESCE(SUM(amount_minor), 0) FROM payments
                WHERE %s AND is_warmup = false
                  AND created_at >= date_trunc('day', now()) - interval '%d days'
                  AND created_at <  date_trunc('day', now()) - interval '%d days'
                """.formatted(captured, previousStart, windowStart) + scope);
        Double deltaPct = previousCaptured == 0 ? null
                : round2(100.0 * (windowCaptured - previousCaptured) / previousCaptured);

        // LEFT JOIN off generate_series so a day with no traffic is a zero, not a gap in the chart.
        List<DailyPoint> daily = jdbc.query("""
                SELECT to_char(d.day, 'YYYY-MM-DD') AS day,
                       COALESCE(SUM(p.amount_minor) FILTER (WHERE p.status IN ('CAPTURED', 'REFUNDED')), 0) AS captured_minor,
                       count(p.id) AS cnt,
                       COALESCE(SUM(p.amount_minor) FILTER (WHERE p.status IN ('FAILED', 'VOIDED')), 0) AS failed_minor,
                       count(p.id) FILTER (WHERE p.status IN ('FAILED', 'VOIDED')) AS failed_cnt
                FROM generate_series(date_trunc('day', now()) - interval '%d days',
                                     date_trunc('day', now()), interval '1 day') AS d(day)
                LEFT JOIN payments p
                       ON date_trunc('day', p.created_at) = d.day AND p.is_warmup = false
                """.formatted(windowStart) + scope + """
                GROUP BY d.day ORDER BY d.day
                """,
                (rs, i) -> new DailyPoint(rs.getString("day"), rs.getLong("captured_minor"),
                        rs.getLong("cnt"), rs.getLong("failed_minor"), rs.getLong("failed_cnt")));

        List<StatusSlice> statusCounts = jdbc.query(
                "SELECT status, count(*) AS cnt FROM payments WHERE is_warmup = false" + scope
                        + " GROUP BY status ORDER BY cnt DESC",
                (rs, i) -> new StatusSlice(rs.getString("status"), rs.getLong("cnt")));

        return new Summary(awaitingSettlement, capturedVolume, capturedCount, total, successRate,
                held, pendingReview, failed, windowCaptured, previousCaptured, deltaPct,
                daily, statusCounts);
    }

    private long sum(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
