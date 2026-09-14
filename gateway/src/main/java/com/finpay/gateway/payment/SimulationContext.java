package com.finpay.gateway.payment;

import java.util.UUID;

/**
 * Marks a payment as belonging to a simulation run, and whether it is part of the requested
 * population or scenery the harness built to produce it (a customer's seeded transaction
 * history, the warm-up burst before a velocity-abuse pattern, and so on).
 *
 * <p>Only the simulation harness constructs one of these — real traffic's
 * {@link PaymentService.CreatePaymentCommand} carries {@code null} here, same as it does for
 * {@link com.finpay.gateway.groundtruth.GroundTruthLabel}. The two null-together, because
 * they answer related questions from the same place: "is this fraud?" and "does this count
 * towards what was asked for?" are both properties of who generated the transaction, not of
 * the transaction itself.
 *
 * @param runId   which {@code simulation_runs} row this payment belongs to
 * @param warmup  true for scenery (history seeding, pattern warm-ups) that should never be
 *                counted in a run's reported population or fed into an auto-clear metric
 */
public record SimulationContext(UUID runId, boolean warmup) {

    public SimulationContext {
        if (runId == null) {
            throw new IllegalArgumentException("a SimulationContext must name the run it belongs to");
        }
    }

    public static SimulationContext population(UUID runId) {
        return new SimulationContext(runId, false);
    }

    public static SimulationContext warmup(UUID runId) {
        return new SimulationContext(runId, true);
    }
}
