package com.finpay.gateway.common.ratelimit;

import com.finpay.gateway.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "finpay.ratelimit.capacity=5",
        "finpay.ratelimit.refill-per-second=0",
})
class RateLimitTest extends AbstractIntegrationTest {

    @Autowired RateLimiter limiter;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void clearBuckets() {
        var keys = redis.keys("finpay:rl:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void bucketAllowsCapacityThenRejects() {
        String caller = "test-caller-" + System.nanoTime();
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(caller).allowed())
                    .as("request %d within capacity", i).isTrue();
        }
        assertThat(limiter.tryAcquire(caller).allowed()).as("6th request over capacity").isFalse();
    }

    @Test
    void separateCallersHaveSeparateBuckets() {
        String a = "caller-a-" + System.nanoTime();
        String b = "caller-b-" + System.nanoTime();
        for (int i = 0; i < 5; i++) limiter.tryAcquire(a);
        assertThat(limiter.tryAcquire(a).allowed()).isFalse();
        assertThat(limiter.tryAcquire(b).allowed()).isTrue();
    }
}
