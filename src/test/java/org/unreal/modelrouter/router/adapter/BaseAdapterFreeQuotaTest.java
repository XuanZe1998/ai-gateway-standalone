package org.unreal.modelrouter.router.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.billing.BillingService;
import org.unreal.modelrouter.billing.freequota.FreeQuotaResult;
import org.unreal.modelrouter.billing.freequota.FreeQuotaService;
import org.unreal.modelrouter.common.util.ApplicationContextProvider;
import org.unreal.modelrouter.persistence.repository.ModelCallStatsRepository;
import org.unreal.modelrouter.router.adapter.builder.RequestBuilder;
import org.unreal.modelrouter.router.adapter.checker.CapabilityChecker;
import org.unreal.modelrouter.router.adapter.error.AdapterErrorHandler;
import org.unreal.modelrouter.router.adapter.error.ErrorResponseBuilder;
import org.unreal.modelrouter.router.adapter.handler.ResponseHandler;
import org.unreal.modelrouter.router.adapter.mapper.ResponseMapper;
import org.unreal.modelrouter.router.adapter.metrics.AdapterMetricsRecorder;
import org.unreal.modelrouter.router.adapter.processor.HttpRequestProcessor;
import org.unreal.modelrouter.router.adapter.retry.RetryPolicy;
import org.unreal.modelrouter.router.adapter.selector.InstanceSelector;
import org.unreal.modelrouter.router.adapter.transformer.ResponseTransformer;
import org.unreal.modelrouter.router.adapter.tracing.AdapterTracingManager;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BaseAdapter 免费额度集成测试
 *
 * 测试目标：
 * 1. 免费额度充足时，BillingContext 携带 isFreeQuota=true 和 freeQuotaConsumed=实际tokens
 * 2. 免费额度不足时，扣减剩余额度并锁定 trial_exhausted，仍保存计费记录（isFreeQuota=true）
 * 3. 非平台用户或非文本服务时，不走免费额度逻辑
 * 4. FreeQuotaService 故障时，计费记录仍应保存（不带免费额度标记）
 *
 * @since v2.26.x
 */
@ExtendWith(MockitoExtension.class)
class BaseAdapterFreeQuotaTest {

    @Mock
    private ModelServiceRegistry registry;

    @Mock
    private ModelCallStatsRepository statsRepository;

    @Mock
    private InstanceSelector instanceSelector;

    @Mock
    private ResponseTransformer responseTransformer;

    @Mock
    private CapabilityChecker capabilityChecker;

    @Mock
    private FreeQuotaService freeQuotaService;

    @Mock
    private BillingService billingService;

    private ObjectMapper objectMapper;
    private AdapterErrorHandler errorHandler;
    private RetryPolicy retryPolicy;
    private TestAdapter testAdapter;
    private ModelRouterProperties.ModelInstance testInstance;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        errorHandler = new AdapterErrorHandler();
        retryPolicy = new RetryPolicy();

        testInstance = new ModelRouterProperties.ModelInstance();
        testInstance.setName("test-instance");
        testInstance.setInstanceId("test-id-001");
        testInstance.setBaseUrl("http://localhost:8081");
        testInstance.setWeight(100);
        testInstance.setChannelId("ch-001");

        testAdapter = new TestAdapter(
                registry,
                objectMapper,
                statsRepository,
                instanceSelector,
                responseTransformer,
                capabilityChecker,
                errorHandler,
                retryPolicy
        );
    }

    // ========== 免费额度充足场景 ==========

    @Nested
    @DisplayName("免费额度充足场景")
    class FreeQuotaSufficientTests {

        @Test
        @DisplayName("平台用户 + 文本服务 + 额度充足 → BillingContext 携带 isFreeQuota=true")
        void shouldBuildBillingContextWithFreeQuotaWhenSufficient() throws Exception {
            // Given
            UserIdentity platformUser = new UserIdentity(
                    "user-123",
                    "test@example.com",
                    "ak-001",
                    "test-key",
                    100L,
                    "TestCorp",
                    "comp-001",
                    true, null, null, null
            );

            FreeQuotaResult hitResult = FreeQuotaResult.hit(100L, 500L, 400L);

            try (MockedStatic<ApplicationContextProvider> mockedStatic = mockStatic(ApplicationContextProvider.class)) {
                mockedStatic.when(() -> ApplicationContextProvider.getBean(FreeQuotaService.class))
                        .thenReturn(freeQuotaService);
                mockedStatic.when(() -> ApplicationContextProvider.getBean(BillingService.class))
                        .thenReturn(billingService);

                when(freeQuotaService.isEnabledFor("user-123", "chat")).thenReturn(true);
                when(freeQuotaService.getRemainingQuota("user-123", "chat")).thenReturn(500L);
                when(freeQuotaService.deductStreamingQuota("user-123", "chat", 100L)).thenReturn(hitResult);

                // When
                invokeRecordBilling(platformUser, 100L);

                // Then
                verify(freeQuotaService).isEnabledFor("user-123", "chat");
                verify(freeQuotaService).getRemainingQuota("user-123", "chat");
                verify(freeQuotaService).deductStreamingQuota("user-123", "chat", 100L);

                // 验证 BillingContext 携带了免费额度标记
                verify(billingService).recordBilling(argThat(ctx ->
                        Boolean.TRUE.equals(ctx.getIsFreeQuota())
                                && ctx.getFreeQuotaConsumed() != null
                                && ctx.getFreeQuotaConsumed() == 100L
                ));
            }
        }

        @Test
        @DisplayName("embedding 服务也支持免费额度")
        void shouldSupportFreeQuotaForEmbeddingService() throws Exception {
            // Given
            UserIdentity platformUser = new UserIdentity(
                    "user-456",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true, null, null, null
            );

            FreeQuotaResult hitResult = FreeQuotaResult.hit(50L, 200L, 150L);

            try (MockedStatic<ApplicationContextProvider> mockedStatic = mockStatic(ApplicationContextProvider.class)) {
                mockedStatic.when(() -> ApplicationContextProvider.getBean(FreeQuotaService.class))
                        .thenReturn(freeQuotaService);
                mockedStatic.when(() -> ApplicationContextProvider.getBean(BillingService.class))
                        .thenReturn(billingService);

                when(freeQuotaService.isEnabledFor("user-456", "embedding")).thenReturn(true);
                when(freeQuotaService.getRemainingQuota("user-456", "embedding")).thenReturn(200L);
                when(freeQuotaService.deductStreamingQuota("user-456", "embedding", 50L)).thenReturn(hitResult);

                // When
                invokeRecordBilling(platformUser, ModelServiceRegistry.ServiceType.embedding, 50L);

                // Then
                verify(billingService).recordBilling(argThat(ctx ->
                        Boolean.TRUE.equals(ctx.getIsFreeQuota())
                                && ctx.getFreeQuotaConsumed() == 50L
                ));
            }
        }
    }

    // ========== 免费额度不足场景 ==========

    @Nested
    @DisplayName("免费额度不足场景")
    class FreeQuotaInsufficientTests {

        @Test
        @DisplayName("平台用户 + 额度不足 → 扣减剩余额度并锁定，仍保存计费记录")
        void shouldAcceptOverageAndLockQuotaWhenInsufficient() throws Exception {
            // Given
            UserIdentity platformUser = new UserIdentity(
                    "user-123",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true, null, null, null
            );

            // 实际用量 200，剩余 100，超支接受：扣减 100，锁定后剩余 0
            FreeQuotaResult overageResult = FreeQuotaResult.hit(100L, 100L, 0L);

            try (MockedStatic<ApplicationContextProvider> mockedStatic = mockStatic(ApplicationContextProvider.class)) {
                mockedStatic.when(() -> ApplicationContextProvider.getBean(FreeQuotaService.class))
                        .thenReturn(freeQuotaService);
                mockedStatic.when(() -> ApplicationContextProvider.getBean(BillingService.class))
                        .thenReturn(billingService);

                when(freeQuotaService.isEnabledFor("user-123", "chat")).thenReturn(true);
                when(freeQuotaService.getRemainingQuota("user-123", "chat")).thenReturn(100L);
                when(freeQuotaService.deductStreamingQuota("user-123", "chat", 200L)).thenReturn(overageResult);

                // When
                invokeRecordBilling(platformUser, 200L);

                // Then - 不应抛异常，且计费记录仍保存，isFreeQuota=true，freeQuotaConsumed=100
                verify(freeQuotaService).deductStreamingQuota("user-123", "chat", 200L);
                verify(billingService).recordBilling(argThat(ctx ->
                        Boolean.TRUE.equals(ctx.getIsFreeQuota())
                                && ctx.getFreeQuotaConsumed() != null
                                && ctx.getFreeQuotaConsumed() == 100L
                ));
            }
        }
    }

    // ========== 免费额度不适用场景 ==========

    @Nested
    @DisplayName("免费额度不适用场景")
    class FreeQuotaNotApplicableTests {

        @Test
        @DisplayName("非平台用户（本地 API Key）→ 不走免费额度逻辑")
        void shouldSkipFreeQuotaForNonPlatformUser() throws Exception {
            // Given
            UserIdentity localUser = new UserIdentity(
                    "local-user-001",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false, null, null, null
            );

            try (MockedStatic<ApplicationContextProvider> mockedStatic = mockStatic(ApplicationContextProvider.class)) {
                mockedStatic.when(() -> ApplicationContextProvider.getBean(BillingService.class))
                        .thenReturn(billingService);

                // FreeQuotaService 不应该被查询
                // When
                invokeRecordBilling(localUser, 100L);

                // Then
                verify(billingService).recordBilling(argThat(ctx ->
                        !Boolean.TRUE.equals(ctx.getIsFreeQuota())
                                && (ctx.getFreeQuotaConsumed() == null || ctx.getFreeQuotaConsumed() == 0L)
                ));

                // FreeQuotaService 不应被调用
                verifyNoInteractions(freeQuotaService);
            }
        }

        @Test
        @DisplayName("平台用户但服务类型不在免费额度白名单 → 不走免费额度")
        void shouldSkipFreeQuotaForNonEligibleServiceType() throws Exception {
            // Given
            UserIdentity platformUser = new UserIdentity(
                    "user-123",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true, null, null, null
            );

            try (MockedStatic<ApplicationContextProvider> mockedStatic = mockStatic(ApplicationContextProvider.class)) {
                mockedStatic.when(() -> ApplicationContextProvider.getBean(FreeQuotaService.class))
                        .thenReturn(freeQuotaService);
                mockedStatic.when(() -> ApplicationContextProvider.getBean(BillingService.class))
                        .thenReturn(billingService);

                // tts 不在默认免费额度白名单中
                when(freeQuotaService.isEnabledFor("user-123", "tts")).thenReturn(false);

                // When
                invokeRecordBilling(platformUser, ModelServiceRegistry.ServiceType.tts, 100L);

                // Then
                verify(freeQuotaService).isEnabledFor("user-123", "tts");
                verify(billingService).recordBilling(argThat(ctx ->
                        !Boolean.TRUE.equals(ctx.getIsFreeQuota())
                ));
                verify(freeQuotaService, never()).deductStreamingQuota(any(), any(), anyLong());
            }
        }

        @Test
        @DisplayName("identity 为 null → 不走免费额度")
        void shouldSkipFreeQuotaForNullIdentity() throws Exception {
            try (MockedStatic<ApplicationContextProvider> mockedStatic = mockStatic(ApplicationContextProvider.class)) {
                mockedStatic.when(() -> ApplicationContextProvider.getBean(BillingService.class))
                        .thenReturn(billingService);

                // When
                invokeRecordBilling(null, 100L);

                // Then
                verify(billingService).recordBilling(argThat(ctx ->
                        !Boolean.TRUE.equals(ctx.getIsFreeQuota())
                ));
                verifyNoInteractions(freeQuotaService);
            }
        }
        @Test
        @DisplayName("平台用户但无免费额度记录 → 不走免费额度，走余额计费")
        void shouldFallbackToBalanceWhenNoFreeQuotaRecord() throws Exception {
            // Given
            UserIdentity enterpriseUser = new UserIdentity(
                    "user-789",
                    "corp@example.com",
                    "ak-002",
                    "test-key",
                    100L,
                    "TestCorp",
                    "comp-002",
                    true, null, null, null
            );

            try (MockedStatic<ApplicationContextProvider> mockedStatic = mockStatic(ApplicationContextProvider.class)) {
                mockedStatic.when(() -> ApplicationContextProvider.getBean(FreeQuotaService.class))
                        .thenReturn(freeQuotaService);
                mockedStatic.when(() -> ApplicationContextProvider.getBean(BillingService.class))
                        .thenReturn(billingService);

                when(freeQuotaService.isEnabledFor("user-789", "chat")).thenReturn(true);
                when(freeQuotaService.getRemainingQuota("user-789", "chat")).thenReturn(0L);

                // When
                invokeRecordBilling(enterpriseUser, 100L);

                // Then
                verify(freeQuotaService).isEnabledFor("user-789", "chat");
                verify(freeQuotaService).getRemainingQuota("user-789", "chat");
                verify(freeQuotaService, never()).deductStreamingQuota(any(), any(), anyLong());

                verify(billingService).recordBilling(argThat(ctx ->
                        !Boolean.TRUE.equals(ctx.getIsFreeQuota())
                                && (ctx.getFreeQuotaConsumed() == null || ctx.getFreeQuotaConsumed() == 0L)
                ));
            }
        }
    }

    // ========== FreeQuotaService 故障场景 ==========

    @Nested
    @DisplayName("FreeQuotaService 故障场景")
    class FreeQuotaServiceFailureTests {

        @Test
        @DisplayName("FreeQuotaService Bean 不存在 → 计费记录仍应保存（不带免费额度标记）")
        void shouldSaveBillingRecordWhenFreeQuotaServiceUnavailable() throws Exception {
            // Given
            UserIdentity platformUser = new UserIdentity(
                    "user-123",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true, null, null, null
            );

            try (MockedStatic<ApplicationContextProvider> mockedStatic = mockStatic(ApplicationContextProvider.class)) {
                mockedStatic.when(() -> ApplicationContextProvider.getBean(FreeQuotaService.class))
                        .thenThrow(new IllegalStateException("FreeQuotaService not available"));
                mockedStatic.when(() -> ApplicationContextProvider.getBean(BillingService.class))
                        .thenReturn(billingService);

                // When - 不应抛出异常
                assertDoesNotThrow(() -> invokeRecordBilling(platformUser, 100L));

                // Then - 计费记录仍应保存，但不带免费额度标记
                verify(billingService).recordBilling(argThat(ctx ->
                        !Boolean.TRUE.equals(ctx.getIsFreeQuota())
                                && (ctx.getFreeQuotaConsumed() == null || ctx.getFreeQuotaConsumed() == 0L)
                ));
            }
        }

        @Test
        @DisplayName("FreeQuotaService.deductStreamingQuota 抛出异常 → 计费记录仍应保存")
        void shouldSaveBillingRecordWhenDeductQuotaThrows() throws Exception {
            // Given
            UserIdentity platformUser = new UserIdentity(
                    "user-123",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true, null, null, null
            );

            try (MockedStatic<ApplicationContextProvider> mockedStatic = mockStatic(ApplicationContextProvider.class)) {
                mockedStatic.when(() -> ApplicationContextProvider.getBean(FreeQuotaService.class))
                        .thenReturn(freeQuotaService);
                mockedStatic.when(() -> ApplicationContextProvider.getBean(BillingService.class))
                        .thenReturn(billingService);

                when(freeQuotaService.isEnabledFor("user-123", "chat")).thenReturn(true);
                when(freeQuotaService.getRemainingQuota("user-123", "chat")).thenReturn(500L);
                when(freeQuotaService.deductStreamingQuota("user-123", "chat", 100L))
                        .thenThrow(new RuntimeException("Database connection failed"));

                // When - 不应抛出异常
                assertDoesNotThrow(() -> invokeRecordBilling(platformUser, 100L));

                // Then - 计费记录仍应保存
                verify(billingService).recordBilling(argThat(ctx ->
                        !Boolean.TRUE.equals(ctx.getIsFreeQuota())
                ));
            }
        }

        @Test
        @DisplayName("BillingService 故障 → 仅记录日志，不抛异常到主流程")
        void shouldNotPropagateBillingServiceException() throws Exception {
            // Given
            UserIdentity platformUser = new UserIdentity(
                    "user-123",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true, null, null, null
            );

            try (MockedStatic<ApplicationContextProvider> mockedStatic = mockStatic(ApplicationContextProvider.class)) {
                mockedStatic.when(() -> ApplicationContextProvider.getBean(FreeQuotaService.class))
                        .thenReturn(freeQuotaService);
                mockedStatic.when(() -> ApplicationContextProvider.getBean(BillingService.class))
                        .thenReturn(billingService);

                when(freeQuotaService.isEnabledFor("user-123", "chat")).thenReturn(true);
                when(freeQuotaService.getRemainingQuota("user-123", "chat")).thenReturn(500L);
                when(freeQuotaService.deductStreamingQuota("user-123", "chat", 100L))
                        .thenReturn(FreeQuotaResult.hit(100L, 500L, 400L));
                doThrow(new RuntimeException("Database write failed")).when(billingService).recordBilling(any());

                // When - 不应抛出异常到主流程
                assertDoesNotThrow(() -> invokeRecordBilling(platformUser, 100L));
            }
        }
    }

    // ========== 辅助方法 ==========

    private void invokeRecordBilling(UserIdentity identity, long totalTokens) throws Exception {
        invokeRecordBilling(identity, ModelServiceRegistry.ServiceType.chat, totalTokens);
    }

    private void invokeRecordBilling(UserIdentity identity, ModelServiceRegistry.ServiceType serviceType,
                                       long totalTokens) throws Exception {
        invokeRecordBilling(identity, serviceType, totalTokens, null);
    }

    private void invokeRecordBilling(UserIdentity identity, ModelServiceRegistry.ServiceType serviceType,
                                       long totalTokens, String upstreamBody) throws Exception {
        Method method = BaseAdapter.class.getDeclaredMethod("recordBilling",
                String.class, String.class, ModelRouterProperties.ModelInstance.class,
                ModelServiceRegistry.ServiceType.class, String.class,
                long.class, boolean.class, String.class,
                long.class, long.class, long.class,
                org.unreal.modelrouter.billing.usage.TokenUsage.class, String.class, UserIdentity.class);
        method.setAccessible(true);
        method.invoke(testAdapter,
                "test-adapter", "test-instance", testInstance,
                serviceType, "gpt-4",
                1000L, true, null,
                totalTokens / 2, totalTokens / 2, totalTokens,
                null, upstreamBody, identity);
    }

    // ========== 计费响应快照 ==========

    @Nested
    @DisplayName("计费响应快照（V5）")
    class ResponseSnapshotTests {

        @Test
        @DisplayName("非流式响应体 → 经 builder 构建后写入 BillingContext")
        void shouldCarrySnapshotBuiltFromUpstreamBody() throws Exception {
            // Given
            UserIdentity platformUser = new UserIdentity(
                    "user-123", null, null, null, null, null, null,
                    false, null, null, null);
            org.unreal.modelrouter.billing.ResponseSnapshotBuilder snapshotBuilder =
                    mock(org.unreal.modelrouter.billing.ResponseSnapshotBuilder.class);

            try (MockedStatic<ApplicationContextProvider> mockedStatic = mockStatic(ApplicationContextProvider.class)) {
                mockedStatic.when(() -> ApplicationContextProvider.getBean(FreeQuotaService.class))
                        .thenReturn(freeQuotaService);
                mockedStatic.when(() -> ApplicationContextProvider.getBean(BillingService.class))
                        .thenReturn(billingService);
                mockedStatic.when(() -> ApplicationContextProvider.getBean(
                        org.unreal.modelrouter.billing.ResponseSnapshotBuilder.class))
                        .thenReturn(snapshotBuilder);
                when(snapshotBuilder.build(eq("test-adapter"), eq(true), eq(200), isNull(), isNull(),
                        isNull(), eq("{\"id\":\"chatcmpl-1\"}")))
                        .thenReturn("{\"protocol\":\"test-adapter\",\"success\":true}");

                // When
                invokeRecordBilling(platformUser, ModelServiceRegistry.ServiceType.chat, 100L,
                        "{\"id\":\"chatcmpl-1\"}");

                // Then
                verify(billingService).recordBilling(argThat(ctx ->
                        "{\"protocol\":\"test-adapter\",\"success\":true}".equals(ctx.getResponseSnapshot())));
            }
        }

        @Test
        @DisplayName("无响应体（最终失败/流式跳过）→ 不构建快照，落库为 NULL")
        void shouldSkipSnapshotWhenNoUpstreamBody() throws Exception {
            // Given
            UserIdentity platformUser = new UserIdentity(
                    "user-123", null, null, null, null, null, null,
                    false, null, null, null);
            org.unreal.modelrouter.billing.ResponseSnapshotBuilder snapshotBuilder =
                    mock(org.unreal.modelrouter.billing.ResponseSnapshotBuilder.class);

            try (MockedStatic<ApplicationContextProvider> mockedStatic = mockStatic(ApplicationContextProvider.class)) {
                mockedStatic.when(() -> ApplicationContextProvider.getBean(FreeQuotaService.class))
                        .thenReturn(freeQuotaService);
                mockedStatic.when(() -> ApplicationContextProvider.getBean(BillingService.class))
                        .thenReturn(billingService);
                mockedStatic.when(() -> ApplicationContextProvider.getBean(
                        org.unreal.modelrouter.billing.ResponseSnapshotBuilder.class))
                        .thenReturn(snapshotBuilder);

                // When - 不传响应体（对应最终失败分支）
                invokeRecordBilling(platformUser, ModelServiceRegistry.ServiceType.chat, 100L, null);

                // Then - builder 不被调用，快照为 null
                verifyNoInteractions(snapshotBuilder);
                verify(billingService).recordBilling(argThat(ctx -> ctx.getResponseSnapshot() == null));
            }
        }
    }

    // ========== 测试用的 Adapter 实现 ==========

    private static class TestAdapter extends BaseAdapter {

        public TestAdapter(
                ModelServiceRegistry registry,
                ObjectMapper objectMapper,
                ModelCallStatsRepository statsRepository,
                InstanceSelector instanceSelector,
                ResponseTransformer responseTransformer,
                CapabilityChecker capabilityChecker,
                AdapterErrorHandler errorHandler,
                RetryPolicy retryPolicy) {
            super(
                    registry,
                    objectMapper,
                    statsRepository,
                    new RequestBuilder(),
                    new ResponseHandler(objectMapper),
                    instanceSelector,
                    responseTransformer,
                    capabilityChecker,
                    errorHandler,
                    retryPolicy,
                    new HttpRequestProcessor(),
                    new ResponseMapper(objectMapper),
                    null,
                    null,
                    new ErrorResponseBuilder(),
                    null,
                    null,
                    null
            );
        }

        @Override
        protected String getAdapterType() {
            return "test";
        }

        @Override
        public AdapterCapabilities supportCapability() {
            return AdapterCapabilities.builder()
                    .chat(true)
                    .embedding(true)
                    .rerank(true)
                    .tts(true)
                    .stt(true)
                    .build();
        }
    }
}
