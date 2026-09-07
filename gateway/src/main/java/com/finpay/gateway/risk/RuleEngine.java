package com.finpay.gateway.risk;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, explainable rule layer. Runs synchronously before the processor call.
 * Each fired rule contributes a weight; {@link #score} sums them, capped at 100.
 * Rules are drawn directly from the project plan §8.
 */
@Component
public class RuleEngine {

    public record RuleFlag(String code, double weight, String detail) {}

    public record RuleResult(List<RuleFlag> flags, double score) {}

    // amount thresholds are in minor units (paise); 3_00_000 = ₹3,000
    private static final long NEW_DEVICE_AMOUNT_THRESHOLD = 300_000;

    public RuleResult evaluate(RiskFeatures f) {
        List<RuleFlag> flags = new ArrayList<>();

        if (f.velocity5m() > 5) {
            flags.add(new RuleFlag("VELOCITY_5MIN", 30,
                    f.velocity5m() + " payments in the last 5 minutes"));
        }
        if (f.velocity1h() > 15) {
            flags.add(new RuleFlag("VELOCITY_1HR", 15,
                    f.velocity1h() + " payments in the last hour"));
        }
        if (f.customerRollingAvgMinor() > 0 && f.amountDeviationRatio() > 3.0) {
            flags.add(new RuleFlag("AMOUNT_DEVIATION", 25, String.format(
                    "amount is %.1fx the customer's rolling average", f.amountDeviationRatio())));
        }
        if (f.deviceIsNew() && f.amountMinor() > NEW_DEVICE_AMOUNT_THRESHOLD) {
            flags.add(new RuleFlag("NEW_DEVICE_HIGH_AMOUNT", 25,
                    "new device + amount above threshold"));
        }
        if (f.deviceIsNew() && f.ipIsNew() && f.amountMinor() > 150_000) {
            flags.add(new RuleFlag("NEW_DEVICE_NEW_IP", 20,
                    "unrecognised device and IP on a non-trivial amount"));
        }
        if (f.failedAttemptsLastHour() > 3) {
            flags.add(new RuleFlag("FAILED_ATTEMPT_BURST", 20,
                    f.failedAttemptsLastHour() + " failed attempts in the last hour then a success"));
        }
        if (f.distinctAccountsForDevice() >= 3) {
            flags.add(new RuleFlag("DEVICE_MANY_ACCOUNTS", 30,
                    "device used across " + f.distinctAccountsForDevice() + " distinct accounts"));
        }
        if (f.ipIsNew() && f.deviceIsNew() && f.customerTenureDays() < 7) {
            flags.add(new RuleFlag("NEW_EVERYTHING", 15,
                    "new IP + new device + customer < 7 days old"));
        }

        double score = Math.min(100.0, flags.stream().mapToDouble(RuleFlag::weight).sum());
        return new RuleResult(flags, score);
    }
}
