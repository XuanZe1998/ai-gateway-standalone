package org.unreal.modelrouter.router.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.persistence.jpa.entity.VideoTaskEntity;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;

import java.util.List;
import java.util.Set;

/**
 * 视频任务上游访问辅助组件（查询链路与后台调度器共用）。
 *
 * 职责：实例解析（留档 instance_id 精确匹配 → 兜底路由选择）与
 * 上游查询路径推导（方舟协议 vs 电信 aigw 协议）。
 *
 * 提取背景：原逻辑在 {@link VideoTaskQueryService} 与
 * {@link VideoTaskScheduler} 中各有一份，存在漂移风险。
 */
@Component
public class VideoTaskUpstreamHelper {

    private static final Logger logger = LoggerFactory.getLogger(VideoTaskUpstreamHelper.class);

    /** 火山方舟视频任务查询路径（创建路径未配置时的兜底常量） */
    public static final String ARK_QUERY_PATH = "/api/v3/contents/generations/tasks";

    /** 电信 aigw 查询子路径：查询 = 创建路径 + "/task/{任务 ID}" */
    public static final String TELECOM_QUERY_SUBPATH = "task";

    /** 电信 aigw 默认创建路径（渠道未配 video_url 时 detectPath 兜底值） */
    public static final String TELECOM_DEFAULT_CREATION_PATH = "/v1/videos/generations";

    /** 方舟系厂商集合：查询路径 = 创建路径 + "/{id}"（REST 集合资源语义）；其余厂商按电信 aigw 协议处理 */
    public static final Set<String> ARK_VENDORS = Set.of("volcengine", "ark");

    private final ModelServiceRegistry registry;

    public VideoTaskUpstreamHelper(final ModelServiceRegistry registry) {
        this.registry = registry;
    }

    /**
     * 解析上游实例：优先按留档 instance_id 在当前实例缓存中精确匹配
     * （保证 API Key 与任务归属渠道一致），找不到再回退路由选择
     * （渠道配置变更 / 留档实例已下线的兜底）。
     *
     * @param entity   留档任务
     * @param clientIp 客户端 IP（路由选择用；调度器场景传 null）
     * @param caller   调用方标识（日志区分，如 "查询链路"/"后台轮询"）
     */
    public ModelRouterProperties.ModelInstance resolveInstance(final VideoTaskEntity entity,
                                                                final String clientIp,
                                                                final String caller) {
        try {
            if (entity.getInstanceId() != null) {
                List<ModelRouterProperties.ModelInstance> instances = registry.getAllInstances()
                        .getOrDefault(ModelServiceRegistry.ServiceType.vidGen, List.of());
                for (ModelRouterProperties.ModelInstance candidate : instances) {
                    if (entity.getInstanceId().equals(candidate.getInstanceId())) {
                        return candidate;
                    }
                }
                // 留档实例在缓存中不存在（模型/渠道被删改重建等）：回退路由选择，
                // 必须留痕——回退可能选中不同渠道实例导致上游鉴权失败，排查依赖本日志
                logger.warn("视频任务{}：留档实例不在当前缓存，回退路由选择: taskNo={}, 留档instanceId={}, model={}, 缓存实例数={}",
                        caller, entity.getTaskNo(), entity.getInstanceId(), entity.getModelName(), instances.size());
            }
            ModelRouterProperties.ModelInstance fallback = registry.selectInstance(
                    ModelServiceRegistry.ServiceType.vidGen, entity.getModelName(), clientIp);
            logger.info("视频任务{}命中路由实例: taskNo={}, instanceId={}, baseUrl={}",
                    caller, entity.getTaskNo(),
                    fallback != null ? fallback.getInstanceId() : null,
                    fallback != null ? fallback.getBaseUrl() : null);
            return fallback;
        } catch (Exception e) {
            logger.error("视频任务{}实例解析失败: taskNo={}, model={}: {}",
                    caller, entity.getTaskNo(), entity.getModelName(), e.getMessage());
            return null;
        }
    }

    /**
     * 推导上游查询路径（按上游协议分 vendor）：
     * <ul>
     *   <li>电信 aigw（非方舟 vendor）：查询 = 创建路径 + "/task/" + 任务 ID
     *       （同源派生，主流形式；创建路径取渠道配置，未配置时用 detectPath
     *       默认值 /v1/videos/generations）；不能用 OpenAI 官方路径 /v1/videos/{id}
     *       （该路径被电信瑞数 WAF 拦截 412），也不能用创建路径 + "/{id}" 拼接
     *       （上游 401）；后续接入其他 OpenAI 兼容厂商时需在此按 vendor 细分。</li>
     *   <li>方舟协议（volcengine/ark）：查询 = 创建路径 + "/" + 任务 ID：
     *       创建路径非空（video_url 纯路径形态）则拼接；
     *       创建路径为空串（video_url 配完整 URL 形态，创建时直接 POST baseUrl 本身）
     *       则仅拼 "/" + 任务 ID（拼到 baseUrl 后即「创建地址/{任务 ID}」）；
     *       创建路径获取异常（null）回退方舟默认查询路径。</li>
     * </ul>
     */
    public String resolveQueryPath(final VideoTaskEntity entity) {
        String vendor = entity.getVendor() != null ? entity.getVendor().toLowerCase().trim() : "";
        if (!ARK_VENDORS.contains(vendor)) {
            // 电信 aigw 协议：查询 = 创建路径 + "/task/" + 任务 ID（同源派生）
            String creationPath = getCreationPathOrDefault(entity.getModelName(), TELECOM_DEFAULT_CREATION_PATH);
            return trimEndSlash(creationPath) + "/" + TELECOM_QUERY_SUBPATH + "/" + entity.getUpstreamTaskId();
        }
        String creationPath = null;
        try {
            creationPath = registry.getModelPath(ModelServiceRegistry.ServiceType.vidGen,
                    entity.getModelName());
        } catch (Exception e) {
            logger.warn("视频任务创建路径获取失败，使用方舟默认查询路径: {}", e.getMessage());
        }
        if (creationPath != null && !creationPath.isBlank()) {
            // 创建路径非空（video_url 纯路径形态）
            return trimEndSlash(creationPath) + "/" + entity.getUpstreamTaskId();
        }
        if (creationPath == null) {
            // 路径获取异常（null），回退方舟默认查询路径
            return ARK_QUERY_PATH + "/" + entity.getUpstreamTaskId();
        }
        // 创建路径为空串：创建时 POST 的即 baseUrl 本身，查询仅拼任务 ID
        return "/" + entity.getUpstreamTaskId();
    }

    /** 获取创建路径，未配置/获取失败时回退默认值（电信分支用，创建路径是查询路径的派生源） */
    private String getCreationPathOrDefault(final String modelName, final String defaultPath) {
        try {
            String path = registry.getModelPath(ModelServiceRegistry.ServiceType.vidGen, modelName);
            if (path != null && !path.isBlank()) {
                return path;
            }
        } catch (Exception e) {
            logger.warn("视频任务创建路径获取失败，使用默认创建路径 {}: {}", defaultPath, e.getMessage());
        }
        return defaultPath;
    }

    /** 去除尾部斜杠（防创建路径带 / 时拼接出双斜杠） */
    private static String trimEndSlash(final String path) {
        return path != null && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
