// 文件说明：测试 RedisDistributedRateLimiterTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.unreal.modelrouter.router.ratelimit.RateLimitConfig;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisDistributedRateLimiterTest {

    private ReactiveStringRedisTemplate redisTemplate;
    private ClusterProperties properties;
    private RateLimitConfig config;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        properties = new ClusterProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setTimeoutMs(500L);
        config = new RateLimitConfig("token-bucket", 10L, 5L, "service");
    }

    @Test
    void shouldAllowWhenRedisScriptReturnsOne() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.just(1L));
        RedisDistributedRateLimiter limiter =
                new RedisDistributedRateLimiter(redisTemplate, properties);

        assertTrue(limiter.tryAcquire("service:chat", config, 1));
    }

    @Test
    void shouldDenyWhenRedisScriptReturnsZero() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.just(0L));
        RedisDistributedRateLimiter limiter =
                new RedisDistributedRateLimiter(redisTemplate, properties);

        assertFalse(limiter.tryAcquire("service:chat", config, 1));
    }

    @Test
    void shouldHonorFailOpenPolicyWhenRedisFails() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new IllegalStateException("redis unavailable")));
        RedisDistributedRateLimiter limiter =
                new RedisDistributedRateLimiter(redisTemplate, properties);

        assertFalse(limiter.tryAcquire("service:chat", config, 1));
        properties.getRateLimit().setFailOpen(true);
        assertTrue(limiter.tryAcquire("service:chat", config, 1));
    }
}
