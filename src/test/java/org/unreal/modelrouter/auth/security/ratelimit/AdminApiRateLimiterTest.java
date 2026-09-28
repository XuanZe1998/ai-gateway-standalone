// 文件说明：测试 AdminApiRateLimiterTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.auth.security.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import org.unreal.modelrouter.auth.campus.config.CampusAuthProperties;
import org.unreal.modelrouter.auth.campus.service.EmergencyClientIpResolver;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AdminApiRateLimiterTest {

    @Test
    void limitsEmergencyLoginAfterTenPostRequestsPerClient() {
        CampusAuthProperties properties = new CampusAuthProperties();
        EmergencyClientIpResolver resolver = new EmergencyClientIpResolver(properties);
        AdminApiRateLimiter limiter = new AdminApiRateLimiter(resolver);
        AtomicInteger forwarded = new AtomicInteger();
        WebFilterChain chain = exchange -> {
            forwarded.incrementAndGet();
            return Mono.empty();
        };

        for (int i = 0; i < 10; i++) {
            MockServerWebExchange exchange = emergencyLogin("198.51.100.20", "203.0.113." + i);
            limiter.filter(exchange, chain).block();
            assertNull(exchange.getResponse().getStatusCode());
        }

        MockServerWebExchange rejected = emergencyLogin("198.51.100.20", "203.0.113.200");
        limiter.filter(rejected, chain).block();

        assertEquals(10, forwarded.get());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getResponse().getStatusCode());
    }

    @Test
    void leavesUnrelatedEndpointsUnchanged() {
        CampusAuthProperties properties = new CampusAuthProperties();
        AdminApiRateLimiter limiter = new AdminApiRateLimiter(new EmergencyClientIpResolver(properties));
        AtomicInteger forwarded = new AtomicInteger();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/other"));

        limiter.filter(exchange, ignored -> {
            forwarded.incrementAndGet();
            return Mono.empty();
        }).block();

        assertEquals(1, forwarded.get());
        assertNull(exchange.getResponse().getStatusCode());
    }

    private MockServerWebExchange emergencyLogin(final String remoteIp, final String spoofedForwardedFor) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/jwt/login")
                .remoteAddress(new InetSocketAddress(remoteIp, 12345))
                .header("X-Forwarded-For", spoofedForwardedFor));
    }
}
