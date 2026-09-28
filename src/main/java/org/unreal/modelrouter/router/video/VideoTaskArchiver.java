package org.unreal.modelrouter.router.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.billing.ModelPricingService;
import org.unreal.modelrouter.persistence.jpa.entity.VideoTaskEntity;
import org.unreal.modelrouter.persistence.jpa.repository.VideoTaskRepository;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频生成任务留档器。
 *
 * 以 fire-and-forget 方式在 boundedElastic 上落库：留档失败仅告警，
 * 不影响客户端响应（与计费 @Async 落账的可靠性策略一致）。
 * 请求快照入库前对 base64 素材做脱敏截断，防止大素材撑爆存储。
 */
@Component
public class VideoTaskArchiver {

    private static final Logger logger = LoggerFactory.getLogger(VideoTaskArchiver.class);

    /** 请求快照最大长度（字符）；base64 已先行截断，此处为最终兜底 */
    private static final int MAX_REQUEST_SNAPSHOT = 65535;
    /** 上游响应快照最大长度（字符） */
    private static final int MAX_RESPONSE_SNAPSHOT = 8192;

    private final VideoTaskRepository repository;
    private final ObjectMapper objectMapper;
    private final ModelPricingService pricingService;

    public VideoTaskArchiver(final VideoTaskRepository repository, final ObjectMapper objectMapper,
                             final ModelPricingService pricingService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.pricingService = pricingService;
    }

    /**
     * 留档一次成功提交上游的任务。
     *
     * @param status 优先使用上游创建响应中的 status；上游未返回时用 submitted
     */
    public void archiveSubmitted(final String taskNo, final String upstreamTaskId, final String status,
                                 final String model, final ModelRouterProperties.ModelInstance instance,
                                 final JsonNode requestNode, final String upstreamResponseBody,
                                 final UserIdentity identity, final String clientIp, final String traceId) {
        VideoTaskEntity entity = buildBase(taskNo, model, instance, requestNode, identity, clientIp, traceId);
        entity.setUpstreamTaskId(upstreamTaskId);
        entity.setStatus(status != null && !status.isBlank() ? status : "submitted");
        entity.setFirstResponseSnapshot(truncate(upstreamResponseBody, MAX_RESPONSE_SNAPSHOT));
        saveAsync(entity, taskNo);
    }

    /**
     * 留档一次提交失败（上游 4xx/5xx 或网关侧超时）。
     */
    public void archiveSubmitFailed(final String taskNo, final String model,
                                    final ModelRouterProperties.ModelInstance instance,
                                    final JsonNode requestNode, final String errorCode, final String errorMessage,
                                    final UserIdentity identity, final String clientIp, final String traceId) {
        VideoTaskEntity entity = buildBase(taskNo, model, instance, requestNode, identity, clientIp, traceId);
        entity.setStatus("failed");
        entity.setErrorCode(errorCode);
        entity.setErrorMessage(truncate(errorMessage, 1000));
        saveAsync(entity, taskNo);
    }

    // ==================== 内部方法 ====================

    private VideoTaskEntity buildBase(final String taskNo, final String model,
                                      final ModelRouterProperties.ModelInstance instance,
                                      final JsonNode requestNode, final UserIdentity identity,
                                      final String clientIp, final String traceId) {
        VideoTaskEntity entity = new VideoTaskEntity();
        entity.setTaskNo(taskNo);
        entity.setModelName(model);
        if (instance != null) {
            entity.setVendor(instance.getVendor());
            entity.setChannelId(instance.getChannelId());
            entity.setChannelName(instance.getName());
            entity.setInstanceId(instance.getInstanceId());
            entity.setBaseUrl(truncate(instance.getBaseUrl(), 500));
        }
        // 关键参数提取（供二期计费/对账）
        if (requestNode != null) {
            entity.setResolution(textOrNull(requestNode.path("resolution")));
            entity.setRatio(textOrNull(requestNode.path("ratio")));
            entity.setDuration(intOrNull(requestNode.path("duration")));
            entity.setFrames(intOrNull(requestNode.path("frames")));
            entity.setGenerateAudio(boolOrNull(requestNode.path("generate_audio")));
            entity.setDraft(boolOrNull(requestNode.path("draft")));
            entity.setHasVideoInput(hasVideoInput(requestNode.path("content")));
            entity.setRequestSnapshot(sanitizeRequestSnapshot(requestNode));
        }
        // 用户维度
        if (identity != null) {
            entity.setUserId(identity.userId());
            entity.setUserAccount(identity.userAccount());
            entity.setApiKeyId(identity.apiKeyId());
            entity.setApiKeyName(identity.apiKeyName());
            entity.setUserType(identity.userType());
            entity.setEnterpriseId(identity.enterpriseId());
            entity.setCompanyId(identity.companyId());
            // 平台用户标记留档：后台轮询结算重建身份时恢复原值（企业折扣依赖该标记）
            entity.setPlatformUser(identity.platformUser());
            entity.setCreatedBy(identity.userAccount());
        }
        entity.setTraceId(traceId);
        entity.setClientIp(clientIp);
        entity.setSubmittedAt(LocalDateTime.now());
        // 计费规则快照：任务创建时锁定提交时刻的视频计费规则（价格模式/计费单位/模型主键/启用规则行），
        // 结算时优先用快照计价——平台侧后续改价/删规则不影响已提交任务的账单生成（提交时刻锁价）；
        // 创建时定价未命中/非视频模型则快照为 NULL，结算回退实时查询定价（兼容历史数据）
        entity.setBillingRuleSnapshot(pricingService.snapshotVideoPricing(model, channelIdOf(instance)));
        return entity;
    }

    /** 渠道 ID 提取（instance 为 null 时返回 null，快照按模型名回退匹配） */
    private static String channelIdOf(final ModelRouterProperties.ModelInstance instance) {
        return instance != null ? instance.getChannelId() : null;
    }

    private void saveAsync(final VideoTaskEntity entity, final String taskNo) {
        Mono.fromRunnable(() -> repository.save(entity))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        unused -> logger.info("视频任务留档成功: taskNo={}, upstreamTaskId={}, status={}",
                                taskNo, entity.getUpstreamTaskId(), entity.getStatus()),
                        err -> logger.error("视频任务留档失败（不影响客户端响应）: taskNo={}: {}",
                                taskNo, err.getMessage(), err));
    }

    /**
     * 请求快照脱敏：递归替换所有 base64 data URL 为截断标记，序列化后限长。
     * URL 类引用（公网 URL / asset://）原样保留，保证追溯时仍可定位素材来源。
     */
    private String sanitizeRequestSnapshot(final JsonNode requestNode) {
        try {
            JsonNode copy = requestNode.deepCopy();
            stripBase64Data(copy);
            return truncate(objectMapper.writeValueAsString(copy), MAX_REQUEST_SNAPSHOT);
        } catch (Exception e) {
            logger.warn("请求快照脱敏失败，跳过快照: {}", e.getMessage());
            return null;
        }
    }

    private void stripBase64Data(final JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> fields = new ArrayList<>();
            obj.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                JsonNode value = obj.get(field);
                if (value.isTextual() && isBase64DataUrl(value.asText())) {
                    String text = value.asText();
                    int comma = text.indexOf(',');
                    String prefix = comma >= 0 ? text.substring(0, comma + 1) : "data:";
                    obj.put(field, prefix + "<base64 truncated, original length " + text.length() + ">");
                } else {
                    stripBase64Data(value);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                stripBase64Data(child);
            }
        }
    }

    private static boolean isBase64DataUrl(final String text) {
        return text != null && text.startsWith("data:") && text.contains(";base64,");
    }

    private static String textOrNull(final JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    /**
     * 判断本次调用是否有视频输入：content 数组中任一项 type=video_url 即 true；
     * 数组内无视频项为 false；content 缺失或非数组为 null（无法判定）。
     * 供视频模型按条件定价（price_mode=2）匹配分辨率价格规则使用，
     * 创建提交校验（VideoTaskService）与留档判定共用同一口径。
     */
    public static Boolean hasVideoInput(final JsonNode content) {
        if (content == null || !content.isArray()) {
            return null;
        }
        for (JsonNode item : content) {
            if ("video_url".equals(textOrNull(item.path("type")))) {
                return true;
            }
        }
        return false;
    }

    private static Integer intOrNull(final JsonNode node) {
        return node != null && node.isInt() ? node.asInt() : null;
    }

    private static Boolean boolOrNull(final JsonNode node) {
        return node != null && node.isBoolean() ? node.asBoolean() : null;
    }

    private static String truncate(final String text, final int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }
}
