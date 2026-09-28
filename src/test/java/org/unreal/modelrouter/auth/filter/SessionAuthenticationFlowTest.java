// 文件说明：测试 SessionAuthenticationFlowTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.auth.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.WebFilterChain;
import org.unreal.modelrouter.auth.security.config.ExcludedPathsConfig;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.model.JwtAuthentication;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SessionAuthenticationFlowTest {
    @Test
    void emptyAuthenticationResultRemainsUnauthorized() {
        var converter = mock(ServerAuthenticationConverter.class);
        var manager = mock(ReactiveAuthenticationManager.class);
        var chain = mock(WebFilterChain.class);
        var credentials = new JwtAuthentication("invalid-token");
        var properties = new SecurityProperties();
        properties.getJwt().setEnabled(true);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/auth/session"));
        when(converter.convert(exchange)).thenReturn(Mono.just(credentials));
        when(manager.authenticate(credentials)).thenReturn(Mono.empty());
        StepVerifier.create(new SpringSecurityAuthenticationFilter(properties, converter, manager)
                .filter(exchange, chain)).verifyComplete();
        org.junit.jupiter.api.Assertions.assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED,
                exchange.getResponse().getStatusCode());
        verifyNoInteractions(chain);
    }

    @Test
    void usesLoadedSessionContextBeforeExchangePrincipalWrapperRuns() {
        var converter = mock(ServerAuthenticationConverter.class);
        var manager = mock(ReactiveAuthenticationManager.class);
        var chain = mock(WebFilterChain.class);
        var authentication = new JwtAuthentication("teacher", "unused", List.of("TEACHER"));
        authentication.setAuthenticated(true);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/auth/session"));
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(new SpringSecurityAuthenticationFilter(new SecurityProperties(), converter, manager)
                .filter(exchange, chain)
                .contextWrite(org.springframework.security.core.context.ReactiveSecurityContextHolder
                        .withAuthentication(authentication))).verifyComplete();
        verify(chain, times(1)).filter(exchange);
        verifyNoInteractions(converter, manager);
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void sessionCompletionDoesNotRunCredentialFallbackOrWriteUnauthorizedResponse() {
        var converter = mock(ServerAuthenticationConverter.class);
        var manager = mock(ReactiveAuthenticationManager.class);
        var chain = mock(WebFilterChain.class);
        var authentication = new JwtAuthentication("teacher", "unused", List.of("TEACHER"));
        authentication.setAuthenticated(true);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/auth/session"))
                .mutate().principal(Mono.just(authentication)).build();
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(new SpringSecurityAuthenticationFilter(
                new SecurityProperties(), converter, manager).filter(exchange, chain)).verifyComplete();

        verify(chain, times(1)).filter(exchange);
        verifyNoInteractions(converter, manager);
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void statelessCompletionDoesNotWriteMissingCredentialError() {
        var converter = mock(ServerAuthenticationConverter.class);
        var manager = mock(ReactiveAuthenticationManager.class);
        var chain = mock(WebFilterChain.class);
        var authentication = new JwtAuthentication("teacher", "token", List.of("TEACHER"));
        authentication.setAuthenticated(true);
        var properties = new SecurityProperties();
        properties.getJwt().setEnabled(true);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/auth/session"));
        when(converter.convert(exchange)).thenReturn(Mono.just(authentication));
        when(manager.authenticate(authentication)).thenReturn(Mono.just(authentication));
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(new SpringSecurityAuthenticationFilter(
                properties, converter, manager).filter(exchange, chain)).verifyComplete();
        verify(chain, times(1)).filter(exchange);
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void healthEndpointIsPublicButOtherActuatorEndpointsAreNot() {
        var converter = mock(ServerAuthenticationConverter.class);
        var manager = mock(ReactiveAuthenticationManager.class);
        var chain = mock(WebFilterChain.class);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));
        when(chain.filter(any())).thenReturn(Mono.empty());
        StepVerifier.create(new SpringSecurityAuthenticationFilter(
                new SecurityProperties(), converter, manager).filter(exchange, chain)).verifyComplete();
        verify(chain).filter(exchange);
        verifyNoInteractions(converter, manager);
        assertFalse(ExcludedPathsConfig.isAuthExcluded("/actuator/env"));
        assertFalse(ExcludedPathsConfig.isAuthExcluded("/actuator/health-secret"));
    }
}
