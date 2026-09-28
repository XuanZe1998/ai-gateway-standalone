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
import org.unreal.modelrouter.common.util.ApplicationContextProvider;
import org.unreal.modelrouter.common.util.IpUtils;
import org.unreal.modelrouter.monitor.tracing.TracingConstants;
import org.unreal.modelrouter.monitor.tracing.TracingContext;
import org.unreal.modelrouter.persistence.jpa.entity.VideoTaskEntity;
import org.unreal.modelrouter.persistence.jpa.repository.VideoTaskRepository;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.protocol.ProtocolErrorHandler;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * 视频生成任务查询服务（GET /v1/videos/generations/task/{taskId}，第二阶段）。
 *
 * 链路：留档表按网关任务号反查（归属校验）→ 本地已终态直出快照 →
 * 否则 GET 上游方舟查询任务 → 响应改写（id→网关任务号，注入 object）→
 * 终态结算（{@link VideoTaskSettler}：succeeded 首次计费/失败回写/中间态刷新）。
 *
 * 设计要点：
 *  - 查询免费，不做余额预检；计费在首次观测到 succeeded 时延迟结算；
 *  - 归属不符与任务不存在统一 404（防止任务号探测）；
 *  - 终态后一律不再打上游（防上游 7 天记录过期 404、防重复结算）；
 *  - 上游实例优先按留档 instance_id 精确匹配，保证 API Key 与任务归属渠道一致；
 *  - JPA 阻塞操作全部经 boundedElastic 调度，不占用 Netty 事件循环线程。
 */
@Service
public class VideoTaskQueryService {

    private static final Logger logger = LoggerFactory.getLogger(VideoTaskQueryService.class);

    /** 查询为轻量操作，超时短于创建提交 */
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(30);

    /** 终态集合：到达终态后直出快照，不再打上游 */
    private static final Set<String> TERMINAL_STATUSES =
            Set.of("succeeded", "failed", "expired", "cancelled");

    private final VideoTaskRepository repository;
    private final VideoTaskUpstreamHelper upstreamHelper;
    private final VideoTaskSettler settler;
    private final ObjectMapper objectMapper;

    public VideoTaskQueryService(final VideoTaskRepository repository,
                                 final VideoTaskUpstreamHelper upstreamHelper,
                                 final VideoTaskSettler settler,
                                 final ObjectMapper objectMapper) {
        this.repository = repository;
        this.upstreamHelper = upstreamHelper;
        this.settler = settler;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询视频生成任务。
     *
     * @param taskNo   网关任务号（vidtask_*，创建接口返回的对外 ID）
     * @param exchange 当前请求（提取客户端 IP 与 traceId）
     * @return 改写后的上游任务资源对象（OpenAI 风格）；错误为 OpenAI error 格式
     */
    public Mono<ResponseEntity<?>> queryTask(final String taskNo, final ServerWebExchange exchange) {
        if (taskNo == null || taskNo.isBlank()) {
            return Mono.just(errorEntity(400, "task id is required", null));
        }
        final String clientIp = exchange != null ? IpUtils.getClientIp(exchange.getRequest()) : null;
        final String traceId = resolveTraceId(exchange);

        return Mono.deferContextual(ctx -> {
            UserIdentity identity = ctx.getOrDefault(UserIdentity.CONTEXT_KEY, UserIdentity.SYSTEM);
            return Mono.fromCallable(() -> repository.findByTaskNoAndDeletedFalse(taskNo))
                    .subscribeOn(Schedulers.boundedElastic())
                    .<ResponseEntity<?>>flatMap(opt -> opt
                            .map(entity -> handleEntity(entity, identity, clientIp, traceId))
                            .orElseGet(() -> Mono.just(taskNotFound(taskNo))))
                    .onErrorResume(t -> Mono.just(errorEntity(t)));
        });
    }

    // ==================== 留档处理 ====================

    private Mono<ResponseEntity<?>> handleEntity(final VideoTaskEntity entity, final UserIdentity identity,
                                                 final String clientIp, final String traceId) {
        // 归属校验：不符与不存在统一 404，防止任务号探测
        if (!Objects.equals(entity.getUserId(), identity.userId())) {
            return Mono.just(taskNotFound(entity.getTaskNo()));
        }
        // 终态直出：不再打上游（防上游 7 天记录过期 404、防 succeeded 重复结算）
        if (TERMINAL_STATUSES.contains(entity.getStatus())) {
            return Mono.just(respondFromSnapshot(entity));
        }
        // 一阶段提交成功但上游任务 ID 解析失败的遗留数据，无法查询上游
        if (entity.getUpstreamTaskId() == null || entity.getUpstreamTaskId().isBlank()) {
            return Mono.just(errorEntity(502,
                    "上游任务 ID 缺失，无法查询上游，请联系管理员对账", "upstream_task_missing"));
        }
        final ModelRouterProperties.ModelInstance instance =
                upstreamHelper.resolveInstance(entity, clientIp, "查询链路");
        if (instance == null) {
            return Mono.just(errorEntity(503, "上游渠道暂不可用，请稍后重试", "upstream_unavailable"));
        }
        return queryUpstream(entity, instance, identity, clientIp, traceId);
    }

    /**
     * 终态直出响应：优先终态快照 → 创建响应快照（改写 id）→ 留档字段合成最小响应。
     */
    private ResponseEntity<?> respondFromSnapshot(final VideoTaskEntity entity) {
        // 终态快照内容即改写后的对外响应，原样直出
        if (entity.getResponseSnapshot() != null && !entity.getResponseSnapshot().isBlank()) {
            return jsonEntity(entity.getResponseSnapshot());
        }
        // 兜底一：创建响应快照（改写 id 防上游任务 ID 泄露，status 以留档最新状态为准）
        if (entity.getFirstResponseSnapshot() != null && !entity.getFirstResponseSnapshot().isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(entity.getFirstResponseSnapshot());
                if (node.isObject()) {
                    ObjectNode obj = (ObjectNode) node;
                    obj.put("id", entity.getTaskNo());
                    obj.put("object", "video.generation.task");
                    obj.put("status", entity.getStatus());
                    // 上游创建响应若带 task_id 字段，一并移除防上游任务 ID 泄露
                    obj.remove("task_id");
                    return jsonEntity(obj.toString());
                }
            } catch (Exception e) {
                logger.warn("视频任务首次响应快照解析失败，改用合成响应: taskNo={}", entity.getTaskNo());
            }
        }
        // 兜底二：合成最小响应（如一阶段 archiveSubmitFailed 落的提交失败记录，两类快照皆无）
        ObjectNode result = objectMapper.createObjectNode();
        result.put("id", entity.getTaskNo());
        result.put("object", "video.generation.task");
        result.put("status", entity.getStatus());
        if (entity.getErrorCode() != null || entity.getErrorMessage() != null) {
            ObjectNode error = result.putObject("error");
            if (entity.getErrorCode() != null) {
                error.put("code", entity.getErrorCode());
            }
            if (entity.getErrorMessage() != null) {
                error.put("message", entity.getErrorMessage());
            }
        }
        return jsonEntity(result.toString());
    }

    // ==================== 上游查询 ====================

    private Mono<ResponseEntity<?>> queryUpstream(final VideoTaskEntity entity,
                                                  final ModelRouterProperties.ModelInstance instance,
                                                  final UserIdentity identity,
                                                  final String clientIp, final String traceId) {
        final String baseUrl = instance.getBaseUrl() != null && !instance.getBaseUrl().isBlank()
                ? instance.getBaseUrl() : entity.getBaseUrl();
        final String queryPath = upstreamHelper.resolveQueryPath(entity);
        // 排查留痕：DEBUG 记录实际请求地址与留档地址，便于对比创建链路（鉴权失败时定位实例是否被换）
        logger.debug("视频任务查询请求上游: taskNo={}, baseUrl={}, path={}, 留档baseUrl={}",
                entity.getTaskNo(), baseUrl, queryPath, entity.getBaseUrl());

        WebClient client = buildWebClient(baseUrl);
        WebClient.RequestHeadersSpec<?> spec = client.get()
                .uri(queryPath)
                .accept(MediaType.APPLICATION_JSON);
        Map<String, String> headers = instance.getHeaders();
        if (headers != null) {
            headers.forEach((key, value) -> {
                if (!"accept".equalsIgnoreCase(key)) {
                    spec.header(key, value);
                }
            });
        }

        return spec.exchangeToMono(resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> handleUpstreamResponse(entity, resp.statusCode().value(),
                                body, identity, clientIp, traceId)))
                .timeout(QUERY_TIMEOUT)
                .onErrorResume(TimeoutException.class, e -> {
                    logger.error("视频任务上游查询超时: taskNo={}", entity.getTaskNo());
                    return Mono.error(new ResponseStatusException(
                            HttpStatus.GATEWAY_TIMEOUT, "上游服务响应超时，请稍后重试"));
                });
    }

    private Mono<ResponseEntity<?>> handleUpstreamResponse(final VideoTaskEntity entity, final int status,
                                                           final String body, final UserIdentity identity,
                                                           final String clientIp, final String traceId) {
        if (status == 404) {
            // 上游记录已过期（仅保留 7 天）或不存在：本地置 expired 止损，后续查询走终态直出
            return Mono.<ResponseEntity<?>>fromCallable(() -> {
                        settler.markExpired(entity, "上游任务记录不存在或已过期（上游仅保留最近 7 天）");
                        return errorEntity(404, "任务记录不存在或已过期（上游仅保留最近 7 天）", "task_not_found");
                    })
                    .subscribeOn(Schedulers.boundedElastic());
        }
        if (status < 200 || status >= 300) {
            // 上游 4xx/5xx：错误体转 OpenAI 格式透传状态码，不变更本地状态；
            // 原始响应体留痕 ERROR 日志（截断 500，防 WAF HTML 挑战页刷屏），便于定位上游拒绝原因
            logger.error("视频任务上游查询失败: taskNo={}, status={}, body={}",
                    entity.getTaskNo(), status, truncate(body, 500));
            return Mono.just(upstreamErrorEntity(body, status));
        }

        final JsonNode upstreamResp;
        try {
            upstreamResp = objectMapper.readTree(body);
        } catch (Exception e) {
            logger.error("视频任务上游响应解析失败: taskNo={}", entity.getTaskNo());
            return Mono.just(errorEntity(502, "上游服务响应解析失败", "invalid_upstream_response"));
        }

        // 响应改写：id → 网关任务号，注入 object（不泄露上游任务 ID）；
        // 上游响应可能同时带 task_id 字段（与 id 同值），一并移除防泄露
        final ObjectNode transformed = upstreamResp.isObject()
                ? (ObjectNode) upstreamResp : objectMapper.createObjectNode();
        transformed.put("id", entity.getTaskNo());
        transformed.put("object", "video.generation.task");
        transformed.remove("task_id");
        final String responseBody = transformed.toString();

        // 终态结算（succeeded 首次计费 / 失败回写 / 中间态刷新），响应前同步完成
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
                    settler.settle(entity, upstreamResp, responseBody, identity, clientIp, traceId);
                    return jsonEntity(responseBody);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ==================== 错误响应 ====================

    private ResponseEntity<?> taskNotFound(final String taskNo) {
        return errorEntity(404, "视频生成任务不存在: " + taskNo, "task_not_found");
    }

    /**
     * 上游 4xx/5xx：解析上游错误体（优先 error.message / error.code，兼容顶层 message/code），
     * 转 OpenAI error 格式并透传状态码（与一阶段创建链路同手法）。
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

    private ResponseEntity<?> errorEntity(final Throwable t) {
        return errorEntity(ProtocolErrorHandler.statusOf(t), ProtocolErrorHandler.messageOf(t), null);
    }

    private ResponseEntity<?> errorEntity(final int status, final String message, final String code) {
        var body = code != null
                ? ProtocolErrorHandler.openAiError(objectMapper, status, message, code)
                : ProtocolErrorHandler.openAiError(objectMapper, status, message);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body.toString());
    }

    /** 日志留痕用截断（防上游异常大响应体刷屏） */
    private static String truncate(final String text, final int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }

    // ==================== 辅助 ====================

    private ResponseEntity<?> jsonEntity(final String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private WebClient buildWebClient(final String baseUrl) {
        try {
            var tracingFactory = ApplicationContextProvider.getBean(
                    org.unreal.modelrouter.monitor.tracing.client.TracingWebClientFactory.class);
            return tracingFactory.createTracingWebClient(baseUrl);
        } catch (Exception e) {
            return WebClient.builder().baseUrl(baseUrl).build();
        }
    }

    /** 从请求关联的 TracingContext 提取 traceId（与一阶段创建链路一致，无则 null） */
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
