package com.finpay.gateway.analyst;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The current auto-clear confidence threshold. Starts at {@code finpay.autoclear.threshold}
 * and is RATCHETED UP by {@link AutoClearCalibrator} whenever a false auto-clear is confirmed
 * — a single false auto-clear is treated as a critical bug, not a tuning nuisance (Doubt 4).
 * Persisted in {@code system_config} so a restart keeps the calibrated value.
 */
@Component
public class AutoClearPolicy {

    private static final Logger log = LoggerFactory.getLogger(AutoClearPolicy.class);
    private static final String KEY = "autoclear.threshold";

    private final JdbcTemplate jdbc;
    private final double floor;
    private volatile double threshold;

    public AutoClearPolicy(JdbcTemplate jdbc, @Value("${finpay.autoclear.threshold:0.97}") double floor) {
        this.jdbc = jdbc;
        this.floor = floor;
        Double stored = readStored();
        this.threshold = stored == null ? floor : Math.max(floor, stored);
    }

    public double threshold() {
        return threshold;
    }

    /** Raise the bar. Called after a confirmed false auto-clear. */
    public synchronized void ratchetUp(double toAtLeast) {
        double next = Math.min(0.999, Math.max(threshold, toAtLeast));
        if (next > threshold) {
            log.error("AUTO-CLEAR THRESHOLD RAISED {} -> {} after a confirmed false auto-clear", threshold, next);
            threshold = next;
            jdbc.update("""
                    INSERT INTO system_config(key, value, updated_at) VALUES (?, ?, now())
                    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                    """, KEY, Double.toString(next));
        }
    }

    private Double readStored() {
        try {
            return jdbc.queryForObject("SELECT value::double precision FROM system_config WHERE key = ?",
                    Double.class, KEY);
        } catch (Exception e) {
            return null;
        }
    }
}
