package com.finpay.gateway.settlement;

import com.finpay.gateway.sim.SimulationScenario;
import com.finpay.gateway.sim.SimulationService;
import com.finpay.gateway.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes the loop between interview sprint Day 7 and Day 9. Day 7 found that a stealth-fraud
 * transaction — built to carry zero rule signal, indistinguishable from a regular customer —
 * scores exactly 0 with no ML sidecar and auto-CAPTURES directly via the confidently-LOW
 * shortcut, never touching the review queue, the reference analyst, or {@code
 * autoclear_decisions}. The existing false-auto-clear metric literally cannot see that loss,
 * because the money never took the path that metric watches. This test proves what actually
 * stops it from reaching the merchant: not that metric, but settlement holdback (Day 9) — the
 * capture sits un-payable until its ground truth resolves, and a confirmed-fraud capture is
 * written off, at the settlement step, whatever path captured it.
 */
class SettlementHoldbackClosesStealthFraudTest extends AbstractIntegrationTest {

    @Autowired SimulationService sim;
    @Autowired SettlementService settlements;

    @Test
    void stealthFraud_bypassesReview_butIsWrittenOff_beforeItEverReachesTheMerchant() {
        SimulationScenario scenario = new SimulationScenario(60, 0.3, 1.0, 0.0, 4242L);
        SimulationService.SimSummary summary = sim.run(scenario, "test");
        UUID runId = summary.runId();

        long stealthCaptured = stealthCountWhere(runId, "p.status = 'CAPTURED'");
        assertThat(stealthCaptured).as("the scenario actually drew some stealth fraud that auto-captured")
                .isPositive();

        // A batch run the instant it captures pays out nothing for this run — everything just
        // captured is still within its holdback window, stealth fraud included.
        SettlementService.BatchResult tooSoon = settlements.runBatch();
        assertThat(tooSoon.writtenOffCount()).isZero();
        assertThat(stealthCountWhere(runId, "p.settled_at IS NOT NULL"))
                .as("nothing has been paid out yet").isZero();

        // Force what the wall clock (the simulated dispute window) and the settlement scheduler
        // would do on their own: resolve ground truth, then run the next settlement batch.
        sim.resolve(runId);
        SettlementService.BatchResult r = settlements.runBatch();

        assertThat(r.writtenOffCount())
                .as("every confirmed-fraud stealth capture is written off, not just proposed for review")
                .isGreaterThanOrEqualTo(1);

        long confirmedFraudCount = stealthCountWhere(runId, "g.resolved = true AND g.actually_fraudulent = true");
        long stillUnsettled = stealthCountWhere(runId, "g.resolved = true AND g.actually_fraudulent = true AND p.settled_at IS NULL");
        long writtenOff = stealthCountWhere(runId, "g.resolved = true AND g.actually_fraudulent = true AND p.written_off_at IS NOT NULL");

        assertThat(stillUnsettled).as("not one confirmed-fraud stealth capture is ever settled to the merchant")
                .isEqualTo(confirmedFraudCount);
        assertThat(writtenOff).as("and it's written off, not just silently stuck")
                .isEqualTo(confirmedFraudCount);
    }

    private long stealthCountWhere(UUID runId, String condition) {
        String sql = "SELECT count(*) FROM payments p JOIN fraud_ground_truth g ON g.payment_id = p.id "
                + "WHERE p.run_id = ? AND g.scenario_pattern = 'stealth' AND " + condition;
        Long n = jdbc.queryForObject(sql, Long.class, runId);
        return n == null ? 0 : n;
    }
}
