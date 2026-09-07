package com.finpay.gateway.risk;

import com.finpay.gateway.risk.api.RiskGate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Phase 1 placeholder: every payment is treated as LOW risk / ALLOW.
 * Active only under the {@code phase1} profile; otherwise the real {@link RiskEngine} is used.
 */
@Component
@Profile("phase1")
public class AllowAllRiskGate implements RiskGate {
    @Override
    public RiskResult evaluate(RiskContext ctx) {
        return RiskResult.allowLow();
    }
}
