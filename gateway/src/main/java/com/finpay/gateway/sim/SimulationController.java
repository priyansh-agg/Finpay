package com.finpay.gateway.sim;

import com.finpay.gateway.auth.CurrentCaller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** SUPER_ADMIN-only: generate, and manage, batches of realistic traffic for the Phase-3 evaluation. */
@RestController
@RequestMapping("/v1/admin/simulate")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SimulationController {

    private final SimulationService sim;

    public SimulationController(SimulationService sim) {
        this.sim = sim;
    }

    public record SimRequest(Integer count, Double fraudRate, Double stealthRate,
                             Double fraudShapedLegitRate, Long seed) {}

    @PostMapping
    public SimulationService.SimSummary simulate(@RequestBody(required = false) SimRequest req) {
        SimulationScenario scenario = SimulationScenario.of(
                req == null ? null : req.count(),
                req == null ? null : req.fraudRate(),
                req == null ? null : req.stealthRate(),
                req == null ? null : req.fraudShapedLegitRate(),
                req == null ? null : req.seed());
        return sim.run(scenario, CurrentCaller.get().subjectId());
    }

    /**
     * Forces this run's flagged payments through a decision and its ground truth through
     * resolution right now, and returns the refreshed report — for a demo, or a test, that
     * shouldn't have to wait on the simulated dispute window or the review-queue scheduler.
     */
    @PostMapping("/{runId}/resolve")
    public SimulationService.SimSummary resolve(@PathVariable UUID runId) {
        return sim.resolve(runId);
    }

    /**
     * Clears the Redis feature store and the auto-clear threshold's ratchet. Call this before
     * re-running the same seed and expecting the same numbers — see {@link SimulationService#reset()}.
     */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        int cleared = sim.reset();
        return Map.of("redisKeysCleared", cleared, "autoClearThresholdResetToFloor", true);
    }
}
