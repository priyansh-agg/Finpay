package com.finpay.gateway.risk;

import com.finpay.gateway.identity.Customer;
import com.finpay.gateway.identity.CustomerRepository;
import com.finpay.gateway.identity.DeviceRepository;
import com.finpay.gateway.risk.api.RiskGate.RiskContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Component
public class FeatureExtractor {

    private final FeatureStore store;
    private final CustomerRepository customers;
    private final CustomerPaymentStatsRepository stats;
    private final DeviceRepository devices;

    public FeatureExtractor(FeatureStore store, CustomerRepository customers,
                            CustomerPaymentStatsRepository stats, DeviceRepository devices) {
        this.store = store;
        this.customers = customers;
        this.stats = stats;
        this.devices = devices;
    }

    public RiskFeatures extract(RiskContext ctx) {
        int vel5 = store.velocity(ctx.customerId(), Duration.ofMinutes(5));
        int vel60 = store.velocity(ctx.customerId(), Duration.ofHours(1));
        int failed = store.failedAttempts(ctx.customerId(), Duration.ofHours(1));

        long tenureDays = customers.findById(ctx.customerId())
                .map(Customer::getFirstSeenAt)
                .map(seen -> ChronoUnit.DAYS.between(seen, Instant.now()))
                .orElse(0L);

        long rollingAvg = stats.findById(ctx.customerId())
                .map(CustomerPaymentStats::getRollingAvgMinor)
                .orElse(0L);
        // No spending baseline yet => deviation is unknown, not "infinite". Use 1.0 (neutral)
        // so a customer's very first payment isn't auto-flagged as a huge anomaly.
        double deviation = rollingAvg > 0 ? ctx.amountMinor() / (double) rollingAvg : 1.0;

        boolean deviceNew = ctx.deviceFingerprint() == null || ctx.deviceFingerprint().isBlank()
                || devices.findByCustomerIdAndFingerprint(ctx.customerId(), ctx.deviceFingerprint()).isEmpty();
        boolean ipNew = !store.seenIp(ctx.customerId(), ctx.ipAddress());
        int distinctAccountsForDevice = store.distinctAccountsForDevice(ctx.deviceFingerprint());

        int hour = Instant.now().atZone(ZoneOffset.UTC).getHour();

        return new RiskFeatures(
                ctx.amountMinor(), ctx.method(), vel5, vel60, failed, tenureDays, rollingAvg,
                deviation, deviceNew, ipNew, distinctAccountsForDevice, hour, 0.0);
    }
}
