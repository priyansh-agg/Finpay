package com.finpay.gateway.sim;

import com.finpay.gateway.common.error.ApiException;

/**
 * A requested simulation, validated and self-contained enough to be stored verbatim
 * (see {@link SimulationRun#getScenario()}) and reproduced later from the row alone.
 *
 * <p>Deliberately minimal today: a population size and an overall fraud rate, the two things
 * this stage of the interview sprint (Day 6 — runs and provenance) actually drives. Day 7
 * extends generation to two further populations — fraud disguised to look legitimate, and
 * legitimate traffic that looks suspicious — which is a property of the generator, not of
 * this request shape; that work adds fields here rather than leaving unused ones now.
 *
 * @param count     population size: how many scored transactions to generate. Does not
 *                  include warm-up transactions (customer history, pattern scene-setting) —
 *                  those are generated as needed and tagged separately (V16).
 * @param fraudRate probability that any one generated transaction is fraud, 0..1
 * @param seed      the PRNG seed. Same seed, same scenario shape ⇒ the same transactions,
 *                  in the same order — see {@link SimulationRun}.
 */
public record SimulationScenario(int count, double fraudRate, long seed) {

    public SimulationScenario {
        if (count <= 0) {
            throw ApiException.validation("count must be positive");
        }
        if (count > 50_000) {
            throw ApiException.validation("count must be 50,000 or fewer per run");
        }
        if (fraudRate < 0 || fraudRate > 1) {
            throw ApiException.validation("fraudRate must be between 0 and 1");
        }
    }

    public static final int DEFAULT_COUNT = 200;
    public static final double DEFAULT_FRAUD_RATE = 0.12;

    /** @param seed if null, a fresh one is drawn so every unseeded call is still reproducible from its own row. */
    public static SimulationScenario of(Integer count, Double fraudRate, Long seed) {
        return new SimulationScenario(
                count == null ? DEFAULT_COUNT : count,
                fraudRate == null ? DEFAULT_FRAUD_RATE : fraudRate,
                seed == null ? System.nanoTime() : seed);
    }
}
