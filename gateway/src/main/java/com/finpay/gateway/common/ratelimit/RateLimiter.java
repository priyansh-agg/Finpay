package com.finpay.gateway.common.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis token-bucket rate limiter (plan §10). One bucket per key (an API key id or a
 * dashboard user id). The refill + take is a single atomic Lua eval so concurrent requests
 * on the same key can't over-draw.
 *
 * <p>Degrades OPEN: if Redis is unreachable, requests are allowed rather than blocked.
 */
@Component
public class RateLimiter {

    // KEYS[1]=bucket   ARGV[1]=capacity ARGV[2]=refillPerSec ARGV[3]=nowMs ARGV[4]=ttlSec
    private static final String LUA = """
        local b = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
        local cap = tonumber(ARGV[1])
        local refill = tonumber(ARGV[2])
        local now = tonumber(ARGV[3])
        local tokens = tonumber(b[1])
        local ts = tonumber(b[2])
        if tokens == nil then tokens = cap; ts = now end
        local delta = math.max(0, now - ts) / 1000.0
        tokens = math.min(cap, tokens + delta * refill)
        local allowed = 0
        if tokens >= 1 then tokens = tokens - 1; allowed = 1 end
        redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now)
        redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
        return { allowed, math.floor(tokens) }
        """;

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;
    private final int capacity;
    private final int refillPerSec;

    public RateLimiter(StringRedisTemplate redis,
                       @Value("${finpay.ratelimit.capacity:60}") int capacity,
                       @Value("${finpay.ratelimit.refill-per-second:20}") int refillPerSec) {
        this.redis = redis;
        this.capacity = capacity;
        this.refillPerSec = refillPerSec;
        this.script = new DefaultRedisScript<>(LUA, List.class);
    }

    public record Result(boolean allowed, long remaining) {}

    public Result tryAcquire(String key) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> r = redis.execute(script, List.of("finpay:rl:" + key),
                    String.valueOf(capacity), String.valueOf(refillPerSec),
                    String.valueOf(System.currentTimeMillis()), "120");
            if (r == null || r.size() < 2) return new Result(true, capacity);
            return new Result(asLong(r.get(0)) == 1L, asLong(r.get(1)));
        } catch (Exception e) {
            return new Result(true, capacity); // fail open
        }
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o));
    }

    public int capacity() {
        return capacity;
    }
}
