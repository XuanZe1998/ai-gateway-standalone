// 文件说明：测试 EmergencyClientIpResolverTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.auth.campus.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.unreal.modelrouter.auth.campus.config.CampusAuthProperties;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmergencyClientIpResolverTest {

    @Test
    void ignoresForwardedHeadersFromUntrustedPeer() {
        CampusAuthProperties properties = properties(List.of("10.0.0.0/8"));
        EmergencyClientIpResolver resolver = new EmergencyClientIpResolver(properties);
        MockServerWebExchange exchange = exchange("203.0.113.9", "10.20.30.40", null);

        assertEquals("203.0.113.9", resolver.resolve(exchange));
    }

    @Test
    void acceptsForwardedIpFromTrustedProxy() {
        CampusAuthProperties properties = properties(List.of("10.0.0.0/8"));
        EmergencyClientIpResolver resolver = new EmergencyClientIpResolver(properties);
        MockServerWebExchange exchange = exchange("10.1.2.3", "192.168.8.9, 10.1.2.3", null);

        assertEquals("192.168.8.9", resolver.resolve(exchange));
    }

    @Test
    void rejectsHostnameAndFallsBackToTrustedPeer() {
        CampusAuthProperties properties = properties(List.of("10.0.0.0/8"));
        EmergencyClientIpResolver resolver = new EmergencyClientIpResolver(properties);
        MockServerWebExchange exchange = exchange("10.1.2.3", "attacker.example.com", "also.invalid");

        assertEquals("10.1.2.3", resolver.resolve(exchange));
    }

    @Test
    void checksAllowedCidrsForIpv4AndIpv6() {
        CampusAuthProperties properties = properties(List.of());
        properties.getEmergencyLogin().setAllowedCidrs(List.of("192.168.0.0/16", "2001:db8::/32"));
        EmergencyClientIpResolver resolver = new EmergencyClientIpResolver(properties);

        assertTrue(resolver.isAllowed("192.168.1.9"));
        assertTrue(resolver.isAllowed("2001:db8::1"));
        assertFalse(resolver.isAllowed("192.169.1.9"));
        assertFalse(resolver.isAllowed("host.example.com"));
    }

    private CampusAuthProperties properties(final List<String> trustedProxyCidrs) {
        CampusAuthProperties properties = new CampusAuthProperties();
        properties.getEmergencyLogin().setTrustedProxyCidrs(trustedProxyCidrs);
        return properties;
    }

    private MockServerWebExchange exchange(final String remoteIp, final String forwardedFor, final String realIp) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.post("/api/auth/jwt/login")
                .remoteAddress(new InetSocketAddress(remoteIp, 12345));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        if (realIp != null) {
            request.header("X-Real-IP", realIp);
        }
        return MockServerWebExchange.from(request.build());
    }
}
