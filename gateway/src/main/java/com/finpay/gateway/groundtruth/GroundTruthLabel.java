package com.finpay.gateway.groundtruth;

/**
 * The hidden truth about a generated transaction, as stated by whoever generated it.
 *
 * <p>Only the simulation harness constructs these. The public API and hosted checkout build
 * their payment commands from request DTOs that have no such field, so an outside caller has
 * no way to author a fraud label — which is the point: the label used to be derived from a
 * card token that any tester could pick on the public checkout page.
 *
 * @param intendedClass   what the transaction really is
 * @param scenarioPattern the generator's name for the pattern that produced it
 * @param disguiseLevel   0..1 — how much it looks like the OTHER class. 0 means it looks
 *                        exactly like what it is; stealth fraud and fraud-shaped legitimate
 *                        traffic sit near 1. These are the populations that make an
 *                        auto-clear metric mean anything.
 */
public record GroundTruthLabel(IntendedClass intendedClass, String scenarioPattern, double disguiseLevel) {

    public enum IntendedClass { LEGIT, FRAUD }

    public GroundTruthLabel {
        if (intendedClass == null) {
            throw new IllegalArgumentException("a ground-truth label needs a class");
        }
        if (scenarioPattern == null || scenarioPattern.isBlank()) {
            throw new IllegalArgumentException("a ground-truth label must name the pattern that produced it");
        }
        if (disguiseLevel < 0 || disguiseLevel > 1) {
            throw new IllegalArgumentException("disguiseLevel must be within [0, 1], was " + disguiseLevel);
        }
    }

    /** Legitimate traffic that looks legitimate. */
    public static GroundTruthLabel legit(String scenarioPattern) {
        return new GroundTruthLabel(IntendedClass.LEGIT, scenarioPattern, 0.0);
    }

    public static GroundTruthLabel fraud(String scenarioPattern, double disguiseLevel) {
        return new GroundTruthLabel(IntendedClass.FRAUD, scenarioPattern, disguiseLevel);
    }

    public boolean isFraud() {
        return intendedClass == IntendedClass.FRAUD;
    }
}
