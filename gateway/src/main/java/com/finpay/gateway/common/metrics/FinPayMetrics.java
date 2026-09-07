package com.finpay.gateway.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Business metrics exposed at {@code /actuator/prometheus}. Deliberately small — the plan
 * says a working ledger beats a pretty dashboard, so this is the "minimum bar" set:
 * payment outcomes, risk decisions, webhook delivery health, auto-clear rate, risk latency.
 */
@Component
public class FinPayMetrics {

    private final MeterRegistry reg;

    public FinPayMetrics(MeterRegistry reg) {
        this.reg = reg;
    }

    /** result: captured | failed | held | voided | review */
    public void paymentOutcome(String result) {
        reg.counter("finpay.payments.total", "result", result).increment();
    }

    /** decision: ALLOW | REVIEW | BLOCK ; bucket: LOW | MEDIUM | HIGH | CRITICAL */
    public void riskDecision(String decision, String bucket) {
        reg.counter("finpay.risk.decisions.total", "decision", decision, "bucket", bucket).increment();
    }

    public void riskLatency(Duration d) {
        Timer.builder("finpay.risk.latency").publishPercentiles(0.5, 0.95, 0.99)
                .register(reg).record(d);
    }

    /** status: delivered | failed | dead */
    public void webhookDelivery(String status) {
        reg.counter("finpay.webhook.deliveries.total", "status", status).increment();
    }

    public void autoClearDecision(boolean autoCleared) {
        reg.counter("finpay.autoclear.decisions.total", "auto_cleared", Boolean.toString(autoCleared)).increment();
    }

    public void falseAutoClear() {
        reg.counter("finpay.autoclear.false_clears.total").increment();
    }
}
