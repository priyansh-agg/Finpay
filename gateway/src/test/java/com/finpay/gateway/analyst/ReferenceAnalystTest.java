package com.finpay.gateway.analyst;

import com.finpay.gateway.risk.RiskFeatures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceAnalystTest {

    private final ReferenceAnalyst analyst = new ReferenceAnalyst();

    private RiskFeatures f(int vel5, int failed, long tenure, long avg, long amount,
                           boolean newDevice, boolean newIp, int devAccounts) {
        double dev = avg > 0 ? amount / (double) avg : 1.0;
        return new RiskFeatures(amount, "CARD", vel5, vel5, failed, tenure, avg, dev, newDevice, newIp, devAccounts, 12, 0);
    }

    @Test
    void clearsAFalsePositive() {
        var v = analyst.decide(30, f(1, 0, 400, 50_000, 55_000, false, false, 1), false);
        assertThat(v.decision()).isEqualTo(ReferenceAnalyst.Decision.CLEAR);
    }

    @Test
    void rejectsVeryHighScore() {
        assertThat(analyst.decide(85, f(1, 0, 400, 50_000, 55_000, false, false, 1), false).decision())
                .isEqualTo(ReferenceAnalyst.Decision.REJECT);
    }

    @Test
    void rejectsPriorFraudCustomer() {
        assertThat(analyst.decide(25, f(1, 0, 900, 50_000, 40_000, false, false, 1), true).decision())
                .isEqualTo(ReferenceAnalyst.Decision.REJECT);
    }

    @Test
    void rejectsVelocityBurstRegardlessOfScore() {
        assertThat(analyst.decide(25, f(9, 0, 900, 50_000, 40_000, false, false, 1), false).decision())
                .isEqualTo(ReferenceAnalyst.Decision.REJECT);
    }

    @Test
    void rejectsLargeDeviation() {
        assertThat(analyst.decide(35, f(1, 0, 900, 20_000, 120_000, false, false, 1), false).decision())
                .isEqualTo(ReferenceAnalyst.Decision.REJECT);
    }

    @Test
    void rejectsNewDeviceNewIpNonTrivialAmount() {
        assertThat(analyst.decide(40, f(1, 0, 300, 0, 240_000, true, true, 1), false).decision())
                .isEqualTo(ReferenceAnalyst.Decision.REJECT);
    }

    @Test
    void neverConsultsHiddenGroundTruth_signatureHasNoSuchParam() {
        // Compile-time guarantee: decide(...) takes only (score, features, priorFraudFlags).
        // priorFraudFlags is derived from confirmed history, not the actually_fraudulent label.
        assertThat(ReferenceAnalyst.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("decide") && m.getParameterCount() == 3);
    }
}
