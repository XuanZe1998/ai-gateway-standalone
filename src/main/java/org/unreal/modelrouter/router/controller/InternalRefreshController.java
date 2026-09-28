// 文件说明：InternalRefreshController：负责模型路由与请求转发中的HTTP 接口处理。
package org.unreal.modelrouter.router.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.auth.security.cache.impl.PlatformAuthCache;
import org.unreal.modelrouter.billing.ModelPricingService;
import org.unreal.modelrouter.billing.notification.InternalRequestSigner;
import org.unreal.modelrouter.billing.notification.NotificationProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/internal")
public class InternalRefreshController {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalRefreshController.class);

    /** 签名有效时间窗口：5 分钟 */
    private static final long TIMESTAMP_TOLERANCE_MS = 5 * 60 * 1000L;

    private final ModelServiceRegistry registry;
    private final ModelPricingService pricingService;
    private final PlatformAuthCache platformAuthCache;
    private final NotificationProperties notificationProperties;

    /** 防抖：同时只允许一个刷新操作执行 */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public InternalRefreshController(ModelServiceRegistry registry,
                                     ModelPricingService pricingService,
                                     PlatformAuthCache platformAuthCache,
                                     NotificationProperties notificationProperties) {
        this.registry = registry;
        this.pricingService = pricingService;
        this.platformAuthCache = platformAuthCache;
        this.notificationProperties = notificationProperties;
    }

    /**
     * HMAC-SHA256 签名校验。
     * 校验 X-Internal-Timestamp 和 X-Internal-Signature 头，
     * 与算力平台通信复用同一套签名机制（InternalRequestSigner + NotificationProperties.internalSecret）。
     *
     * @param timestamp  请求时间戳（毫秒）
     * @param signature  HMAC-SHA256 签名
     * @param body       请求体（可为空字符串）
     * @return 校验通过返回 null，失败返回错误 ResponseEntity
     */
    private ResponseEntity<String> verifySignature(String timestamp, String signature, String body) {
        String secret = notificationProperties.getInternalSecret();

        // 密钥未配置时跳过校验（开发/测试环境兼容）
        if (secret == null || secret.isBlank()) {
            LOGGER.warn("内部接口密钥未配置，跳过签名校验（请配置 INTERNAL_NOTIFY_SECRET）");
            return null;
        }

        // 缺少必要头
        if (timestamp == null || timestamp.isBlank() || signature == null || signature.isBlank()) {
            LOGGER.warn("签名校验失败：缺少 X-Internal-Timestamp 或 X-Internal-Signature 头");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("missing signature headers");
        }

        // 时间戳有效期检查（防重放）
        try {
            long ts = Long.parseLong(timestamp);
            long diff = Math.abs(System.currentTimeMillis() - ts);
            if (diff > TIMESTAMP_TOLERANCE_MS) {
                LOGGER.warn("签名校验失败：时间戳过期, diff={}ms", diff);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body("timestamp expired");
            }
        } catch (NumberFormatException e) {
            LOGGER.warn("签名校验失败：时间戳格式错误, timestamp={}", timestamp);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("invalid timestamp");
        }

        // HMAC 签名比对（使用常量时间比较，防止时序攻击）
        String expected = InternalRequestSigner.sign(secret, timestamp, body);
        if (!java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                signature.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            LOGGER.warn("签名校验失败：签名不匹配");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("invalid signature");
        }

        return null; // 校验通过
    }

    @PostMapping("/refresh")
    public Mono<ResponseEntity<String>> refreshConfig(
            @RequestHeader(value = "X-Internal-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Internal-Signature", required = false) String signature) {

        ResponseEntity<String> authError = verifySignature(timestamp, signature, "");
        if (authError != null) {
            return Mono.just(authError);
        }

        if (!refreshing.compareAndSet(false, true)) {
            LOGGER.warn("Refresh request rejected: another refresh is already in progress");
            return Mono.just(ResponseEntity.status(429).body("refresh already in progress"));
        }

        LOGGER.info("Received refresh request, reloading config and pricing");

        return Mono.fromRunnable(() -> {
                    // 1. 刷新本地配置（含算力平台实例合并）
                    registry.refreshFromMergedConfig();
                    // 2. 刷新定价（从算力平台 ai_model 同步）
                    pricingService.refreshPricing();
                    // 3. 清除 API Key 认证缓存（算力平台 API Key/用户/企业关联变更后需重新认证）
                    platformAuthCache.clear();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> refreshing.set(false))
                .thenReturn(ResponseEntity.ok("refreshed"))
                .onErrorResume(e -> {
                    LOGGER.error("Refresh failed", e);
                    return Mono.just(ResponseEntity
                            .internalServerError()
                            .body("refresh failed: " + e.getMessage()));
                });
    }

    @PostMapping("/refresh/platform")
    public Mono<ResponseEntity<String>> refreshPlatformOnly(
            @RequestHeader(value = "X-Internal-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Internal-Signature", required = false) String signature) {

        ResponseEntity<String> authError = verifySignature(timestamp, signature, "");
        if (authError != null) {
            return Mono.just(authError);
        }

        if (!refreshing.compareAndSet(false, true)) {
            LOGGER.warn("Platform refresh request rejected: another refresh is already in progress");
            return Mono.just(ResponseEntity.status(429).body("refresh already in progress"));
        }

        LOGGER.info("Received platform-only refresh request");

        return Mono.fromRunnable(() -> {
                    // 1. 仅从算力平台同步实例（不重新加载本地 YAML/DB 配置）
                    registry.refreshFromPlatformOnly();
                    // 2. 同步刷新定价（平台数据变更通常同时涉及实例和定价）
                    pricingService.refreshPricing();
                    // 3. 清除 API Key 认证缓存（算力平台 API Key/用户/企业关联变更后需重新认证）
                    platformAuthCache.clear();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> refreshing.set(false))
                .thenReturn(ResponseEntity.ok("platform refreshed"))
                .onErrorResume(e -> {
                    LOGGER.error("Platform refresh failed", e);
                    return Mono.just(ResponseEntity
                            .internalServerError()
                            .body("platform refresh failed: " + e.getMessage()));
                });
    }
}
