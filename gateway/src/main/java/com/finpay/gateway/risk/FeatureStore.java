package com.finpay.gateway.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis-backed real-time feature store. Velocity / failed-attempt counters live here (fast,
 * TTL'd) rather than in Postgres so the synchronous risk gate stays well under its latency
 * budget. Slower features (customer tenure, rolling average) come from Postgres.
 *
 * <p>Every operation degrades gracefully: if Redis is unavailable the risk engine simply
 * scores with weaker velocity signal rather than failing the payment.
 */
@Component
public class FeatureStore {

    private static final Logger log = LoggerFactory.getLogger(FeatureStore.class);
    private static final Duration WINDOW_TTL = Duration.ofHours(2);

    private final StringRedisTemplate redis;

    public FeatureStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String velKey(UUID c) { return "finpay:vel:" + c; }
    private String failKey(UUID c) { return "finpay:fail:" + c; }
    private String devKey(String fp) { return "finpay:dev:" + fp; }
    private String ipKey(UUID c) { return "finpay:ip:" + c; }

    // --- writes ---

    public void recordPaymentAttempt(UUID customerId, UUID paymentId) {
        safeRun(() -> {
            long now = System.currentTimeMillis();
            redis.opsForZSet().add(velKey(customerId), paymentId.toString(), now);
            redis.expire(velKey(customerId), WINDOW_TTL);
            redis.opsForZSet().removeRangeByScore(velKey(customerId), 0, now - Duration.ofHours(1).toMillis());
        });
    }

    public void recordFailedAttempt(UUID customerId, UUID paymentId) {
        safeRun(() -> {
            long now = System.currentTimeMillis();
            redis.opsForZSet().add(failKey(customerId), paymentId.toString(), now);
            redis.expire(failKey(customerId), WINDOW_TTL);
            redis.opsForZSet().removeRangeByScore(failKey(customerId), 0, now - Duration.ofHours(1).toMillis());
        });
    }

    /** @return true if this IP was already seen for the customer. */
    public boolean seenIp(UUID customerId, String ip) {
        if (ip == null || ip.isBlank()) return true;
        return safeGet(() -> {
            Long added = redis.opsForSet().add(ipKey(customerId), ip);
            redis.expire(ipKey(customerId), Duration.ofDays(30));
            return added == null || added == 0L;
        }, true);
    }

    public int registerDeviceAccount(String fingerprint, UUID customerId) {
        if (fingerprint == null || fingerprint.isBlank()) return 1;
        return safeGet(() -> {
            redis.opsForSet().add(devKey(fingerprint), customerId.toString());
            redis.expire(devKey(fingerprint), Duration.ofDays(30));
            Long card = redis.opsForSet().size(devKey(fingerprint));
            return card == null ? 1 : card.intValue();
        }, 1);
    }

    // --- reads ---

    public int velocity(UUID customerId, Duration window) {
        return safeGet(() -> {
            long from = System.currentTimeMillis() - window.toMillis();
            Long c = redis.opsForZSet().count(velKey(customerId), from, Double.MAX_VALUE);
            return c == null ? 0 : c.intValue();
        }, 0);
    }

    public int failedAttempts(UUID customerId, Duration window) {
        return safeGet(() -> {
            long from = System.currentTimeMillis() - window.toMillis();
            Long c = redis.opsForZSet().count(failKey(customerId), from, Double.MAX_VALUE);
            return c == null ? 0 : c.intValue();
        }, 0);
    }

    /** Test/simulation helper: forget a customer's recent-velocity + failed-attempt history. */
    public void resetVelocity(UUID customerId) {
        safeRun(() -> redis.delete(java.util.List.of(velKey(customerId), failKey(customerId))));
    }

    public int distinctAccountsForDevice(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) return 1;
        return safeGet(() -> {
            Long card = redis.opsForSet().size(devKey(fingerprint));
            return card == null ? 0 : card.intValue();
        }, 0);
    }

    // --- safety wrappers ---

    private void safeRun(Runnable r) {
        try { r.run(); } catch (Exception e) { log.debug("feature-store write skipped: {}", e.toString()); }
    }

    private <T> T safeGet(Supplier<T> s, T fallback) {
        try { return s.get(); } catch (Exception e) {
            log.debug("feature-store read fell back: {}", e.toString());
            return fallback;
        }
    }
}
