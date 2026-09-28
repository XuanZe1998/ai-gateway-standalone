package org.unreal.modelrouter.router.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.unreal.modelrouter.common.util.ApplicationContextProvider;
import org.unreal.modelrouter.persistence.jpa.entity.VideoTaskEntity;
import org.unreal.modelrouter.persistence.jpa.repository.VideoTaskRepository;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 视频任务后台轮询调度器（防资损核心）。
 *
 * 背景：视频任务原仅靠客户端轮询触发结算（{@link VideoTaskSettler}），
 * 客户端一直不查则上游已 succeeded 的任务永远停在中间态，造成资损。
 *
 * 职责：
 * <ul>
 *   <li>每 10s 扫一批非终态任务（submitted/queued/running），
 *       按留档的 instance/baseUrl/vendor 主动打上游查询，
 *       复用 {@link VideoTaskSettler} 做终态抢占与结算（幂等）；</li>
 *   <li>上游 404 置 expired 止损（与查询链路一致）；</li>
 *   <li>超 7 天（上游记录保留上限）仍未终态的置 expired 兜底，
 *       不再打上游；</li>
 *   <li>上游持续 5xx 超阈值打 ERROR 告警（避免无限静默重试）。</li>
 * </ul>
 *
 * 幂等安全：{@link VideoTaskRepository#claimTerminal} 带非终态条件，
 * 与客户端并发查询天然互斥；调度器与查询链路不会重复计费。
 *
 * 身份重建：调度器无 UserIdentity，计费身份从留档的
 * userId/userAccount/apiKeyId/enterpriseId/userType 等字段重建，
 * 保证落账字段完整。
 *
 * 快照一致性：响应改写（id→taskNo、移除 task_id、注入 object）后落
 * response_snapshot，与查询链路快照口径一致。
 */
@Component
public class VideoTaskScheduler {

    private static final Logger logger = LoggerFactory.getLogger(VideoTaskScheduler.class);

    /** 视频任务查询超时（与查询链路一致） */
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(30);

    private final VideoTaskRepository repository;
    private final VideoTaskUpstreamHelper upstreamHelper;
    private final VideoTaskSettler settler;
    private final ObjectMapper objectMapper;

    @Value("${jairouter.video.scheduler.enabled:true}")
    private boolean enabled;

    @Value("${jairouter.video.scheduler.batch-size:100}")
    private int batchSize;

    @Value("${jairouter.video.scheduler.min-age-seconds:10}")
    private int minAgeSeconds;

    @Value("${jairouter.video.scheduler.ttl-days:7}")
    private int ttlDays;

    /** 上游连续 5xx 告警阈值（按厂商统计，防单任务异常拖垮整体） */
    @Value("${jairouter.video.scheduler.upstream-error-alert-threshold:10}")
    private int upstreamErrorAlertThreshold;

    /** 上游 5xx 连续失败计数器（vendor → 次数），成功时清零 */
    private final Map<String, Integer> upstreamErrorCounts = new java.util.concurrent.ConcurrentHashMap<>();

    public VideoTaskScheduler(final VideoTaskRepository repository,
                              final VideoTaskUpstreamHelper upstreamHelper,
                              final VideoTaskSettler settler,
                              final ObjectMapper objectMapper) {
        this.repository = repository;
        this.upstreamHelper = upstreamHelper;
        this.settler = settler;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${jairouter.video.scheduler.interval-ms:10000}")
    public void pollAndSettle() {
        if (!enabled) {
            return;
        }
        LocalDateTime before = LocalDateTime.now().minusSeconds(minAgeSeconds);
        LocalDateTime expireBefore = LocalDateTime.now().minusDays(ttlDays);

        List<VideoTaskEntity> tasks = repository.findNonTerminalTasks(before, PageRequest.of(0, batchSize));
        if (tasks.isEmpty()) {
            return;
        }
        logger.info("视频任务后台轮询: 命中 {} 条非终态任务", tasks.size());

        for (VideoTaskEntity entity : tasks) {
            try {
                processOne(entity, expireBefore);
            } catch (Exception e) {
                logger.error("视频任务后台轮询异常: taskNo={}: {}", entity.getTaskNo(), e.getMessage(), e);
            }
        }
    }

    private void processOne(final VideoTaskEntity entity, final LocalDateTime expireBefore) {
        // TTL 止损：超上游记录保留期限仍未终态，本地置 expired 不再打上游
        if (entity.getSubmittedAt() != null && entity.getSubmittedAt().isBefore(expireBefore)) {
            settler.markExpired(entity, "后台轮询超 TTL（上游记录保留 " + ttlDays + " 天）仍未终态，置过期");
            return;
        }
        // 上游任务 ID 缺失：无法查询，打 ERROR 留痕（与查询链路 502 口径一致），等待人工处理
        if (entity.getUpstreamTaskId() == null || entity.getUpstreamTaskId().isBlank()) {
            logger.error("视频任务后台轮询跳过：上游任务 ID 缺失，无法查询: taskNo={}", entity.getTaskNo());
            return;
        }

        final ModelRouterProperties.ModelInstance instance =
                upstreamHelper.resolveInstance(entity, null, "后台轮询");
        if (instance == null) {
            logger.warn("视频任务后台轮询：实例解析失败，下轮重试: taskNo={}, model={}",
                    entity.getTaskNo(), entity.getModelName());
            return;
        }

        queryUpstreamAndSettle(entity, instance)
                .timeout(QUERY_TIMEOUT)
                .subscribe(
                        unused -> { },
                        err -> logger.warn("视频任务后台轮询查询失败，下轮重试: taskNo={}: {}",
                                entity.getTaskNo(), err.getMessage())
                );
    }

    /**
     * 查询上游并触发结算（复用 settler 的终态抢占与幂等）。
     * 成功/失败都静默处理日志，不抛异常——失败下轮重试。
     */
    private Mono<Void> queryUpstreamAndSettle(final VideoTaskEntity entity,
                                               final ModelRouterProperties.ModelInstance instance) {
        final String baseUrl = instance.getBaseUrl() != null && !instance.getBaseUrl().isBlank()
                ? instance.getBaseUrl() : entity.getBaseUrl();
        final String queryPath = upstreamHelper.resolveQueryPath(entity);

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
                        .flatMap(body -> handleResponse(entity, resp.statusCode().value(), body)))
                .then();
    }

    private Mono<Void> handleResponse(final VideoTaskEntity entity, final int status, final String body) {
        return Mono.fromRunnable(() -> {
            if (status == 404) {
                clearUpstreamError(entity.getVendor());
                settler.markExpired(entity, "后台轮询：上游任务记录不存在或已过期（上游仅保留最近 " + ttlDays + " 天）");
                return;
            }
            if (status < 200 || status >= 300) {
                recordUpstreamError(entity.getVendor(), entity.getTaskNo(), status);
                return;
            }
            clearUpstreamError(entity.getVendor());
            final JsonNode upstreamResp;
            try {
                upstreamResp = objectMapper.readTree(body);
            } catch (Exception e) {
                logger.warn("视频任务后台轮询上游响应解析失败: taskNo={}", entity.getTaskNo());
                return;
            }
            // 响应改写：id → 网关任务号，注入 object，移除 task_id（与查询链路快照口径一致）
            final String snapshot = buildSnapshot(entity, upstreamResp);
            // 结算：succeeded 首次计费 / 失败回写 / 中间态刷新（幂等）
            // identity 传 null：settler 内部会从 entity 重建身份
            settler.settle(entity, upstreamResp, snapshot, null, null, null);
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).then();
    }

    /**
     * 构造对外响应快照（与查询链路口径一致）：
     * id 替换为网关任务号、注入 object、移除 task_id（防上游任务 ID 泄露）。
     */
    private String buildSnapshot(final VideoTaskEntity entity, final JsonNode upstreamResp) {
        final ObjectNode transformed = upstreamResp.isObject()
                ? (ObjectNode) upstreamResp : objectMapper.createObjectNode();
        transformed.put("id", entity.getTaskNo());
        transformed.put("object", "video.generation.task");
        transformed.remove("task_id");
        return transformed.toString();
    }

    /** 记录上游 5xx 失败，达阈值打 ERROR 告警（按 vendor 统计） */
    private void recordUpstreamError(final String vendor, final String taskNo, final int status) {
        String key = vendor != null ? vendor : "unknown";
        int count = upstreamErrorCounts.merge(key, 1, Integer::sum);
        if (count >= upstreamErrorAlertThreshold) {
            logger.error("视频任务后台轮询上游连续失败告警: vendor={}, 连续失败次数={}, 最新taskNo={}, status={}",
                    key, count, taskNo, status);
        } else {
            logger.warn("视频任务后台轮询上游返回非 2xx: taskNo={}, status={}, vendor={}, 连续失败次数={}",
                    taskNo, status, key, count);
        }
    }

    /** 上游成功时清零失败计数器 */
    private void clearUpstreamError(final String vendor) {
        String key = vendor != null ? vendor : "unknown";
        upstreamErrorCounts.remove(key);
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
}
