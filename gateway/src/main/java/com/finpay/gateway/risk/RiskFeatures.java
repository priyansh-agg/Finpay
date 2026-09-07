package com.finpay.gateway.risk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The feature snapshot for one payment, shared by the rule engine, the ML client, and the
 * pgvector encoder. All values are computed BEFORE the processor is called.
 */
public record RiskFeatures(
        long amountMinor,
        String method,
        int velocity5m,
        int velocity1h,
        int failedAttemptsLastHour,
        long customerTenureDays,
        long customerRollingAvgMinor,
        double amountDeviationRatio,   // amount / max(rollingAvg, 1)
        boolean deviceIsNew,
        boolean ipIsNew,
        int distinctAccountsForDevice,
        int hourOfDay,
        double avgSimilarityToKnownFraud  // set by the vector store; 0 if no history
) {
    public RiskFeatures withVectorSimilarity(double sim) {
        return new RiskFeatures(amountMinor, method, velocity5m, velocity1h, failedAttemptsLastHour,
                customerTenureDays, customerRollingAvgMinor, amountDeviationRatio, deviceIsNew, ipIsNew,
                distinctAccountsForDevice, hourOfDay, sim);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amountMinor", amountMinor);
        m.put("method", method);
        m.put("velocity5m", velocity5m);
        m.put("velocity1h", velocity1h);
        m.put("failedAttemptsLastHour", failedAttemptsLastHour);
        m.put("customerTenureDays", customerTenureDays);
        m.put("customerRollingAvgMinor", customerRollingAvgMinor);
        m.put("amountDeviationRatio", round(amountDeviationRatio));
        m.put("deviceIsNew", deviceIsNew);
        m.put("ipIsNew", ipIsNew);
        m.put("distinctAccountsForDevice", distinctAccountsForDevice);
        m.put("hourOfDay", hourOfDay);
        m.put("avgSimilarityToKnownFraud", round(avgSimilarityToKnownFraud));
        return m;
    }

    private static double round(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }
}
