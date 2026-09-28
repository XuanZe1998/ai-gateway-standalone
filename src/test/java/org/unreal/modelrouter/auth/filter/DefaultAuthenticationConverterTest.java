package org.unreal.modelrouter.auth.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.model.ApiKeyAuthentication;
import reactor.test.StepVerifier;

/**
 * DefaultAuthenticationConverter 单元测试
 */
@DisplayName("DefaultAuthenticationConverter 认证转换测试")
class DefaultAuthenticationConverterTest {

    private final SecurityProperties props = createSecurityProperties();
    private final DefaultAuthenticationConverter converter = new DefaultAuthenticationConverter(props);

    private static SecurityProperties createSecurityProperties() {
        SecurityProperties p = new SecurityProperties();
        p.getApiKey().setEnabled(true);
        p.getJwt().setEnabled(true);
        return p;
    }

    @Test
    @DisplayName("X-API-Key 存在时优先使用")
    void testXApiKeyPriority() {
        MockServerWebExchange exchange = MockServerWebExchange.builder(
                MockServerHttpRequest.post("/v1/chat/completions")
                        .header("X-API-Key", "sk-from-x-api-key")
                        .header("Authorization", "Bearer sk-from-auth")
                        .build()).build();

        StepVerifier.create(converter.convert(exchange))
                .assertNext(auth -> {
                    assert auth instanceof ApiKeyAuthentication;
                    assert "sk-from-x-api-key".equals(auth.getCredentials());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("无 X-API-Key 时从 Authorization: Bearer <key> 兜底读取")
    void testAuthorizationBearerFallback() {
        MockServerWebExchange exchange = MockServerWebExchange.builder(
                MockServerHttpRequest.post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-from-auth-header")
                        .build()).build();

        StepVerifier.create(converter.convert(exchange))
                .assertNext(auth -> {
                    assert auth instanceof ApiKeyAuthentication;
                    assert "sk-from-auth-header".equals(auth.getCredentials());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Authorization 无 Bearer 前缀时不提取")
    void testAuthorizationWithoutBearer() {
        MockServerWebExchange exchange = MockServerWebExchange.builder(
                MockServerHttpRequest.post("/v1/chat/completions")
                        .header("Authorization", "Basic c2s9dGVzdA==")
                        .build()).build();

        StepVerifier.create(converter.convert(exchange))
                .verifyComplete(); // Mono.empty()
    }

    @Test
    @DisplayName("无任何认证 header 时返回空")
    void testNoAuthHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.builder(
                MockServerHttpRequest.post("/v1/chat/completions")
                        .build()).build();

        StepVerifier.create(converter.convert(exchange))
                .verifyComplete();
    }

    @Test
    @DisplayName("API Key 未启用时不提取")
    void testApiKeyDisabled() {
        SecurityProperties disabled = new SecurityProperties();
        disabled.getApiKey().setEnabled(false);
        disabled.getJwt().setEnabled(false);
        DefaultAuthenticationConverter c = new DefaultAuthenticationConverter(disabled);

        MockServerWebExchange exchange = MockServerWebExchange.builder(
                MockServerHttpRequest.post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-test")
                        .build()).build();

        StepVerifier.create(c.convert(exchange))
                .verifyComplete();
    }
}
