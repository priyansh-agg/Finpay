package com.finpay.gateway.analyst;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Watches the auto-cleared set against the (now-resolved) hidden ground truth. If ANY
 * auto-cleared payment is confirmed fraudulent, it:
 *  <ul>
 *    <li>logs it as a critical failure, and</li>
 *    <li>raises the auto-clear confidence threshold above the confidence that mistake had,
 *        so the same class of mistake can't recur.</li>
 *  </ul>
 * This is the concrete "zero false auto-clear" enforcement loop.
 */
@Component
@Profile("!phase1")
public class AutoClearCalibrator {

    private static final Logger log = LoggerFactory.getLogger(AutoClearCalibrator.class);

    private final JdbcTemplate jdbc;
    private final AutoClearPolicy policy;

    public AutoClearCalibrator(JdbcTemplate jdbc, AutoClearPolicy policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    @Scheduled(fixedDelayString = "${finpay.autoclear.calibrate-ms:15000}")
    public void calibrate() {
        List<Double> badConfidences = jdbc.queryForList("""
                SELECT confidence FROM autoclear_decisions
                WHERE auto_cleared = true AND later_confirmed_fraud = true
                """, Double.class);
        if (badConfidences.isEmpty()) return;

        double worst = badConfidences.stream().mapToDouble(Double::doubleValue).max().orElse(0.97);
        // put the threshold a margin above the highest confidence that produced a false clear
        policy.ratchetUp(Math.min(0.999, worst + 0.02));
        log.error("{} false auto-clear(s) detected (max confidence {}). Threshold now {}.",
                badConfidences.size(), worst, policy.threshold());
    }
}
