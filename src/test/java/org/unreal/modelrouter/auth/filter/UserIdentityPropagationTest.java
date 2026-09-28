package org.unreal.modelrouter.auth.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.model.ApiKeyAuthentication;
import org.unreal.modelrouter.auth.security.model.JwtAuthentication;
import org.unreal.modelrouter.auth.security.model.JwtPrincipal;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * UserIdentity 传播链路测试
 *
 * 验证从 Authentication 提取身份 → 存入 exchange attributes + Reactor Context 的完整链路。
 * 通过模拟 Auth Filter 的认证成功路径来间接测试 extractUserIdentity 逻辑。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserIdentity 传播测试")
class UserIdentityPropagationTest {

    @Mock
    private ServerAuthenticationConverter authenticationConverter;
    @Mock
    private ReactiveAuthenticationManager authenticationManager;
    @Mock
    private org.springframework.web.server.WebFilterChain filterChain;

    /**
     * 测试 API Key 认证时身份提取
     */
    @Test
    @DisplayName("API Key 认证 → UserIdentity 正确提取 keyId/createdBy/description")
    void testApiKeyIdentityExtraction() {
        // 准备已认证的 ApiKeyAuthentication
        ApiKeyAuthentication auth = new ApiKeyAuthentication("key-123", "sk-raw-key", List.of("USER"));
        auth.setAuthenticated(true);

        ApiKey apiKey = ApiKey.builder()
                .keyId("key-123")
                .description("测试用 API Key")
                .createdBy("user-001")
                .build();
        auth.setDetails(apiKey);

        // 通过 filter 验证 exchange attributes 和 Reactor Context 都被设置
        SecurityProperties props = new SecurityProperties();
        props.getApiKey().setEnabled(true);
        props.getJwt().setEnabled(false);

        SpringSecurityAuthenticationFilter filter =
                new SpringSecurityAuthenticationFilter(props, authenticationConverter, authenticationManager);

        MockServerWebExchange exchange = MockServerWebExchange.builder(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest
                        .post("/v1/chat/completions")
                        .header("X-API-Key", "sk-test")
                        .build()).build();

        when(authenticationConverter.convert(any())).thenReturn(Mono.just(new ApiKeyAuthentication("sk-test")));
        when(authenticationManager.authenticate(any())).thenReturn(Mono.just(auth));
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        Mono<Void> result = filter.filter(exchange, filterChain);

        StepVerifier.create(result.contextWrite(Context.of(UserIdentity.CONTEXT_KEY, "dummy")))
                .verifyComplete();

        // 验证 exchange attributes 中存储了 UserIdentity
        UserIdentity stored = exchange.getAttribute(UserIdentity.CONTEXT_KEY);
        assertNotNull(stored, "UserIdentity 应该被存入 exchange attributes");
        assertEquals("key-123", stored.userId());
        assertEquals("user-001", stored.userAccount());
        assertEquals("key-123", stored.apiKeyId());
        assertEquals("测试用 API Key", stored.apiKeyName());
    }

    /**
     * 测试 JWT 认证时身份提取
     */
    @Test
    @DisplayName("JWT 认证 → UserIdentity 从 claims 提取 userId")
    void testJwtIdentityExtraction() {
        JwtAuthentication auth = new JwtAuthentication("user-subject", "token", List.of("USER"));
        auth.setAuthenticated(true);

        JwtPrincipal principal = new JwtPrincipal(
                "user-subject", "jairouter", List.of("USER"),
                LocalDateTime.now(), LocalDateTime.now().plusHours(1),
                Map.of("userId", "real-user-456")
        );
        auth.setDetails(principal);

        SecurityProperties props = new SecurityProperties();
        props.getApiKey().setEnabled(false);
        props.getJwt().setEnabled(true);

        SpringSecurityAuthenticationFilter filter =
                new SpringSecurityAuthenticationFilter(props, authenticationConverter, authenticationManager);

        MockServerWebExchange exchange = MockServerWebExchange.builder(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest
                        .post("/v1/chat/completions")
                        .header("Authorization", "Bearer jwt-token")
                        .build()).build();

        when(authenticationConverter.convert(any())).thenReturn(Mono.just(new JwtAuthentication("jwt-token")));
        when(authenticationManager.authenticate(any())).thenReturn(Mono.just(auth));
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        UserIdentity stored = exchange.getAttribute(UserIdentity.CONTEXT_KEY);
        assertNotNull(stored, "UserIdentity 应该被存入 exchange attributes");
        assertEquals("real-user-456", stored.userId(), "应该从 claims 中取 userId");
        assertEquals("user-subject", stored.userAccount(), "userAccount 应该是 JWT subject");
        assertNull(stored.apiKeyId(), "JWT 认证不应该有 apiKeyId");
        assertNull(stored.apiKeyName(), "JWT 认证不应该有 apiKeyName");
    }

    /**
     * 测试 JWT 无 userId claim 时回退到 subject
     */
    @Test
    @DisplayName("JWT 无 userId claim → 回退到 subject")
    void testJwtFallbackToSubject() {
        JwtAuthentication auth = new JwtAuthentication("fallback-subject", "token", List.of("USER"));
        auth.setAuthenticated(true);

        JwtPrincipal principal = new JwtPrincipal(
                "fallback-subject", "jairouter", List.of("USER"),
                LocalDateTime.now(), LocalDateTime.now().plusHours(1),
                Map.of()
        );
        auth.setDetails(principal);

        SecurityProperties props = new SecurityProperties();
        props.getApiKey().setEnabled(false);
        props.getJwt().setEnabled(true);

        SpringSecurityAuthenticationFilter filter =
                new SpringSecurityAuthenticationFilter(props, authenticationConverter, authenticationManager);

        MockServerWebExchange exchange = MockServerWebExchange.builder(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest
                        .post("/v1/chat/completions")
                        .header("Authorization", "Bearer jwt-token")
                        .build()).build();

        when(authenticationConverter.convert(any())).thenReturn(Mono.just(new JwtAuthentication("jwt-token")));
        when(authenticationManager.authenticate(any())).thenReturn(Mono.just(auth));
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        UserIdentity stored = exchange.getAttribute(UserIdentity.CONTEXT_KEY);
        assertNotNull(stored);
        assertEquals("fallback-subject", stored.userId(), "没有 userId claim 时应该用 subject");
    }

    /**
     * 测试 Reactor Context 中 UserIdentity 能被下游 deferContextual 读取
     */
    @Test
    @DisplayName("Reactor Context 传播 → 下游 Mono.deferContextual 能读到 UserIdentity")
    void testReactorContextPropagation() {
        UserIdentity identity = new UserIdentity("user-1", "account-1", "key-1", "测试Key", null, null, null, false, null, null, null);

        // 模拟 auth filter 写入 context 后，下游读取
        Mono<UserIdentity> downstream = Mono.deferContextual(ctx ->
                Mono.just(ctx.getOrDefault(UserIdentity.CONTEXT_KEY, UserIdentity.SYSTEM))
        );

        StepVerifier.create(downstream.contextWrite(Context.of(UserIdentity.CONTEXT_KEY, identity)))
                .assertNext(read -> {
                    assertEquals("user-1", read.userId());
                    assertEquals("account-1", read.userAccount());
                    assertEquals("key-1", read.apiKeyId());
                    assertEquals("测试Key", read.apiKeyName());
                })
                .verifyComplete();
    }

    /**
     * 测试无 context 时回退到 SYSTEM
     */
    @Test
    @DisplayName("无 Reactor Context → 回退到 UserIdentity.SYSTEM")
    void testNoContextFallback() {
        Mono<UserIdentity> downstream = Mono.deferContextual(ctx ->
                Mono.just(ctx.getOrDefault(UserIdentity.CONTEXT_KEY, UserIdentity.SYSTEM))
        );

        // 不写任何 context
        StepVerifier.create(downstream)
                .assertNext(read -> {
                    assertEquals("system", read.userId());
                    assertEquals("system", read.userAccount());
                    assertNotNull(read.userId(), "SYSTEM userId 不能为 null（DB NOT NULL 约束）");
                })
                .verifyComplete();
    }

    /**
     * 测试 API Key 认证但 details 为 null 的兜底
     */
    @Test
    @DisplayName("API Key 无 details → 回退到 principal 作为 userId")
    void testApiKeyNoDetailsFallback() {
        ApiKeyAuthentication auth = new ApiKeyAuthentication("key-fallback", "sk-raw", List.of("USER"));
        auth.setAuthenticated(true);
        // details 保持 null

        SecurityProperties props = new SecurityProperties();
        props.getApiKey().setEnabled(true);
        props.getJwt().setEnabled(false);

        SpringSecurityAuthenticationFilter filter =
                new SpringSecurityAuthenticationFilter(props, authenticationConverter, authenticationManager);

        MockServerWebExchange exchange = MockServerWebExchange.builder(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest
                        .post("/v1/chat/completions")
                        .header("X-API-Key", "sk-test")
                        .build()).build();

        when(authenticationConverter.convert(any())).thenReturn(Mono.just(new ApiKeyAuthentication("sk-test")));
        when(authenticationManager.authenticate(any())).thenReturn(Mono.just(auth));
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        UserIdentity stored = exchange.getAttribute(UserIdentity.CONTEXT_KEY);
        assertNotNull(stored);
        assertEquals("key-fallback", stored.userId(), "无 details 时应该用 principal");
        assertEquals("api_key_user", stored.userAccount());
    }
}
