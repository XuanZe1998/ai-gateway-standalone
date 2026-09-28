package org.unreal.modelrouter.router.video;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.billing.BillingService;
import org.unreal.modelrouter.billing.ModelPricingService;
import org.unreal.modelrouter.persistence.jpa.entity.VideoTaskEntity;
import org.unreal.modelrouter.persistence.jpa.repository.VideoTaskRepository;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 视频生成任务终态结算器（第二阶段查询链路）。
 *
 * 客户端轮询查询时，根据上游响应驱动留档表的状态流转：
 * <ul>
 *   <li>succeeded：终态抢占成功后首次计费（serviceType=vidGen，按上游实际用量
 *       组装视频计费上下文：输出分辨率 + 时长秒数 + 有无视频输入 + token），
 *       并回写 billing_record_id；</li>
 *   <li>failed/expired/cancelled：终态抢占 + 回写错误信息与响应快照；</li>
 *   <li>queued/running：仅刷新留档中间态（供内部追溯）。</li>
 * </ul>
 *
 * 幂等核心：{@link VideoTaskRepository#claimTerminal} 的 UPDATE 带
 * 「status 非终态」条件，DB 串行化保证并发轮询下只有一方抢占成功，
 * succeeded 计费因此只发生一次。
 *
 * 本类方法均为阻塞 DB 操作，调用方（{@link VideoTaskQueryService}）
 * 保证在 boundedElastic 上调用。
 */
@Component
public class VideoTaskSettler {

    private static final Logger logger = LoggerFactory.getLogger(VideoTaskSettler.class);

    /** 终态响应快照最大长度（字符），最终兜底（视频任务响应实际仅 URL 与用量，远小于该值） */
    private static final int MAX_RESPONSE_SNAPSHOT = 65535;
    /** 错误信息最大长度，与 ai_video_task.error_message 列长一致 */
    private static final int MAX_ERROR_MESSAGE = 1000;
    /** 视频帧率默认值（frames 折算秒数时使用，与方舟协议 framespersecond=24 一致） */
    private static final BigDecimal DEFAULT_FPS = new BigDecimal("24");

    private final VideoTaskRepository repository;
    private final BillingService billingService;
    private final ModelPricingService pricingService;
    private final org.unreal.modelrouter.billing.ResponseSnapshotBuilder responseSnapshotBuilder;

    public VideoTaskSettler(final VideoTaskRepository repository,
                            final BillingService billingService,
                            final ModelPricingService pricingService,
                            final org.unreal.modelrouter.billing.ResponseSnapshotBuilder responseSnapshotBuilder) {
        this.repository = repository;
        this.billingService = billingService;
        this.pricingService = pricingService;
        this.responseSnapshotBuilder = responseSnapshotBuilder;
    }

    /**
     * 按上游查询响应结算任务。
     *
     * @param entity       留档记录
     * @param upstreamResp 上游查询响应 JSON（原始内容，提取 status/usage/error）
     * @param snapshot     改写后的对外响应（id 已替换为网关任务号），终态时落 response_snapshot；
     *                     后台轮询场景由调度器按同一口径构造（可传 null，仅影响计费快照内容）
     * @param identity     查询方身份；后台轮询场景传 null，内部从 entity 重建身份
     * @param clientIp     查询方客户端 IP（计费追溯）；后台轮询场景传 null
     * @param traceId      查询请求链路追踪 ID（计费追溯）；后台轮询场景传 null
     */
    public void settle(final VideoTaskEntity entity, final JsonNode upstreamResp,
                       final String snapshot, final UserIdentity identity,
                       final String clientIp, final String traceId) {
        String status = upstreamResp.path("status").isTextual() ? upstreamResp.path("status").asText() : null;
        if (status == null || status.isBlank()) {
            logger.warn("视频任务上游响应缺少 status 字段，跳过结算: taskNo={}", entity.getTaskNo());
            return;
        }
        // 后台轮询场景：identity 为 null，从留档字段重建身份（保证计费落账字段完整）
        final UserIdentity effectiveIdentity = identity != null ? identity : rebuildIdentityFromEntity(entity);
        switch (status) {
            case "succeeded" -> settleSuccess(entity, upstreamResp, snapshot, effectiveIdentity, clientIp, traceId);
            case "failed", "expired", "cancelled" -> settleTerminalFailure(entity, status, upstreamResp, snapshot);
            case "queued", "running" -> repository.updateRunningStatus(entity.getId(), status);
            default -> logger.warn("视频任务上游返回未知状态，跳过结算: taskNo={}, status={}",
                    entity.getTaskNo(), status);
        }
    }

    /**
     * 从留档字段重建 UserIdentity（后台轮询无请求上下文，计费身份从留档恢复）。
     * enterpriseName 留档未存储（避免冗余），此处为 null——余额扣减/预警走
     * EnterpriseLookupService 按 accountId 实时查询，不受影响。
     * platformUser 取创建时留档快照（ai_video_task.platform_user），
     * 历史数据/创建异常为 NULL 时按 false 兜底（维持原行为：轮询结算无企业折扣）。
     */
    private static UserIdentity rebuildIdentityFromEntity(final VideoTaskEntity entity) {
        if (entity == null || entity.getUserId() == null) {
            return UserIdentity.SYSTEM;
        }
        return new UserIdentity(
                entity.getUserId(),
                entity.getUserAccount(),
                entity.getApiKeyId(),
                entity.getApiKeyName(),
                entity.getEnterpriseId(),
                null, // enterpriseName 未留档，余额扣减链路实时查询
                entity.getCompanyId(),
                Boolean.TRUE.equals(entity.getPlatformUser()), // 创建时留档快照；NULL/历史数据按 false 兜底
                entity.getUserType(),
                null, // systemUserId 未留档，余额扣减链路按需查询
                null  // verifyStatus 未留档，非关键字段
        );
    }

    /**
     * 上游记录不存在/过期（上游 404）时止损：本地置 expired，
     * 后续查询走终态直出，不再打上游。
     */
    public void markExpired(final VideoTaskEntity entity, final String reason) {
        int claimed = repository.claimTerminal(entity.getId(), "expired", "404",
                truncate(reason, MAX_ERROR_MESSAGE), null, LocalDateTime.now());
        if (claimed > 0) {
            logger.info("视频任务置过期: taskNo={}, reason={}", entity.getTaskNo(), reason);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * succeeded 首次结算：终态抢占 → 同步计费 → 回写 billing_record_id。
     *
     * 计费口径（视频模型元数据升级后）：按上游实际用量组装视频计费上下文
     * （分辨率/时长秒数/有无视频输入/token），由 BillingService 按价格模式 +
     * 计费单位 + 分辨率规则计价；不再按 token 字段计价。
     *
     * 资损兜底：抢占成功但计费失败（返回 null）时，任务已置 succeeded 且
     * billing_record_id 留 NULL，不再自动重试——对账口径为
     * 「status=succeeded AND billing_record_id IS NULL」，可通过内部追溯
     * 端点（status 过滤 + 单条查询）定位。
     */
    private void settleSuccess(final VideoTaskEntity entity, final JsonNode upstreamResp,
                               final String snapshot, final UserIdentity identity,
                               final String clientIp, final String traceId) {
        int claimed = repository.claimTerminal(entity.getId(), "succeeded", null, null,
                truncate(snapshot, MAX_RESPONSE_SNAPSHOT), LocalDateTime.now());
        if (claimed == 0) {
            // 已被其他并发查询结算，幂等跳过（防重复计费）
            return;
        }

        // 定价预检：平台未配置该模型定价时计费侧将拒计费（billing_record_id 留 NULL 走人工对账），
        // 预检未命中即 WARN 留痕（BillingService 内部还会按需同步一次定价，此处为告警防线）
        if (pricingService.getPrice(entity.getModelName(), entity.getChannelId()) == null) {
            logger.warn("视频任务 succeeded 结算预检：定价缓存未命中，若平台未配置该模型定价将拒计费留人工对账: "
                    + "taskNo={}, model={}, channelId={}",
                    entity.getTaskNo(), entity.getModelName(), entity.getChannelId());
        }

        // 方舟视频模型输入 token 恒为 0；completion_tokens 仅透传对账留痕——
        // second 计费单位（元/秒）时 BillingService 落库将 token 列置 NULL，token 用量统计天然排除；
        // token 计费单位（元/token）时作为计费依据，缺失时计费侧拒计费
        JsonNode usage = upstreamResp.path("usage");
        long completionTokens = usage.path("completion_tokens").asLong(0);
        long totalTokens = usage.path("total_tokens").asLong(completionTokens);
        // 上游协议异常时 token 可能缺失/为 0：WARN 留痕便于对账定位
        //（口径：succeeded 且 completion_tokens=0；second 计量时仅影响留痕不影响计费）
        if (completionTokens <= 0) {
            logger.warn("视频任务 succeeded 但 usage.completion_tokens 缺失或为 0: "
                    + "taskNo={}, model={}, usage={}",
                    entity.getTaskNo(), entity.getModelName(), usage);
        }

        // 视频实际用量提取（计费依据）：分辨率/时长（duration 与 frames 二选一，
        // 查询响应只回传其一）/有无视频输入（创建时留档）
        String resolution = normalizeResolution(textOrNull(upstreamResp.path("resolution")));
        if (resolution == null) {
            resolution = normalizeResolution(textOrNull(usage.path("SR")));
        }
        if (resolution == null) {
            resolution = normalizeResolution(entity.getResolution());
        }
        BigDecimal seconds = resolveSeconds(upstreamResp, usage, entity);
        if (seconds == null) {
            logger.warn("视频任务 succeeded 但时长用量缺失（duration/frames 均无效），计费侧将拒计费: "
                    + "taskNo={}, model={}", entity.getTaskNo(), entity.getModelName());
        }
        Boolean hasVideoInput = entity.getHasVideoInput();

        // 上游真实生成耗时（秒）：updated_at - created_at，反映模型实际处理时长而非轮询空窗
        Long upstreamGenerationMs = resolveUpstreamGenerationMs(upstreamResp);

        BillingService.BillingContext ctx = BillingService.BillingContext.create()
                .userId(identity.userId())
                .userAccount(identity.userAccount())
                .apiKeyId(identity.apiKeyId())
                .apiKeyName(identity.apiKeyName())
                .modelName(entity.getModelName())
                .serviceType(ModelServiceRegistry.ServiceType.vidGen.name())
                .provider(entity.getVendor())
                .channelId(entity.getChannelId())
                .channelName(entity.getChannelName())
                .promptTokens(0L)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .vendor(entity.getVendor())
                .baseUrl(entity.getBaseUrl())
                .isSuccess(true)
                .traceId(traceId)
                .clientIp(clientIp)
                .startedAt(entity.getSubmittedAt())
                .responseTimeMs(upstreamGenerationMs)
                .enterpriseId(identity.enterpriseId())
                .enterpriseName(identity.enterpriseName())
                .companyId(identity.companyId())
                .userType(identity.userType())
                .systemUserId(identity.systemUserId())
                .platformUser(identity.platformUser())
                .accountId(resolveAccountId(identity))
                .accountType(resolveAccountType(identity))
                .videoUsage(new BillingService.VideoUsage(resolution, hasVideoInput, seconds, completionTokens))
                // 计费规则快照：任务创建时锁定的提交时刻计费规则（V7 billing_rule_snapshot），
                // 结算优先用快照计价——平台侧后续改价/删规则不影响已提交任务账单；
                // 快照缺失（创建时定价未命中/历史任务）解析为 null，BillingService 回退实时定价
                .videoPricingSnapshot(pricingService.parseVideoPricingSnapshot(entity.getBillingRuleSnapshot()))
                // 计费响应快照：复用改写后的对外响应（id=网关任务号，已移除上游 task_id，
                // 防上游任务 ID 落入账单）；两条链路（查询/后台轮询）快照构造口径一致。
                // usage 原始结构一并入快照，与 chat 链路快照口径统一（builder 内 8KB 截断 + base64 脱敏）
                .responseSnapshot(responseSnapshotBuilder.build("vidGen", true, 200, null, null,
                        usage.isObject() ? usage : null, snapshot))
                .isFreeQuota(false);

        Long billingRecordId = billingService.recordBillingSync(ctx);
        if (billingRecordId == null) {
            logger.error("视频任务 succeeded 计费失败，留待人工对账: taskNo={}, model={}, completionTokens={}, "
                            + "resolution={}, hasVideoInput={}, seconds={} "
                            + "(对账口径: status=succeeded AND billing_record_id IS NULL)",
                    entity.getTaskNo(), entity.getModelName(), completionTokens,
                    resolution, hasVideoInput, seconds);
            return;
        }
        repository.attachBillingRecord(entity.getId(), billingRecordId);
        logger.info("视频任务结算完成: taskNo={}, billingRecordId={}, completionTokens={}",
                entity.getTaskNo(), billingRecordId, completionTokens);
    }

    /** failed/expired/cancelled 终态落库：回写错误信息与响应快照。 */
    private void settleTerminalFailure(final VideoTaskEntity entity, final String status,
                                       final JsonNode upstreamResp, final String snapshot) {
        String errorCode = null;
        String errorMessage = null;
        JsonNode error = upstreamResp.path("error");
        if (error.isObject()) {
            errorCode = error.path("code").isTextual() ? error.path("code").asText() : null;
            errorMessage = error.path("message").isTextual() ? error.path("message").asText() : null;
        }
        int claimed = repository.claimTerminal(entity.getId(), status, errorCode,
                truncate(errorMessage, MAX_ERROR_MESSAGE),
                truncate(snapshot, MAX_RESPONSE_SNAPSHOT), LocalDateTime.now());
        if (claimed > 0) {
            logger.info("视频任务终态落库: taskNo={}, status={}, errorCode={}",
                    entity.getTaskNo(), status, errorCode);
        }
    }

    /** 账户标识解析：企业用户按 companyId、个人用户按 userId（与 ProtocolPassthroughService 口径一致） */
    private static String resolveAccountId(final UserIdentity identity) {
        if (identity == null) {
            return null;
        }
        if (Integer.valueOf(1).equals(identity.userType())) {
            return identity.companyId();
        }
        if (Integer.valueOf(2).equals(identity.userType())) {
            return identity.userId();
        }
        return null;
    }

    /** 账户类型即用户类型（与 ProtocolPassthroughService 口径一致） */
    private static Integer resolveAccountType(final UserIdentity identity) {
        return identity != null ? identity.userType() : null;
    }

    private static String truncate(final String text, final int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }

    /** 分辨率归一化为大写（"720p"→"720P"），与元数据 ai_model_video_price 口径一致 */
    private static String normalizeResolution(final String resolution) {
        return resolution == null || resolution.isBlank()
                ? null : resolution.trim().toUpperCase(Locale.ROOT);
    }

    private static String textOrNull(final JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static BigDecimal numberOrNull(final JsonNode node) {
        return node != null && node.isNumber() ? node.decimalValue() : null;
    }

    /**
     * 视频时长（秒）解析：duration 与 frames 二选一，查询响应只回传其一。
     * 取值顺序：
     * <ol>
     *   <li>顶层 duration（Number，>0）直接取用——整数秒约数（实际总帧数/24 向下取整），方舟计费口径；</li>
     *   <li>顶层 frames ÷ framespersecond（帧率缺失默认 24）折算为小数秒；</li>
     *   <li>回退 usage.duration → 留档 duration（>0）→ 留档 frames÷24。</li>
     * </ol>
     * 无有效值返回 null（计费侧拒计费留人工对账）。
     */
    private static BigDecimal resolveSeconds(final JsonNode upstreamResp, final JsonNode usage,
                                             final VideoTaskEntity entity) {
        BigDecimal seconds = numberOrNull(upstreamResp.path("duration"));
        if (!isPositive(seconds)) {
            seconds = framesToSeconds(upstreamResp);
        }
        if (!isPositive(seconds)) {
            seconds = numberOrNull(usage.path("duration"));
        }
        if (!isPositive(seconds) && entity.getDuration() != null && entity.getDuration() > 0) {
            seconds = BigDecimal.valueOf(entity.getDuration());
        }
        if (!isPositive(seconds) && entity.getFrames() != null && entity.getFrames() > 0) {
            seconds = BigDecimal.valueOf(entity.getFrames()).divide(DEFAULT_FPS, 6, RoundingMode.HALF_UP);
        }
        return isPositive(seconds) ? seconds : null;
    }

    /** frames ÷ framespersecond 折算秒数（帧率缺失或非正数时默认 24，scale 6 HALF_UP） */
    private static BigDecimal framesToSeconds(final JsonNode upstreamResp) {
        long frames = upstreamResp.path("frames").asLong(0);
        if (frames <= 0) {
            return null;
        }
        BigDecimal fps = numberOrNull(upstreamResp.path("framespersecond"));
        if (fps == null || fps.compareTo(BigDecimal.ZERO) <= 0) {
            fps = DEFAULT_FPS;
        }
        return BigDecimal.valueOf(frames).divide(fps, 6, RoundingMode.HALF_UP);
    }

    private static boolean isPositive(final BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 提取上游真实生成耗时（毫秒）：updated_at - created_at。
     * 防御：字段缺失/非数字/updated_at <= created_at 时返回 null（不阻断计费主链路）。
     */
    private static Long resolveUpstreamGenerationMs(final JsonNode upstreamResp) {
        if (upstreamResp == null) {
            return null;
        }
        JsonNode createdAt = upstreamResp.path("created_at");
        JsonNode updatedAt = upstreamResp.path("updated_at");
        if (!createdAt.isNumber() || !updatedAt.isNumber()) {
            return null;
        }
        long created = createdAt.asLong();
        long updated = updatedAt.asLong();
        if (updated <= created) {
            return null;
        }
        return (updated - created) * 1000L;
    }
}
