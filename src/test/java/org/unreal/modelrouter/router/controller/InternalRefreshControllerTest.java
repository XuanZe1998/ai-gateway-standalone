package org.unreal.modelrouter.router.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.unreal.modelrouter.auth.security.cache.impl.PlatformAuthCache;
import org.unreal.modelrouter.billing.ModelPricingService;
import org.unreal.modelrouter.billing.notification.InternalRequestSigner;
import org.unreal.modelrouter.billing.notification.NotificationProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * InternalRefreshController 单元测试。
 * 覆盖：HMAC 签名校验、刷新防抖、正常刷新流程。
 */
@ExtendWith(MockitoExtension.class)
class InternalRefreshControllerTest {

    @Mock
    private ModelServiceRegistry registry;

    @Mock
    private ModelPricingService pricingService;

    @Mock
    private PlatformAuthCache platformAuthCache;

    @Mock
    private NotificationProperties notificationProperties;

    @InjectMocks
    private InternalRefreshController controller;

    private static final String SECRET = "test-internal-secret-key";

    /** 生成有效的签名头 */
    private String[] validHeaders() {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String signature = InternalRequestSigner.sign(SECRET, timestamp, "");
        return new String[]{timestamp, signature};
    }

    /** 生成过期时间戳的签名头 */
    private String[] expiredHeaders() {
        String timestamp = String.valueOf(System.currentTimeMillis() - 10 * 60 * 1000L); // 10 分钟前
        String signature = InternalRequestSigner.sign(SECRET, timestamp, "");
        return new String[]{timestamp, signature};
    }

    @BeforeEach
    void setUp() {
        lenient().when(notificationProperties.getInternalSecret()).thenReturn(SECRET);
    }

    // ==================== 签名校验测试 ====================

    @Test
    @DisplayName("有效签名 → 刷新成功")
    void refreshConfig_validSignature_success() {
        // Given
        String[] headers = validHeaders();
        doNothing().when(registry).refreshFromMergedConfig();
        doNothing().when(pricingService).refreshPricing();

        // When
        Mono<ResponseEntity<String>> result = controller.refreshConfig(headers[0], headers[1]);

        // Then
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody()).isEqualTo("refreshed");
                })
                .verifyComplete();

        verify(registry).refreshFromMergedConfig();
        verify(pricingService).refreshPricing();
        verify(platformAuthCache).clear();
    }

    @Test
    @DisplayName("缺少签名头 → 401")
    void refreshConfig_missingHeaders_unauthorized() {
        // When
        Mono<ResponseEntity<String>> result = controller.refreshConfig(null, null);

        // Then
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(response.getBody()).isEqualTo("missing signature headers");
                })
                .verifyComplete();

        verifyNoInteractions(registry);
        verifyNoInteractions(pricingService);
    }

    @Test
    @DisplayName("缺少时间戳头 → 401")
    void refreshConfig_missingTimestamp_unauthorized() {
        // Given
        String[] headers = validHeaders();

        // When
        Mono<ResponseEntity<String>> result = controller.refreshConfig(null, headers[1]);

        // Then
        StepVerifier.create(result)
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED))
                .verifyComplete();
    }

    @Test
    @DisplayName("缺少签名头 → 401")
    void refreshConfig_missingSignature_unauthorized() {
        // Given
        String[] headers = validHeaders();

        // When
        Mono<ResponseEntity<String>> result = controller.refreshConfig(headers[0], null);

        // Then
        StepVerifier.create(result)
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED))
                .verifyComplete();
    }

    @Test
    @DisplayName("错误签名 → 401")
    void refreshConfig_wrongSignature_unauthorized() {
        // Given
        String[] headers = validHeaders();

        // When
        Mono<ResponseEntity<String>> result = controller.refreshConfig(headers[0], "badsignature");

        // Then
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(response.getBody()).isEqualTo("invalid signature");
                })
                .verifyComplete();

        verifyNoInteractions(registry);
    }

    @Test
    @DisplayName("过期时间戳 → 401（防重放）")
    void refreshConfig_expiredTimestamp_unauthorized() {
        // Given
        String[] headers = expiredHeaders();

        // When
        Mono<ResponseEntity<String>> result = controller.refreshConfig(headers[0], headers[1]);

        // Then
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(response.getBody()).isEqualTo("timestamp expired");
                })
                .verifyComplete();

        verifyNoInteractions(registry);
    }

    @Test
    @DisplayName("时间戳格式错误 → 401")
    void refreshConfig_invalidTimestamp_unauthorized() {
        // When
        Mono<ResponseEntity<String>> result = controller.refreshConfig("not-a-number", "somesig");

        // Then
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(response.getBody()).isEqualTo("invalid timestamp");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("密钥未配置 → 跳过校验，刷新成功（开发环境兼容）")
    void refreshConfig_noSecretConfigured_skipsVerification() {
        // Given
        when(notificationProperties.getInternalSecret()).thenReturn("");
        doNothing().when(registry).refreshFromMergedConfig();
        doNothing().when(pricingService).refreshPricing();

        // When：不带签名头也能通过
        Mono<ResponseEntity<String>> result = controller.refreshConfig(null, null);

        // Then
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody()).isEqualTo("refreshed");
                })
                .verifyComplete();

        verify(registry).refreshFromMergedConfig();
        verify(platformAuthCache).clear();
    }

    // ==================== /internal/refresh/platform 测试 ====================

    @Test
    @DisplayName("platform 刷新 - 有效签名 → 成功")
    void refreshPlatformOnly_validSignature_success() {
        // Given
        String[] headers = validHeaders();
        doNothing().when(registry).refreshFromPlatformOnly();
        doNothing().when(pricingService).refreshPricing();

        // When
        Mono<ResponseEntity<String>> result = controller.refreshPlatformOnly(headers[0], headers[1]);

        // Then
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody()).isEqualTo("platform refreshed");
                })
                .verifyComplete();

        verify(registry).refreshFromPlatformOnly();
        verify(pricingService).refreshPricing();
        verify(platformAuthCache).clear();
    }

    @Test
    @DisplayName("platform 刷新 - 缺少签名头 → 401")
    void refreshPlatformOnly_missingHeaders_unauthorized() {
        // When
        Mono<ResponseEntity<String>> result = controller.refreshPlatformOnly(null, null);

        // Then
        StepVerifier.create(result)
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED))
                .verifyComplete();

        verifyNoInteractions(registry);
    }

    @Test
    @DisplayName("platform 刷新 - 错误签名 → 401")
    void refreshPlatformOnly_wrongSignature_unauthorized() {
        // Given
        String[] headers = validHeaders();

        // When
        Mono<ResponseEntity<String>> result = controller.refreshPlatformOnly(headers[0], "wrong");

        // Then
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(response.getBody()).isEqualTo("invalid signature");
                })
                .verifyComplete();
    }

    // ==================== 刷新异常测试 ====================

    @Test
    @DisplayName("刷新过程中抛异常 → 500")
    void refreshConfig_internalError_returns500() {
        // Given
        String[] headers = validHeaders();
        doThrow(new RuntimeException("DB connection failed")).when(registry).refreshFromMergedConfig();

        // When
        Mono<ResponseEntity<String>> result = controller.refreshConfig(headers[0], headers[1]);

        // Then
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(response.getBody()).contains("refresh failed");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("platform 刷新过程中抛异常 → 500")
    void refreshPlatformOnly_internalError_returns500() {
        // Given
        String[] headers = validHeaders();
        doThrow(new RuntimeException("Sync failed")).when(registry).refreshFromPlatformOnly();

        // When
        Mono<ResponseEntity<String>> result = controller.refreshPlatformOnly(headers[0], headers[1]);

        // Then
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(response.getBody()).contains("platform refresh failed");
                })
                .verifyComplete();
    }
}
