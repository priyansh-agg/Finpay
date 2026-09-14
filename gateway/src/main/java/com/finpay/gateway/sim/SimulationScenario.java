package com.finpay.gateway.sim;

import com.finpay.gateway.common.error.ApiException;

/**
 * A requested simulation, validated and self-contained enough to be stored verbatim
 * (see {@link SimulationRun#getScenario()}) and reproduced later from the row alone.
 *
 * <p>Day 6 (runs and provenance) only drove {@code count}, {@code fraudRate} and {@code seed}.
 * Day 7 adds the two populations that make the Auto-Clear USP measurable rather than assumed:
 *
 * <ul>
 *   <li>{@code stealthRate} — among FRAUD draws, how many are crafted to carry no rule signal
 *       at all: known device, known IP, an amount near the customer's own average. These are
 *       the only population "zero false auto-clears" is a meaningful claim about — the four
 *       original patterns all trip a rule by design, so none of them was ever actually
 *       eligible to be auto-cleared in the first place (see {@code docs/AUDIT.md} §D.C).</li>
 *   <li>{@code fraudShapedLegitRate} — among LEGIT draws, how many are genuine customers who
 *       nonetheless trip a rule (an established customer buying from a new device and a new
 *       IP while travelling, at a real, unremarkable-for-them-but-rule-tripping amount). This
 *       is the population "manual review reduction" is measured over.</li>
 * </ul>
 */
public record SimulationScenario(int count, double fraudRate, double stealthRate,
                                 double fraudShapedLegitRate, long seed) {

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
        if (stealthRate < 0 || stealthRate > 1) {
            throw ApiException.validation("stealthRate must be between 0 and 1");
        }
        if (fraudShapedLegitRate < 0 || fraudShapedLegitRate > 1) {
            throw ApiException.validation("fraudShapedLegitRate must be between 0 and 1");
        }
    }

    public static final int DEFAULT_COUNT = 200;
    public static final double DEFAULT_FRAUD_RATE = 0.12;
    public static final double DEFAULT_STEALTH_RATE = 0.0;
    public static final double DEFAULT_FRAUD_SHAPED_LEGIT_RATE = 0.0;

    /**
     * @param seed if null, a fresh one is drawn so every unseeded call is still reproducible from its own row.
     */
    public static SimulationScenario of(Integer count, Double fraudRate, Double stealthRate,
                                        Double fraudShapedLegitRate, Long seed) {
        return new SimulationScenario(
                count == null ? DEFAULT_COUNT : count,
                fraudRate == null ? DEFAULT_FRAUD_RATE : fraudRate,
                stealthRate == null ? DEFAULT_STEALTH_RATE : stealthRate,
                fraudShapedLegitRate == null ? DEFAULT_FRAUD_SHAPED_LEGIT_RATE : fraudShapedLegitRate,
                seed == null ? System.nanoTime() : seed);
    }
}
