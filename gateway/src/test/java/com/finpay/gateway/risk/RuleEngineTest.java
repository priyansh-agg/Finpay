package com.finpay.gateway.risk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine();

    private RiskFeatures f(int vel5, int vel1h, int failed, long tenure, long avg, long amount,
                           boolean newDevice, boolean newIp, int devAccounts) {
        double dev = avg > 0 ? amount / (double) avg : 1.0;
        return new RiskFeatures(amount, "CARD", vel5, vel1h, failed, tenure, avg, dev, newDevice, newIp, devAccounts, 12, 0);
    }

    @Test
    void cleanTransactionFiresNoRules() {
        var r = engine.evaluate(f(1, 2, 0, 400, 50_000, 45_000, false, false, 1));
        assertThat(r.flags()).isEmpty();
        assertThat(r.score()).isZero();
    }

    @Test
    void velocityBurstFires() {
        var r = engine.evaluate(f(9, 12, 0, 400, 50_000, 45_000, false, false, 1));
        assertThat(r.flags()).extracting(RuleEngine.RuleFlag::code).contains("VELOCITY_5MIN");
        assertThat(r.score()).isGreaterThanOrEqualTo(30);
    }

    @Test
    void amountDeviationFires() {
        var r = engine.evaluate(f(1, 2, 0, 400, 20_000, 120_000, false, false, 1));
        assertThat(r.flags()).extracting(RuleEngine.RuleFlag::code).contains("AMOUNT_DEVIATION");
    }

    @Test
    void deviceRingFires() {
        var r = engine.evaluate(f(1, 2, 0, 400, 50_000, 45_000, true, false, 4));
        assertThat(r.flags()).extracting(RuleEngine.RuleFlag::code).contains("DEVICE_MANY_ACCOUNTS");
    }

    @Test
    void failedAttemptBurstFires() {
        var r = engine.evaluate(f(1, 2, 5, 400, 50_000, 45_000, false, false, 1));
        assertThat(r.flags()).extracting(RuleEngine.RuleFlag::code).contains("FAILED_ATTEMPT_BURST");
    }

    @Test
    void scoreIsCappedAt100() {
        var r = engine.evaluate(f(20, 40, 9, 1, 10_000, 500_000, true, true, 8));
        assertThat(r.score()).isLessThanOrEqualTo(100.0);
    }
}
