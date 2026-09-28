package org.unreal.modelrouter.router.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.billing.notification.InternalRequestSigner;
import org.unreal.modelrouter.billing.notification.NotificationProperties;
import org.unreal.modelrouter.persistence.jpa.entity.VideoTaskEntity;
import org.unreal.modelrouter.persistence.jpa.repository.VideoTaskRepository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 视频生成任务内部追溯查询端点（第一阶段，运维/对账用）。
 *
 * 鉴权与 {@link InternalRefreshController} 一致：X-Internal-Timestamp +
 * X-Internal-Signature（HMAC-SHA256，复用 InternalRequestSigner +
 * NotificationProperties.internalSecret，5 分钟防重放窗口）。
 *
 * 对外客户端查询 API 已于第二阶段实现：GET /api/v1/videos/generations/task/{taskId}
 * （VideoTaskQueryService），本端点仅供内部运维/对账使用。
 */
@RestController
@RequestMapping("/internal/videos")
public class InternalVideoTaskController {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalVideoTaskController.class);

    /** 签名有效时间窗口：5 分钟（与 InternalRefreshController 一致） */
    private static final long TIMESTAMP_TOLERANCE_MS = 5 * 60 * 1000L;

    private final VideoTaskRepository videoTaskRepository;
    private final NotificationProperties notificationProperties;

    public InternalVideoTaskController(final VideoTaskRepository videoTaskRepository,
                                       final NotificationProperties notificationProperties) {
        this.videoTaskRepository = videoTaskRepository;
        this.notificationProperties = notificationProperties;
    }

    /**
     * 按网关任务号查询单条留档（含上游任务 ID、路由维度、请求快照等全量字段）。
     */
    @GetMapping("/tasks/{taskNo}")
    public Mono<ResponseEntity<Object>> getByTaskNo(
            @PathVariable final String taskNo,
            @RequestHeader(value = "X-Internal-Timestamp", required = false) final String timestamp,
            @RequestHeader(value = "X-Internal-Signature", required = false) final String signature) {

        ResponseEntity<Object> authError = verifySignature(timestamp, signature);
        if (authError != null) {
            return Mono.just(authError);
        }

        return Mono.fromCallable(() -> videoTaskRepository.findByTaskNoAndDeletedFalse(taskNo))
                .subscribeOn(Schedulers.boundedElastic())
                .<ResponseEntity<Object>>map(opt -> opt
                        .<ResponseEntity<Object>>map(e -> ResponseEntity.ok()
                                .contentType(MediaType.APPLICATION_JSON).body(e))
                        .orElse(ResponseEntity.notFound().build()))
                .onErrorResume(e -> {
                    LOGGER.error("内部视频任务查询失败: taskNo={}", taskNo, e);
                    return Mono.just(ResponseEntity.internalServerError()
                            .body(Map.of("message", "query failed: " + e.getMessage())));
                });
    }

    /**
     * 分页追溯查询（所有条件均可选）：用户账号 / 模型 / 状态 / 提交时间范围。
     */
    @GetMapping("/tasks")
    public Mono<ResponseEntity<Object>> listTasks(
            @RequestParam(required = false) final String userAccount,
            @RequestParam(required = false) final String modelName,
            @RequestParam(required = false) final String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    final LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    final LocalDateTime end,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "20") final int size,
            @RequestHeader(value = "X-Internal-Timestamp", required = false) final String timestamp,
            @RequestHeader(value = "X-Internal-Signature", required = false) final String signature) {

        ResponseEntity<Object> authError = verifySignature(timestamp, signature);
        if (authError != null) {
            return Mono.just(authError);
        }

        final int safePage = Math.max(page, 0);
        final int safeSize = Math.min(Math.max(size, 1), 100);

        return Mono.fromCallable(() -> videoTaskRepository.searchWithFilters(
                        blankToNull(userAccount), blankToNull(modelName), blankToNull(status),
                        start, end,
                        PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "submittedAt"))))
                .subscribeOn(Schedulers.boundedElastic())
                .<ResponseEntity<Object>>map(result -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON).body(toPageBody(result)))
                .onErrorResume(e -> {
                    LOGGER.error("内部视频任务分页查询失败", e);
                    return Mono.just(ResponseEntity.internalServerError()
                            .body(Map.of("message", "query failed: " + e.getMessage())));
                });
    }

    // ==================== 内部方法 ====================

    /** 分页响应体：{records, total, page, size} */
    private static Map<String, Object> toPageBody(final Page<VideoTaskEntity> result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", result.getContent());
        body.put("total", result.getTotalElements());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        return body;
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * HMAC-SHA256 签名校验（GET 请求无请求体，签名内容为空字符串）。
     * 校验通过返回 null，失败返回 401 ResponseEntity。
     */
    private ResponseEntity<Object> verifySignature(final String timestamp, final String signature) {
        String secret = notificationProperties.getInternalSecret();

        // 密钥未配置时跳过校验（开发/测试环境兼容，与 InternalRefreshController 一致）
        if (secret == null || secret.isBlank()) {
            LOGGER.warn("内部接口密钥未配置，跳过签名校验（请配置 INTERNAL_NOTIFY_SECRET）");
            return null;
        }

        if (timestamp == null || timestamp.isBlank() || signature == null || signature.isBlank()) {
            LOGGER.warn("签名校验失败：缺少 X-Internal-Timestamp 或 X-Internal-Signature 头");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "missing signature headers"));
        }

        try {
            long ts = Long.parseLong(timestamp);
            long diff = Math.abs(System.currentTimeMillis() - ts);
            if (diff > TIMESTAMP_TOLERANCE_MS) {
                LOGGER.warn("签名校验失败：时间戳过期, diff={}ms", diff);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "timestamp expired"));
            }
        } catch (NumberFormatException e) {
            LOGGER.warn("签名校验失败：时间戳格式错误, timestamp={}", timestamp);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "invalid timestamp"));
        }

        // HMAC 签名比对（常量时间比较，防时序攻击）
        String expected = InternalRequestSigner.sign(secret, timestamp, "");
        if (!java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                signature.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            LOGGER.warn("签名校验失败：签名不匹配");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "invalid signature"));
        }

        return null;
    }
}
