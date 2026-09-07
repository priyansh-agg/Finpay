package com.finpay.gateway.analyst;

import com.finpay.gateway.risk.RiskFeatures;
import org.springframework.stereotype.Component;

/**
 * The rule-based "reference analyst" (architecture-freeze Doubt 3). Deterministic so the
 * Phase-3 experiment is reproducible. Its decisions are the SILVER LABELS the Auto-Clear
 * Engine trains on. The hidden {@code actually_fraudulent} ground truth is NEVER an input
 * here — it is used only to evaluate the Auto-Clear Engine afterwards.
 *
 * <p>Model: fraud alerts are overwhelmingly false positives (industry FP rates on fraud
 * flags are commonly 90%+), so the analyst CLEARS by default and only REJECTS on:
 * <ul>
 *   <li>a very high risk score (&ge; 78),</li>
 *   <li>a prior confirmed-fraud flag on the customer, or</li>
 *   <li>a concrete adverse pattern (velocity burst, failed-attempt burst, device shared
 *       across many accounts, new device + large deviation, large deviation from the
 *       customer's norm, new device + new IP on a young account).</li>
 * </ul>
 * An extra caution band (60&ndash;78) also rejects unless the customer is well established.
 */
@Component
public class ReferenceAnalyst {

    public enum Decision { CLEAR, REJECT }

    public record Verdict(Decision decision, String reason) {}

    public Verdict decide(double riskScore, RiskFeatures f, boolean priorFraudFlags) {
        if (riskScore >= 78) {
            return new Verdict(Decision.REJECT, "risk score >= 78");
        }
        if (priorFraudFlags) {
            return new Verdict(Decision.REJECT, "customer has a prior confirmed-fraud flag");
        }
        // A human analyst would reject these patterns regardless of the numeric score.
        if (f.velocity5m() > 5) {
            return new Verdict(Decision.REJECT, f.velocity5m() + " payments in 5 minutes");
        }
        if (f.failedAttemptsLastHour() > 3) {
            return new Verdict(Decision.REJECT, "failed-attempt burst before this success");
        }
        if (f.distinctAccountsForDevice() >= 3) {
            return new Verdict(Decision.REJECT, "device shared across many accounts");
        }
        if (f.deviceIsNew() && f.customerRollingAvgMinor() > 0
                && f.amountMinor() > 4L * f.customerRollingAvgMinor()) {
            return new Verdict(Decision.REJECT, "new device + amount far above the customer's norm");
        }
        if (f.customerRollingAvgMinor() > 0 && f.amountDeviationRatio() > 3.0) {
            return new Verdict(Decision.REJECT, String.format(
                    "amount is %.1fx the customer's typical spend", f.amountDeviationRatio()));
        }
        // "New everything" is only adverse when paired with a meaningful amount — otherwise
        // it is just a genuinely new customer's first small purchase.
        if (f.deviceIsNew() && f.ipIsNew() && f.customerTenureDays() < 30 && f.amountMinor() > 80_000) {
            return new Verdict(Decision.REJECT, "new device + new IP + young account on a meaningful amount");
        }
        if (f.deviceIsNew() && f.ipIsNew() && f.amountMinor() > 200_000) {
            return new Verdict(Decision.REJECT, "new device + new IP on a large amount");
        }
        // Caution band: a fairly high score with no established history still gets a human.
        boolean tenured = f.customerTenureDays() > 90;
        if (riskScore >= 60 && !tenured) {
            return new Verdict(Decision.REJECT, String.format(
                    "elevated risk %.0f on a customer under 90 days old", riskScore));
        }
        // No adverse pattern, score below the caution band (or established customer):
        // treat as a false positive and clear it.
        return new Verdict(Decision.CLEAR, String.format(
                "no adverse pattern; risk %.0f treated as a false positive", riskScore));
    }
}
