package com.finpay.gateway.analyst;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The current auto-clear confidence threshold. Never below {@code finpay.autoclear.threshold}
 * (the floor), and RATCHETED UP by {@link AutoClearCalibrator} whenever a false auto-clear is
 * confirmed — a single false auto-clear is treated as a critical bug, not a tuning nuisance
 * (architecture-freeze Doubt 4).
 *
 * <p>{@code system_config} is the single source of truth and is read on every call. This used
 * to cache the value in a field at startup, which meant a ratchet only reached the gateway
 * instance whose calibrator ran it: every other instance kept auto-clearing at the old,
 * already-proven-unsafe threshold until it restarted. The ratchet itself is one atomic
 * {@code GREATEST(...)} upsert, so concurrent calibrators can only ever move it up.
 */
@Component
public class AutoClearPolicy {

    private static final Logger log = LoggerFactory.getLogger(AutoClearPolicy.class);
    private static final String KEY = "autoclear.threshold";
    private static final double CEILING = 0.999;

    private final JdbcTemplate jdbc;
    private final double floor;

    public AutoClearPolicy(JdbcTemplate jdbc, @Value("${finpay.autoclear.threshold:0.97}") double floor) {
        this.jdbc = jdbc;
        this.floor = floor;
    }

    /** The effective threshold: the stored (possibly ratcheted) value, never below the floor. */
    public double threshold() {
        Double stored = readStored();
        return stored == null ? floor : Math.max(floor, stored);
    }

    /** Raise the bar. Called after a confirmed false auto-clear. Never lowers it. */
    public void ratchetUp(double toAtLeast) {
        double before = threshold();
        jdbc.update("""
                INSERT INTO system_config(key, value, updated_at) VALUES (?, ?, now())
                ON CONFLICT (key) DO UPDATE
                   SET value = GREATEST(system_config.value::double precision,
                                        EXCLUDED.value::double precision)::text,
                       updated_at = now()
                """, KEY, Double.toString(Math.min(CEILING, toAtLeast)));
        double after = threshold();
        if (after > before) {
            log.error("AUTO-CLEAR THRESHOLD RAISED {} -> {} after a confirmed false auto-clear", before, after);
        }
    }

    /**
     * Drops the ratchet back to the floor. Deliberately NOT wired into the calibrator's own
     * path — the ratchet is a safety mechanism (architecture-freeze Doubt 4) and must only
     * ever be reset by something that knows it is looking at a fresh scenario, not by anything
     * in the production decision path. Called by {@code SimulationService.reset()} for exactly
     * that reason: without it, a false auto-clear a demo scenario deliberately provokes would
     * silently change every later run's threshold, which is correct calibrator behaviour but
     * indistinguishable from non-determinism unless something explicitly clears it back.
     */
    public void resetToFloor() {
        jdbc.update("DELETE FROM system_config WHERE key = ?", KEY);
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
