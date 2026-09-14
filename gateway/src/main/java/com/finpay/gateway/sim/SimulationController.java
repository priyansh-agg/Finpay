package com.finpay.gateway.sim;

import com.finpay.gateway.auth.CurrentCaller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** SUPER_ADMIN-only: generate a batch of realistic traffic for the Phase-3 evaluation. */
@RestController
@RequestMapping("/v1/admin/simulate")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SimulationController {

    private final SimulationService sim;

    public SimulationController(SimulationService sim) {
        this.sim = sim;
    }

    public record SimRequest(Integer count, Double fraudRate, Long seed) {}

    @PostMapping
    public SimulationService.SimSummary simulate(@RequestBody(required = false) SimRequest req) {
        SimulationScenario scenario = SimulationScenario.of(
                req == null ? null : req.count(),
                req == null ? null : req.fraudRate(),
                req == null ? null : req.seed());
        return sim.run(scenario, CurrentCaller.get().subjectId());
    }
}
