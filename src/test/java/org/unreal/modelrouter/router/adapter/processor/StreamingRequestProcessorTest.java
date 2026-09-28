// 文件说明：测试 StreamingRequestProcessorTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.router.adapter.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.billing.freequota.FreeQuotaService;
import org.unreal.modelrouter.billing.usage.TokenUsage;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;
import org.unreal.modelrouter.router.adapter.config.StreamUsageInjectionProperties;
import org.unreal.modelrouter.router.adapter.transformer.ResponseTransformer;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamingRequestProcessorTest {

    @Mock
    private MetricsCollector metricsCollector;
    @Mock
    private ResponseTransformer responseTransformer;
    @Mock
    private FreeQuotaService freeQuotaService;
    @Mock
    private org.unreal.modelrouter.billing.ResponseSnapshotBuilder responseSnapshotBuilder;

    private ObjectMapper objectMapper;
    private StreamingRequestProcessor processor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    private StreamingRequestProcessor newProcessor(final StreamUsageInjectionProperties props) {
        return new StreamingRequestProcessor(
                metricsCollector, responseTransformer, objectMapper, freeQuotaService, props,
                responseSnapshotBuilder);
    }

    @Test
    void injectStreamOptions_addsIncludeUsageForMatchingInstance() throws Exception {
        StreamUsageInjectionProperties props = enabledProps();
        processor = newProcessor(props);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "doubao-lite");
        request.put("stream", true);

        ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
        instance.setName("volc-ark");
        instance.setBaseUrl("https://ark.cn-beijing.volces.com/api/v3");

        Object result = invokeInject(request, ModelServiceRegistry.ServiceType.chat, instance);

        assertTrue(result instanceof ObjectNode);
        ObjectNode resultNode = (ObjectNode) result;
        assertTrue(resultNode.has("stream_options"));
        assertTrue(resultNode.path("stream_options").path("include_usage").asBoolean());
    }

    @Test
    void injectStreamOptions_doesNotInjectForNonChatService() throws Exception {
        StreamUsageInjectionProperties props = enabledProps();
        processor = newProcessor(props);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("stream", true);

        ModelRouterProperties.ModelInstance instance = instance();

        Object result = invokeInject(request, ModelServiceRegistry.ServiceType.embedding, instance);

        assertSame(request, result);
        assertFalse(((ObjectNode) result).has("stream_options"));
    }

    @Test
    void injectStreamOptions_doesNotInjectForNonStreamingRequest() throws Exception {
        StreamUsageInjectionProperties props = enabledProps();
        processor = newProcessor(props);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("stream", false);

        ModelRouterProperties.ModelInstance instance = instance();

        Object result = invokeInject(request, ModelServiceRegistry.ServiceType.chat, instance);

        assertSame(request, result);
    }

    @Test
    void injectStreamOptions_doesNotInjectWhenDisabled() throws Exception {
        StreamUsageInjectionProperties props = new StreamUsageInjectionProperties();
        props.setEnabled(false);
        processor = newProcessor(props);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("stream", true);

        Object result = invokeInject(request, ModelServiceRegistry.ServiceType.chat, instance());

        assertSame(request, result);
    }

    @Test
    void injectStreamOptions_doesNotOverrideExistingStreamOptions() throws Exception {
        StreamUsageInjectionProperties props = enabledProps();
        processor = newProcessor(props);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("stream", true);
        ObjectNode existingOptions = objectMapper.createObjectNode();
        existingOptions.put("include_usage", false);
        request.set("stream_options", existingOptions);

        Object result = invokeInject(request, ModelServiceRegistry.ServiceType.chat, instance());

        assertSame(request, result);
        assertFalse(request.path("stream_options").path("include_usage").asBoolean());
    }

    // ==================== 计费响应快照：尾部 usage chunk 捕获 ====================

    @Test
    void captureUsageFromChunk_recordsRawUsageAndTailChunkForSnapshot() throws Exception {
        processor = newProcessor(enabledProps());
        org.unreal.modelrouter.billing.usage.TokenUsageExtractor extractor =
                mock(org.unreal.modelrouter.billing.usage.TokenUsageExtractor.class);
        when(extractor.extract(any(), any(), any()))
                .thenReturn(new TokenUsage(10, 0, 0, 0, 20, 0, 10, 20, 30));

        try (MockedStatic<org.unreal.modelrouter.common.util.ApplicationContextProvider> mocked =
                     mockStatic(org.unreal.modelrouter.common.util.ApplicationContextProvider.class)) {
            mocked.when(() -> org.unreal.modelrouter.common.util.ApplicationContextProvider.getBean(
                    org.unreal.modelrouter.billing.usage.TokenUsageExtractor.class)).thenReturn(extractor);

            AtomicReference<TokenUsage> capturedUsage = new AtomicReference<>();
            AtomicReference<com.fasterxml.jackson.databind.JsonNode> rawUsage = new AtomicReference<>();
            AtomicReference<String> snapshotBody = new AtomicReference<>();

            String chunk = "data: {\"id\":\"c1\",\"choices\":[{\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}";
            invokeCaptureUsage(chunk, capturedUsage, rawUsage, snapshotBody);

            assertNotNull(capturedUsage.get());
            // 快照素材：usage 原始结构 + 去掉 SSE 前缀的尾部 chunk（与透传链路口径一致）
            assertEquals(30, rawUsage.get().path("total_tokens").asInt());
            assertTrue(snapshotBody.get().contains("\"finish_reason\""));
            assertFalse(snapshotBody.get().startsWith("data:"));
        }
    }

    @Test
    void captureUsageFromChunk_ignoresDoneMarkerAndNonUsageChunks() throws Exception {
        processor = newProcessor(enabledProps());

        AtomicReference<TokenUsage> capturedUsage = new AtomicReference<>();
        AtomicReference<com.fasterxml.jackson.databind.JsonNode> rawUsage = new AtomicReference<>();
        AtomicReference<String> snapshotBody = new AtomicReference<>();

        invokeCaptureUsage("data: [DONE]", capturedUsage, rawUsage, snapshotBody);
        invokeCaptureUsage("data: {\"id\":\"c0\",\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                capturedUsage, rawUsage, snapshotBody);

        assertNull(capturedUsage.get());
        assertNull(rawUsage.get());
        assertNull(snapshotBody.get());
    }

    @SuppressWarnings("unchecked")
    private void invokeCaptureUsage(final String chunk,
                                    final AtomicReference<TokenUsage> capturedUsage,
                                    final AtomicReference<com.fasterxml.jackson.databind.JsonNode> rawUsage,
                                    final AtomicReference<String> snapshotBody) throws Exception {
        java.lang.reflect.Method method = StreamingRequestProcessor.class.getDeclaredMethod(
                "captureUsageFromChunk", String.class,
                AtomicReference.class, AtomicReference.class, AtomicReference.class,
                ModelRouterProperties.ModelInstance.class);
        method.setAccessible(true);
        method.invoke(processor, chunk, capturedUsage, rawUsage, snapshotBody, instance());
    }

    private StreamUsageInjectionProperties enabledProps() {
        StreamUsageInjectionProperties props = new StreamUsageInjectionProperties();
        props.setEnabled(true);
        props.setPatterns(List.of(".*volces\\.com.*"));
        props.setOptions(Map.of("include_usage", true));
        return props;
    }

    private ModelRouterProperties.ModelInstance instance() {
        ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
        instance.setName("volc-ark");
        instance.setBaseUrl("https://ark.cn-beijing.volces.com/api/v3");
        return instance;
    }

    private Object invokeInject(Object request, ModelServiceRegistry.ServiceType serviceType,
                                 ModelRouterProperties.ModelInstance instance) throws Exception {
        java.lang.reflect.Method method = StreamingRequestProcessor.class.getDeclaredMethod(
                "injectStreamOptionsIfNeeded", Object.class, ModelServiceRegistry.ServiceType.class,
                ModelRouterProperties.ModelInstance.class);
        method.setAccessible(true);
        return method.invoke(processor, request, serviceType, instance);
    }
}
