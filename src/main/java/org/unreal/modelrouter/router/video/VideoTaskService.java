package org.unreal.modelrouter.router.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.billing.BalanceCheckService;
import org.unreal.modelrouter.billing.ModelPricingService;
import org.unreal.modelrouter.common.dto.VideoGenerationRequest;
import org.unreal.modelrouter.common.util.ApplicationContextProvider;
import org.unreal.modelrouter.common.util.IpUtils;
import org.unreal.modelrouter.monitor.tracing.TracingConstants;
import org.unreal.modelrouter.monitor.tracing.TracingContext;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.protocol.ProtocolErrorHandler;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * 视频生成任务创建服务（POST /v1/videos/generations，第一阶段）。
 *
 * 链路：余额预检 → 实例选择 → 原样 POST 上游创建任务 → 生成网关任务号对外返回 →
 * 异步留档（{@link VideoTaskArchiver}，fire-and-forget，失败仅告警）。
 *
 * 设计要点：
 *  - 创建不计费（业界主流：异步提交不计费，第二阶段查询 succeeded 时按用量结算）；
 *  - 对外仅暴露网关任务号（vidtask_&lt;uuid&gt;），上游任务 ID 只落留档表，防渠道信息泄露；
 *  - 请求经 DTO 类型化 + AnySetter 兜底后 valueToTree 全量透传，未传的可选字段
 *    递归去除 null，避免向上游发送显式 null 触发强校验；
 *  - 响应为 OpenAI 资源对象风格；错误统一 OpenAI error 格式透传上游状态码。
 */
@Service
public class VideoTaskService {

    private static final Logger logger = LoggerFactory.getLogger(VideoTaskService.class);

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private final ModelServiceRegistry registry;
    private final BalanceCheckService balanceCheckService;
    private final VideoTaskArchiver archiver;
    private final ObjectMapper objectMapper;
    private final ModelPricingService pricingService;

    public VideoTaskService(final ModelServiceRegistry registry,
                            final BalanceCheckService balanceCheckService,
                            final VideoTaskArchiver archiver,
                            final ObjectMapper objectMapper,
                            final ModelPricingService pricingService) {
        this.registry = registry;
        this.balanceCheckService = balanceCheckService;
        this.archiver = archiver;
        this.objectMapper = objectMapper;
        this.pricingService = pricingService;
    }

    /**
     * 创建视频生成任务。
     *
     * @param request 类型化请求 DTO（未知字段经 AnySetter 保留，全量透传上游）
     * @param exchange 当前请求（提取客户端 IP 与 traceId）
     * @return OpenAI 风格任务资源对象；错误为 OpenAI error 格式
     */
    public Mono<ResponseEntity<?>> createTask(final VideoGenerationRequest request,
                                              final ServerWebExchange exchange) {
        if (request == null) {
            return Mono.just(errorEntity(400, "Request body is required", null));
        }
        final ObjectNode requestNode = objectMapper.valueToTree(request);
        stripNulls(requestNode);

        final String model = request.getModel();
        if (model == null || model.isBlank()) {
            return Mono.just(errorEntity(400, "model is required", null));
        }

        final String clientIp = exchange != null ? IpUtils.getClientIp(exchange.getRequest()) : null;
        final String traceId = resolveTraceId(exchange);
        final String taskNo = "vidtask_" + UUID.randomUUID().toString().replace("-", "");

        final ModelRouterProperties.ModelInstance instance;
        final String path;
        try {
            instance = registry.selectInstance(ModelServiceRegistry.ServiceType.vidGen, model, clientIp);
            path = registry.getModelPath(ModelServiceRegistry.ServiceType.vidGen, model);
        } catch (Exception e) {
            logger.error("视频任务实例选择失败: model={}", model, e);
            // 无可用实例等路由异常统一转 OpenAI error 格式（如 404 model_not_found）
            return Mono.just(errorEntity(e));
        }

        // 计费规则组合校验（提交时刻拦截）：按「输出分辨率 × 有无视频输入」匹配该模型当前
        // 启用的视频价格规则，组合缺失直接 400——把结算时才发现并拒计费的组合提前到提交时拦截，
        // 用户提交即知该组合不可用（仅支持有视频输入 → 必须上传视频；仅支持无视频输入 → 不能上传视频）
        String combinationError;
        try {
            combinationError = validatePricingCombination(model, instance, request);
        } catch (Exception e) {
            // 校验自身故障不阻塞提交（结算链路有拒计费兜底，无资损），仅 ERROR 留痕便于排查
            logger.error("视频任务计费规则校验异常，放行提交: model={}, channelId={}",
                    model, instance.getChannelId(), e);
            combinationError = null;
        }
        if (combinationError != null) {
            logger.warn("视频任务提交被计费规则校验拦截: model={}, channelId={}, reason={}",
                    model, instance.getChannelId(), combinationError);
            return Mono.just(errorEntity(400, combinationError, null));
        }

        return Mono.deferContextual(ctx -> {
            UserIdentity identity = ctx.getOrDefault(UserIdentity.CONTEXT_KEY, UserIdentity.SYSTEM);
            return balanceCheckService.checkBalance(identity, ModelServiceRegistry.ServiceType.vidGen.name())
                    .then(Mono.defer(() -> doSubmit(taskNo, requestNode, model, instance, path,
                            identity, clientIp, traceId)));
        }).onErrorResume(t -> Mono.just(errorEntity(t)));
    }

    // ==================== 上游提交 ====================

    private Mono<ResponseEntity<?>> doSubmit(final String taskNo,
                                             final ObjectNode requestNode,
                                             final String model,
                                             final ModelRouterProperties.ModelInstance instance,
                                             final String path,
                                             final UserIdentity identity,
                                             final String clientIp,
                                             final String traceId) {
        WebClient client = buildWebClient(instance);
        WebClient.RequestBodySpec spec = buildRequestSpec(client, path, instance);

        // exchangeToMono：上游 4xx/5xx 错误体解析后按 OpenAI 格式透传状态码，
        // 不走全局信封异常处理，保持错误响应协议纯净。
        return spec.bodyValue(requestNode)
                .exchangeToMono(resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .<ResponseEntity<?>>map(body -> {
                            int status = resp.statusCode().value();
                            if (resp.statusCode().is2xxSuccessful()) {
                                return onSuccess(taskNo, model, instance, requestNode, body,
                                        identity, clientIp, traceId);
                            }
                            logger.error("视频任务上游创建失败: taskNo={}, model={}, status={}",
                                    taskNo, model, status);
                            archiveUpstreamFailure(taskNo, model, instance, requestNode, body, status,
                                    identity, clientIp, traceId);
                            return upstreamErrorEntity(body, status);
                        }))
                .timeout(getTimeout(instance))
                .onErrorResume(TimeoutException.class, e -> {
                    logger.error("视频任务上游创建超时: taskNo={}, model={}", taskNo, model);
                    archiver.archiveSubmitFailed(taskNo, model, instance, requestNode,
                            "504", "上游服务响应超时", identity, clientIp, traceId);
                    return Mono.error(new ResponseStatusException(
                            HttpStatus.GATEWAY_TIMEOUT, "上游服务响应超时，请稍后重试"));
                });
    }

    /**
     * 上游 2xx：解析上游任务 ID 与状态，异步留档映射，返回网关任务号（OpenAI 资源对象风格）。
     */
    private ResponseEntity<?> onSuccess(final String taskNo, final String model,
                                        final ModelRouterProperties.ModelInstance instance,
                                        final ObjectNode requestNode, final String body,
                                        final UserIdentity identity, final String clientIp,
                                        final String traceId) {
        String upstreamTaskId = null;
        String upstreamStatus = null;
        try {
            JsonNode respNode = objectMapper.readTree(body);
            upstreamTaskId = respNode.path("id").isTextual() ? respNode.path("id").asText() : null;
            upstreamStatus = respNode.path("status").isTextual() ? respNode.path("status").asText() : null;
        } catch (Exception e) {
            logger.warn("视频任务上游创建响应解析失败（不影响返回）: taskNo={}: {}", taskNo, e.getMessage());
        }
        if (upstreamTaskId == null || upstreamTaskId.isBlank()) {
            logger.error("视频任务上游创建响应缺少任务 ID: taskNo={}, model={}", taskNo, model);
        }
        archiver.archiveSubmitted(taskNo, upstreamTaskId, upstreamStatus, model, instance,
                requestNode, body, identity, clientIp, traceId);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("id", taskNo);
        result.put("object", "video.generation.task");
        result.put("created_at", Instant.now().getEpochSecond());
        result.put("model", model);
        result.put("status", upstreamStatus != null && !upstreamStatus.isBlank() ? upstreamStatus : "queued");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result.toString());
    }

    /**
     * 上游 4xx/5xx：解析上游错误体（优先 error.message / error.code，兼容顶层 message/code），
     * 转 OpenAI error 格式并透传状态码。
     */
    private ResponseEntity<?> upstreamErrorEntity(final String body, final int status) {
        String message = "上游服务错误";
        String code = null;
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode error = node.path("error");
            if (error.path("message").isTextual()) {
                message = error.path("message").asText();
                code = error.path("code").isTextual() ? error.path("code").asText() : null;
            } else if (node.path("message").isTextual()) {
                message = node.path("message").asText();
                code = node.path("code").isTextual() ? node.path("code").asText() : null;
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体，使用默认消息
        }
        return errorEntity(status, message, code);
    }

    private void archiveUpstreamFailure(final String taskNo, final String model,
                                        final ModelRouterProperties.ModelInstance instance,
                                        final ObjectNode requestNode, final String body, final int status,
                                        final UserIdentity identity, final String clientIp,
                                        final String traceId) {
        String errorCode = String.valueOf(status);
        String errorMessage = "上游服务错误";
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode error = node.path("error");
            if (error.path("message").isTextual()) {
                errorMessage = error.path("message").asText();
            } else if (node.path("message").isTextual()) {
                errorMessage = node.path("message").asText();
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体，使用默认消息
        }
        archiver.archiveSubmitFailed(taskNo, model, instance, requestNode,
                errorCode, errorMessage, identity, clientIp, traceId);
    }

    // ==================== 计费规则组合校验 ====================

    /**
     * 视频计费规则组合校验（提交时刻拦截，替代结算时拒计费）：
     * 按「输出分辨率 × 有无视频输入」匹配该模型当前启用的视频价格规则。
     *
     * <ul>
     *   <li>price_mode=2（按条件定价）：上传了视频（content 含 video_url）→ 须命中
     *       has_video_input=true 规则；未上传 → 须命中 false 规则。组合缺失时给出明确约束：
     *       仅支持无视频输入 → 不能上传视频；仅支持有视频输入 → 必须上传视频。</li>
     *   <li>price_mode=1（统一价格）：不区分视频输入，仅校验该分辨率是否有启用规则。</li>
     * </ul>
     *
     * 规则行来源为定价缓存（同步层 buildPricing 已过滤停用 enabled=false、逻辑删除与
     * 价格非正数行，enabled=null 视为启用），因此匹配到的行必然为启用行——校验、结算、
     * 快照三处消费同一缓存列表，口径一致。
     *
     * 以下场景放行（不拦截）：定价未命中（pricing 为 null）、非视频模型（priceMode 为 null）、
     * 无视频规则行、请求未指定分辨率（以实际输出为准）、content 无法判定视频输入（交上游校验）。
     *
     * @param model    模型名
     * @param instance 已选中的渠道实例（取其 channelId 定位定价缓存）
     * @param request  创建请求（取分辨率与 content 判定视频输入）
     * @return 校验失败时的 OpenAI error message；通过返回 null
     */
    String validatePricingCombination(final String model,
                                      final ModelRouterProperties.ModelInstance instance,
                                      final VideoGenerationRequest request) {
        final ModelPricingService.ModelPricing pricing =
                pricingService.getPrice(model, instance.getChannelId());
        if (pricing == null || pricing.getPriceMode() == null
                || pricing.getVideoPriceRules() == null || pricing.getVideoPriceRules().isEmpty()) {
            return null;
        }
        final String resolution = request.getResolution() == null || request.getResolution().isBlank()
                ? null : request.getResolution().trim().toUpperCase(Locale.ROOT);
        if (resolution == null) {
            return null;
        }
        final List<ModelPricingService.ModelPricing.VideoPriceRule> rules = pricing.getVideoPriceRules();

        if (pricing.getPriceMode() == 2) {
            // 按条件定价：上传视频 → 命中 has_video_input=true 规则；未上传 → 命中 false 规则
            final Boolean hasVideoInput = VideoTaskArchiver.hasVideoInput(request.getContent());
            if (hasVideoInput == null) {
                return null;
            }
            final boolean exactMatched = rules.stream().anyMatch(r ->
                    Objects.equals(r.outputResolution(), resolution)
                            && Boolean.valueOf(hasVideoInput).equals(r.hasVideoInput()));
            if (exactMatched) {
                return null;
            }
            // 组合缺失：按该分辨率下已配置的另一方向规则给出精确约束文案
            final boolean trueRuleExists = rules.stream().anyMatch(r ->
                    Objects.equals(r.outputResolution(), resolution) && Boolean.TRUE.equals(r.hasVideoInput()));
            final boolean falseRuleExists = rules.stream().anyMatch(r ->
                    Objects.equals(r.outputResolution(), resolution) && Boolean.FALSE.equals(r.hasVideoInput()));
            if (hasVideoInput && !trueRuleExists && falseRuleExists) {
                return "模型 " + model + " 在 " + resolution + " 分辨率下仅支持无视频输入，不能上传视频";
            }
            if (!hasVideoInput && trueRuleExists && !falseRuleExists) {
                return "模型 " + model + " 在 " + resolution + " 分辨率下仅支持有视频输入，必须上传视频";
            }
            return "模型 " + model + " 在 " + resolution
                    + " 分辨率下不支持该调用组合（该分辨率未配置有视频输入/无视频输入计费规则）";
        }
        // 统一价格（price_mode=1）：不区分视频输入，仅校验该分辨率是否有启用规则
        final boolean resolutionMatched = rules.stream()
                .anyMatch(r -> Objects.equals(r.outputResolution(), resolution));
        return resolutionMatched ? null : "模型 " + model + " 不支持 " + resolution + " 分辨率";
    }

    // ==================== 错误响应 ====================

    private ResponseEntity<?> errorEntity(final Throwable t) {
        return errorEntity(ProtocolErrorHandler.statusOf(t), ProtocolErrorHandler.messageOf(t), null);
    }

    private ResponseEntity<?> errorEntity(final int status, final String message, final String code) {
        var body = code != null
                ? ProtocolErrorHandler.openAiError(objectMapper, status, message, code)
                : ProtocolErrorHandler.openAiError(objectMapper, status, message);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body.toString());
    }

    // ==================== 辅助 ====================

    private WebClient buildWebClient(final ModelRouterProperties.ModelInstance instance) {
        String baseUrl = instance.getBaseUrl();
        try {
            var tracingFactory = ApplicationContextProvider.getBean(
                    org.unreal.modelrouter.monitor.tracing.client.TracingWebClientFactory.class);
            return tracingFactory.createTracingWebClient(baseUrl);
        } catch (Exception e) {
            return WebClient.builder().baseUrl(baseUrl).build();
        }
    }

    private WebClient.RequestBodySpec buildRequestSpec(final WebClient client,
                                                       final String path,
                                                       final ModelRouterProperties.ModelInstance instance) {
        WebClient.RequestBodySpec spec = client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);

        Map<String, String> headers = instance.getHeaders();
        if (headers != null) {
            headers.forEach((key, value) -> {
                // Content-Type / Accept 已固定，避免重复
                if (!"content-type".equalsIgnoreCase(key) && !"accept".equalsIgnoreCase(key)) {
                    spec.header(key, value);
                }
            });
        }
        return spec;
    }

    private Duration getTimeout(final ModelRouterProperties.ModelInstance instance) {
        if (instance != null && instance.getTimeout() != null && instance.getTimeout() > 0) {
            return Duration.ofSeconds(instance.getTimeout());
        }
        return DEFAULT_TIMEOUT;
    }

    /**
     * 递归去除值为 null 的字段：DTO 未传的可选字段不应以显式 null 发给上游，
     * 否则可能触发上游强校验（如方舟对未知/显式 null 字段的拒绝）。
     */
    private void stripNulls(final JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> nullFields = new ArrayList<>();
            obj.fieldNames().forEachRemaining(f -> {
                if (obj.get(f).isNull()) {
                    nullFields.add(f);
                }
            });
            nullFields.forEach(obj::remove);
            obj.fields().forEachRemaining(e -> stripNulls(e.getValue()));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                stripNulls(child);
            }
        }
    }

    /** 从请求关联的 TracingContext 提取 traceId（与 UniversalController 一致，无则 null）。 */
    private String resolveTraceId(final ServerWebExchange exchange) {
        try {
            if (exchange == null) {
                return null;
            }
            TracingContext context = exchange.getAttribute(TracingConstants.ContextKeys.TRACING_CONTEXT);
            return context != null && context.isActive() ? context.getTraceId() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
