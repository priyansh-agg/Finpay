package com.finpay.gateway.sim;

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
        int count = req == null || req.count() == null ? 200 : req.count();
        double fraudRate = req == null || req.fraudRate() == null ? 0.12 : req.fraudRate();
        long seed = req == null || req.seed() == null ? System.nanoTime() : req.seed();
        return sim.run(count, fraudRate, seed);
    }
}
