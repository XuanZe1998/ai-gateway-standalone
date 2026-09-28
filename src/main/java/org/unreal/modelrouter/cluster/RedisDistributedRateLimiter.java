package org.unreal.modelrouter.cluster;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.router.ratelimit.RateLimitConfig;

/**
 * Atomic Redis token-bucket rate limiter shared by all gateway replicas.
 *
 * <p>The existing router rate limiter API is synchronous. Redis access is therefore
 * bounded by a short timeout. A Redis outage follows the configured fail-open policy.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "jairouter.cluster.rate-limit.enabled", havingValue = "true")
public class RedisDistributedRateLimiter {

    private static final long FAILURE_LOG_INTERVAL_MS = 30_000L;

    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT =
            new DefaultRedisScript<>("""
                    local bucket = KEYS[1]
                    local capacity = tonumber(ARGV[1])
                    local rate = tonumber(ARGV[2])
                    local requested = tonumber(ARGV[3])
                    local ttl = tonumber(ARGV[4])
                    local time = redis.call('TIME')
                    local now = (time[1] * 1000) + math.floor(time[2] / 1000)
                    local values = redis.call('HMGET', bucket, 'tokens', 'timestamp')
                    local tokens = tonumber(values[1])
                    local timestamp = tonumber(values[2])
                    if tokens == nil then
                      tokens = capacity
                      timestamp = now
                    end
                    local elapsed = math.max(0, now - timestamp)
                    tokens = math.min(capacity, tokens + (elapsed * rate / 1000))
                    local allowed = 0
                    if tokens >= requested then
                      tokens = tokens - requested
                      allowed = 1
                    end
                    redis.call('HSET', bucket, 'tokens', tokens, 'timestamp', now)
                    redis.call('PEXPIRE', bucket, ttl)
                    return allowed
                    """, Long.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ClusterProperties properties;
    private final AtomicLong lastFailureLog = new AtomicLong();

    public RedisDistributedRateLimiter(final ReactiveStringRedisTemplate redisTemplate,
                                       final ClusterProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * Attempts to consume tokens from a cluster-wide bucket.
     *
     * @param dimension stable logical bucket name
     * @param config rate limit configuration
     * @param requestedTokens token cost of the request
     * @return whether the request is allowed
     */
    public boolean tryAcquire(final String dimension,
                              final RateLimitConfig config,
                              final int requestedTokens) {
        if (config == null || !config.isEnabled()) {
            return true;
        }

        long capacity = Math.max(1L, config.getCapacity());
        long rate = Math.max(1L, config.getRate());
        long requested = Math.max(1L, requestedTokens);
        long ttlMs = Math.max(1000L, Math.multiplyExact(2000L, divideCeiling(capacity, rate)));
        String redisKey = properties.getRateLimit().getKeyPrefix() + normalize(dimension);

        try {
            Long allowed = redisTemplate.execute(
                            TOKEN_BUCKET_SCRIPT,
                            List.of(redisKey),
                            List.of(Long.toString(capacity), Long.toString(rate),
                                    Long.toString(requested), Long.toString(ttlMs)))
                    .next()
                    .block(Duration.ofMillis(properties.getRateLimit().getTimeoutMs()));
            return Long.valueOf(1L).equals(allowed);
        } catch (RuntimeException exception) {
            boolean failOpen = properties.getRateLimit().isFailOpen();
            logRedisFailure(dimension, failOpen, exception);
            return failOpen;
        }
    }

    private long divideCeiling(final long dividend, final long divisor) {
        return (dividend + divisor - 1L) / divisor;
    }

    private String normalize(final String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        return value.replaceAll("[^A-Za-z0-9:._-]", "_");
    }

    private void logRedisFailure(final String dimension,
                                 final boolean failOpen,
                                 final RuntimeException exception) {
        long now = System.currentTimeMillis();
        long previous = lastFailureLog.get();
        if (now - previous >= FAILURE_LOG_INTERVAL_MS
                && lastFailureLog.compareAndSet(previous, now)) {
            log.error("Distributed rate limiter unavailable for bucket {}; failOpen={}; error={}",
                    dimension, failOpen, exception.getMessage());
        }
    }
}
