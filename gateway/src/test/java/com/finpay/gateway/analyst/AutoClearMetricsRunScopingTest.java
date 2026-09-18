package com.finpay.gateway.analyst;

import com.finpay.gateway.sim.SimulationScenario;
import com.finpay.gateway.sim.SimulationService;
import com.finpay.gateway.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Run-scoped Auto-Clear metrics (interview sprint Day 13 — the audit's §G.6: "add a run
 * selector and use the ?since=/?run= scoping the backend already supports"). Before this,
 * {@code compute()} only ever answered "since some timestamp," never "for this one run" — a
 * demo showing two scenarios back to back had no way to look at just the second one.
 */
class AutoClearMetricsRunScopingTest extends AbstractIntegrationTest {

    @Autowired SimulationService sim;
    @Autowired AutoClearMetricsService metrics;
    @Autowired AutoClearPolicy policy;

    @Test
    void runIdScopesMetrics_toExactlyThatRunsOwnReviews_notTheOtherRunsToo() {
        SimulationScenario scenarioA = new SimulationScenario(40, 0.3, 0.0, 0.0, 111L);
        SimulationService.SimSummary runA = sim.run(scenarioA, "test");
        SimulationScenario scenarioB = new SimulationScenario(40, 0.3, 0.0, 0.0, 222L);
        SimulationService.SimSummary runB = sim.run(scenarioB, "test");

        long flaggedA = count("SELECT count(DISTINCT payment_id) FROM analyst_reviews WHERE run_id = ?", runA.runId());
        long flaggedB = count("SELECT count(DISTINCT payment_id) FROM analyst_reviews WHERE run_id = ?", runB.runId());
        assertThat(flaggedA).as("scenario A actually flagged some payments").isPositive();
        assertThat(flaggedB).as("scenario B actually flagged some payments").isPositive();

        AutoClearMetricsService.Metrics scopedToA = metrics.compute(policy.threshold(), null, runA.runId());
        AutoClearMetricsService.Metrics scopedToB = metrics.compute(policy.threshold(), null, runB.runId());
        AutoClearMetricsService.Metrics global = metrics.compute(policy.threshold(), null, null);

        assertThat(scopedToA.flaggedTotal()).isEqualTo(flaggedA);
        assertThat(scopedToB.flaggedTotal()).isEqualTo(flaggedB);
        assertThat(global.flaggedTotal()).as("unscoped still sees both runs combined")
                .isGreaterThanOrEqualTo(flaggedA + flaggedB);
        assertThat(scopedToA.flaggedTotal()).as("run A's own scope never leaks run B's rows")
                .isLessThan(global.flaggedTotal());
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
