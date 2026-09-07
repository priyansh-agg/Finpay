package com.finpay.gateway.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * pgvector "similar fraud case" retrieval (architecture-freeze §pgvector). We encode a
 * payment's features into a fixed 16-dim numeric vector, then cosine-search the store of
 * CONFIRMED fraud vectors. The average similarity to the top-K becomes an extra risk signal,
 * and the retrieved cases feed the LLM "explain this flag" feature.
 */
@Component
public class FraudVectorStore {

    private static final Logger log = LoggerFactory.getLogger(FraudVectorStore.class);
    private static final int DIM = 16;
    private static final int TOP_K = 5;

    private final JdbcTemplate jdbc;

    public FraudVectorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Deterministic 16-dim encoding, each component roughly normalised to [0,1] or [-1,1]. */
    public float[] encode(RiskFeatures f) {
        double hourRad = 2 * Math.PI * f.hourOfDay() / 24.0;
        float[] v = new float[DIM];
        v[0]  = clamp01(f.amountMinor() / 1_000_000.0);          // ~₹10k scale
        v[1]  = (float) Math.tanh(f.amountDeviationRatio() / 5.0);
        v[2]  = clamp01(f.velocity5m() / 10.0);
        v[3]  = clamp01(f.velocity1h() / 30.0);
        v[4]  = f.deviceIsNew() ? 1f : 0f;
        v[5]  = f.ipIsNew() ? 1f : 0f;
        v[6]  = clamp01(f.failedAttemptsLastHour() / 10.0);
        v[7]  = clamp01(f.customerTenureDays() / 365.0);
        v[8]  = clamp01(f.distinctAccountsForDevice() / 5.0);
        v[9]  = (float) Math.sin(hourRad);
        v[10] = (float) Math.cos(hourRad);
        v[11] = "UPI".equalsIgnoreCase(f.method()) ? 1f : 0f;
        v[12] = "CARD".equalsIgnoreCase(f.method()) ? 1f : 0f;
        v[13] = clamp01(f.customerRollingAvgMinor() / 1_000_000.0);
        v[14] = f.customerTenureDays() < 7 ? 1f : 0f;
        v[15] = clamp01((f.velocity5m() + f.failedAttemptsLastHour()) / 12.0);
        return v;
    }

    public record SimilarCase(UUID paymentId, String fraudType, double similarity) {}

    public List<SimilarCase> topSimilar(float[] vector) {
        try {
            return jdbc.query("""
                    SELECT payment_id, fraud_type, 1 - (feature_vector <=> ?::vector) AS similarity
                    FROM fraud_case_vectors
                    ORDER BY feature_vector <=> ?::vector
                    LIMIT ?
                    """,
                    (rs, i) -> new SimilarCase(
                            rs.getObject("payment_id", UUID.class),
                            rs.getString("fraud_type"),
                            rs.getDouble("similarity")),
                    literal(vector), literal(vector), TOP_K);
        } catch (Exception e) {
            log.warn("pgvector similarity query failed: {}", e.toString());
            return List.of();
        }
    }

    /**
     * Signal = mean of the retrieved cases that are <b>genuinely</b> close (cosine ≥ 0.9).
     * In a crowded low-dimensional space almost everything is "somewhat" similar, so a floor
     * is essential or this feature just adds noise. Needs a minimum case count to be trusted.
     */
    public double avgSimilarityToKnownFraud(float[] vector) {
        if (confirmedCount() < 25) return 0.0;
        List<SimilarCase> hits = topSimilar(vector).stream()
                .filter(s -> s.similarity() >= 0.90)
                .toList();
        if (hits.isEmpty()) return 0.0;
        return hits.stream().mapToDouble(SimilarCase::similarity).average().orElse(0.0);
    }

    public void storeConfirmedFraud(UUID paymentId, float[] vector, String fraudType) {
        jdbc.update("""
                INSERT INTO fraud_case_vectors (payment_id, feature_vector, fraud_type)
                VALUES (?, ?::vector, ?)
                ON CONFLICT (payment_id) DO NOTHING
                """, paymentId, literal(vector), fraudType);
    }

    public long confirmedCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM fraud_case_vectors", Long.class);
        return n == null ? 0 : n;
    }

    private static String literal(float[] v) {
        StringJoiner sj = new StringJoiner(",", "[", "]");
        for (float x : v) sj.add(Float.toString(x));
        return sj.toString();
    }

    private static float clamp01(double d) {
        return (float) Math.max(0.0, Math.min(1.0, d));
    }
}
