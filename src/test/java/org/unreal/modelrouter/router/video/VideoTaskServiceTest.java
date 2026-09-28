package org.unreal.modelrouter.router.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.billing.BalanceCheckService;
import org.unreal.modelrouter.billing.ModelPricingService;
import org.unreal.modelrouter.billing.ModelPricingService.ModelPricing;
import org.unreal.modelrouter.billing.ModelPricingService.ModelPricing.VideoPriceRule;
import org.unreal.modelrouter.common.dto.VideoGenerationRequest;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VideoTaskService} 单元测试。
 *
 * 上游 HTTP 用 JDK 内置 HttpServer 模拟（无需额外依赖）；
 * TracingWebClientFactory 无 Spring 上下文时自动降级为普通 WebClient。
 */
@ExtendWith(MockitoExtension.class)
class VideoTaskServiceTest {

    private static final String MODEL = "doubao-seedance-2-5-251215";

    @Mock
    private ModelServiceRegistry registry;
    @Mock
    private BalanceCheckService balanceCheckService;
    @Mock
    private VideoTaskArchiver archiver;
    @Mock
    private ModelPricingService pricingService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer upstream;
    private VideoTaskService service;

    @BeforeEach
    void setUp() {
        service = new VideoTaskService(registry, balanceCheckService, archiver, objectMapper, pricingService);
    }

    @AfterEach
    void tearDown() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    // ==================== 参数校验 ====================

    @Test
    void createTask_nullRequest_returns400() {
        StepVerifier.create(service.createTask(null, null))
                .assertNext(resp -> {
                    assertThat(resp.getStatusCode().value()).isEqualTo(400);
                    assertThat(resp.getBody().toString()).contains("\"error\"");
                })
                .verifyComplete();
    }

    @Test
    void createTask_missingModel_returns400() {
        VideoGenerationRequest request = new VideoGenerationRequest();

        StepVerifier.create(service.createTask(request, null))
                .assertNext(resp -> {
                    assertThat(resp.getStatusCode().value()).isEqualTo(400);
                    assertThat(resp.getBody().toString()).contains("model is required");
                })
                .verifyComplete();
    }

    // ==================== 路由与余额校验 ====================

    @Test
    void createTask_noAvailableInstance_returns404() {
        VideoGenerationRequest request = newRequest();
        when(registry.selectInstance(eq(ModelServiceRegistry.ServiceType.vidGen), eq(MODEL), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "无可用实例"));

        StepVerifier.create(service.createTask(request, null))
                .assertNext(resp -> {
                    assertThat(resp.getStatusCode().value()).isEqualTo(404);
                    assertThat(resp.getBody().toString()).contains("model_not_found");
                })
                .verifyComplete();
    }

    @Test
    void createTask_insufficientBalance_returns402() {
        VideoGenerationRequest request = newRequest();
        stubInstanceSelection("http://localhost:1");
        when(balanceCheckService.checkBalance(any(), anyString()))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "余额不足")));

        StepVerifier.create(service.createTask(request, null))
                .assertNext(resp -> {
                    assertThat(resp.getStatusCode().value()).isEqualTo(402);
                    assertThat(resp.getBody().toString()).contains("insufficient_quota");
                })
                .verifyComplete();
    }

    // ==================== 上游成功：OpenAI 风格响应 + 留档 ====================

    @Test
    void createTask_upstreamSuccess_returnsGatewayTaskNoAndArchives() throws Exception {
        startUpstream(200, "{\"id\":\"ark-task-123\",\"status\":\"queued\",\"model\":\"" + MODEL + "\"}");
        VideoGenerationRequest request = newRequest();

        ResponseEntity<?> resp = submit(request);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(resp.getBody().toString());
        // 对外只暴露网关任务号，不泄露上游任务 ID
        assertThat(body.path("id").asText()).startsWith("vidtask_");
        assertThat(body.path("id").asText()).doesNotContain("ark-task-123");
        assertThat(body.path("object").asText()).isEqualTo("video.generation.task");
        assertThat(body.path("created_at").isIntegralNumber()).isTrue();
        assertThat(body.path("model").asText()).isEqualTo(MODEL);
        assertThat(body.path("status").asText()).isEqualTo("queued");

        // 异步留档：网关任务号 ↔ 上游任务 ID 映射
        verify(archiver, timeout(3000)).archiveSubmitted(
                anyString(), eq("ark-task-123"), eq("queued"), eq(MODEL),
                any(), any(), anyString(), any(), isNull(), isNull());
    }

    // ==================== 上游 4xx：错误透传 + 失败留档 ====================

    @Test
    void createTask_upstreamError_passThroughStatusAndArchivesFailure() throws Exception {
        startUpstream(400, "{\"error\":{\"message\":\"Invalid parameter: frames\",\"code\":\"InvalidParameter\"}}");
        VideoGenerationRequest request = newRequest();

        ResponseEntity<?> resp = submit(request);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        JsonNode body = objectMapper.readTree(resp.getBody().toString());
        assertThat(body.path("error").path("message").asText()).isEqualTo("Invalid parameter: frames");
        assertThat(body.path("error").path("code").asText()).isEqualTo("InvalidParameter");

        verify(archiver, timeout(3000)).archiveSubmitFailed(
                anyString(), eq(MODEL), any(), any(), eq("400"),
                eq("Invalid parameter: frames"), any(), isNull(), isNull());
        verify(archiver, never()).archiveSubmitted(
                anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), any(), any(), any());
    }

    // ==================== 计费规则组合校验（提交时刻拦截） ====================

    @Test
    void createTask_conditionalPricing_missingVideoInput_returns400_mustUploadVideo() {
        // 条件定价仅配置 720P×有视频输入 规则：未上传视频提交 → 拦截「必须上传视频」
        VideoGenerationRequest request = newRequest();
        stubInstanceSelection("http://localhost:1");
        when(pricingService.getPrice(eq(MODEL), any()))
                .thenReturn(pricing(2, "second", rule("720P", true)));

        StepVerifier.create(service.createTask(request, null))
                .assertNext(resp -> {
                    assertThat(resp.getStatusCode().value()).isEqualTo(400);
                    assertThat(resp.getBody().toString()).contains("仅支持有视频输入，必须上传视频");
                })
                .verifyComplete();
        verify(archiver, never()).archiveSubmitted(
                anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void createTask_conditionalPricing_videoInputForbidden_returns400_cannotUploadVideo() {
        // 条件定价仅配置 720P×无视频输入 规则：上传视频提交 → 拦截「不能上传视频」
        VideoGenerationRequest request = newRequestWithVideo();
        stubInstanceSelection("http://localhost:1");
        when(pricingService.getPrice(eq(MODEL), any()))
                .thenReturn(pricing(2, "second", rule("720P", false)));

        StepVerifier.create(service.createTask(request, null))
                .assertNext(resp -> {
                    assertThat(resp.getStatusCode().value()).isEqualTo(400);
                    assertThat(resp.getBody().toString()).contains("仅支持无视频输入，不能上传视频");
                })
                .verifyComplete();
    }

    @Test
    void createTask_conditionalPricing_resolutionWithoutAnyRule_returns400() {
        // 条件定价但该分辨率完全无规则行（有视频/无视频均未配置）→ 拦截
        VideoGenerationRequest request = newRequest();
        stubInstanceSelection("http://localhost:1");
        when(pricingService.getPrice(eq(MODEL), any()))
                .thenReturn(pricing(2, "second", rule("1080P", false), rule("1080P", true)));

        StepVerifier.create(service.createTask(request, null))
                .assertNext(resp -> {
                    assertThat(resp.getStatusCode().value()).isEqualTo(400);
                    assertThat(resp.getBody().toString()).contains("不支持该调用组合");
                })
                .verifyComplete();
    }

    @Test
    void createTask_unifiedPricing_unsupportedResolution_returns400() {
        // 统一价格模型：请求分辨率无启用规则 → 拦截「不支持该分辨率」
        VideoGenerationRequest request = newRequest();
        stubInstanceSelection("http://localhost:1");
        when(pricingService.getPrice(eq(MODEL), any()))
                .thenReturn(pricing(1, "second", rule("1080P", null)));

        StepVerifier.create(service.createTask(request, null))
                .assertNext(resp -> {
                    assertThat(resp.getStatusCode().value()).isEqualTo(400);
                    assertThat(resp.getBody().toString()).contains("不支持 720P 分辨率");
                })
                .verifyComplete();
    }

    @Test
    void createTask_conditionalPricing_exactCombination_passesToUpstream() throws Exception {
        // 条件定价且组合命中（720P×无视频输入）→ 放行走上游
        startUpstream(200, "{\"id\":\"ark-task-123\",\"status\":\"queued\",\"model\":\"" + MODEL + "\"}");
        when(pricingService.getPrice(eq(MODEL), any()))
                .thenReturn(pricing(2, "second", rule("720P", false)));

        ResponseEntity<?> resp = submit(newRequest());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(archiver, timeout(3000)).archiveSubmitted(
                anyString(), eq("ark-task-123"), eq("queued"), eq(MODEL),
                any(), any(), anyString(), any(), isNull(), isNull());
    }

    @Test
    void createTask_pricingMissing_passesToUpstream() throws Exception {
        // 平台未配置定价（pricing 为 null）→ 放行，结算链路兜底
        startUpstream(200, "{\"id\":\"ark-task-123\",\"status\":\"queued\",\"model\":\"" + MODEL + "\"}");

        ResponseEntity<?> resp = submit(newRequest());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void createTask_pricingCheckThrows_passesToUpstream() throws Exception {
        // 校验自身故障（定价服务异常）不阻塞提交：放行走上游，结算链路拒计费兑底
        startUpstream(200, "{\"id\":\"ark-task-123\",\"status\":\"queued\",\"model\":\"" + MODEL + "\"}");
        when(pricingService.getPrice(eq(MODEL), any()))
                .thenThrow(new IllegalStateException("定价缓存故障"));

        ResponseEntity<?> resp = submit(newRequest());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    // ==================== 辅助 ====================

    private static VideoGenerationRequest newRequest() {
        VideoGenerationRequest request = new VideoGenerationRequest();
        request.setModel(MODEL);
        request.setResolution("720p");
        request.setRatio("16:9");
        request.setDuration(5);
        // 纯文本 content：判定为「无视频输入」（区别于 content 缺失时的无法判定）
        ObjectNode textItem = new ObjectMapper().createObjectNode();
        textItem.put("type", "text");
        textItem.put("text", "生成一段海浪视频");
        ArrayNode content = new ObjectMapper().createArrayNode();
        content.add(textItem);
        request.setContent(content);
        return request;
    }

    /** content 含 type=video_url 项：判定为「有视频输入」 */
    private static VideoGenerationRequest newRequestWithVideo() {
        VideoGenerationRequest request = newRequest();
        ObjectNode videoItem = new ObjectMapper().createObjectNode();
        videoItem.put("type", "video_url");
        videoItem.putObject("video_url").put("url", "https://example.com/input.mp4");
        ArrayNode content = new ObjectMapper().createArrayNode();
        content.add(videoItem);
        request.setContent(content);
        return request;
    }

    /** 构造视频模型定价（priceMode=1 统一价格 / 2 按条件定价） */
    private static ModelPricing pricing(final int priceMode, final String billingUnit,
                                        final VideoPriceRule... rules) {
        return new ModelPricing(MODEL, "19", 37L, "volcengine", 1,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                false, false, false, false, false,
                1, BigDecimal.ZERO, BigDecimal.ONE, List.of(),
                priceMode, billingUnit, Arrays.asList(rules));
    }

    private static VideoPriceRule rule(final String resolution, final Boolean hasVideoInput) {
        return new VideoPriceRule(resolution, hasVideoInput, new BigDecimal("0.1"));
    }

    private void stubInstanceSelection(final String baseUrl) {
        ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
        instance.setName("ark-test");
        instance.setBaseUrl(baseUrl);
        instance.setVendor("volcengine");
        instance.setChannelId("19");
        when(registry.selectInstance(eq(ModelServiceRegistry.ServiceType.vidGen), eq(MODEL), any()))
                .thenReturn(instance);
        when(registry.getModelPath(eq(ModelServiceRegistry.ServiceType.vidGen), eq(MODEL)))
                .thenReturn("");
    }

    private ResponseEntity<?> submit(final VideoGenerationRequest request) {
        when(balanceCheckService.checkBalance(any(), anyString())).thenReturn(Mono.empty());
        return service.createTask(request, null).block(java.time.Duration.ofSeconds(10));
    }

    private void startUpstream(final int status, final String body) throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        upstream.start();
        stubInstanceSelection("http://127.0.0.1:" + upstream.getAddress().getPort());
    }
}
