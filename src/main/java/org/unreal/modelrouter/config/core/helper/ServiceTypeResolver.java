package org.unreal.modelrouter.config.core.helper;

import org.springframework.stereotype.Component;
import org.unreal.modelrouter.common.constants.ServiceTypeConstants;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;

/**
 * 服务类型解析器
 *
 * 负责解析和验证服务类型，支持多种格式（连字符、下划线、驼峰等）。
 *
 * @author JAiRouter Team
 * @since v2.13.1
 */
@Component
public class ServiceTypeResolver {

    /**
     * 解析服务类型（支持多种格式）
     *
     * @param serviceKey 服务键
     * @return 服务类型枚举，如果无法识别则返回null
     */
    public ModelServiceRegistry.ServiceType parseServiceType(final String serviceKey) {
        if (serviceKey == null || serviceKey.trim().isEmpty()) {
            return null;
        }

        try {
            // 标准化处理：转小写，统一格式
            String normalizedKey = serviceKey.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[\\s_-]+", ""); // 移除空格、下划线和连字符

            // 尝试直接匹配枚举值
            return ModelServiceRegistry.ServiceType.valueOf(normalizedKey);
        } catch (IllegalArgumentException e) {
            // 处理常见的别名映射
            return mapServiceTypeAlias(serviceKey);
        }
    }

    /**
     * 获取服务配置键
     *
     * @param serviceType 服务类型
     * @return 服务配置键（连字符格式）
     */
    public String getServiceConfigKey(final ModelServiceRegistry.ServiceType serviceType) {
        if (serviceType == null) {
            return null;
        }

        // 使用连字符格式作为标准键名
        return serviceType.name().replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }

    /**
     * 验证服务类型有效性
     *
     * @param serviceType 服务类型字符串
     * @return 是否有效
     */
    public boolean isValidServiceType(final String serviceType) {
        if (serviceType == null) {
            return false;
        }

        try {
            return parseServiceType(serviceType) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 映射服务类型别名到枚举
     * 支持算力平台数字编码（1-6）和文本别名
     *
     * @param serviceKey 服务键
     * @return 服务类型枚举，如果无法识别则返回null
     */
    private ModelServiceRegistry.ServiceType mapServiceTypeAlias(final String serviceKey) {
        String lower = serviceKey.toLowerCase(java.util.Locale.ROOT);

        // 算力平台数字编码映射：1-对话 2-图片生成 3-视频生成 4-语音 5-嵌入 6-重排序
        switch (lower) {
            case "1":
                return ModelServiceRegistry.ServiceType.chat;
            case "2":
                return ModelServiceRegistry.ServiceType.imgGen;
            case "3":
                return ModelServiceRegistry.ServiceType.vidGen;
            case "4":
                return ModelServiceRegistry.ServiceType.tts;
            case "5":
                return ModelServiceRegistry.ServiceType.embedding;
            case "6":
                return ModelServiceRegistry.ServiceType.rerank;
            default:
                break;
        }

        // 文本别名映射
        if (lower.equals(ServiceTypeConstants.CHAT)
            || lower.equals("chat-completion")
            || lower.equals("chat-completions")) {
            return ModelServiceRegistry.ServiceType.chat;
        }

        if (lower.equals(ServiceTypeConstants.EMBEDDING)
            || lower.equals("embeddings")) {
            return ModelServiceRegistry.ServiceType.embedding;
        }

        if (lower.equals(ServiceTypeConstants.RERANK)
            || lower.equals("re-rank")) {
            return ModelServiceRegistry.ServiceType.rerank;
        }

        if (lower.equals(ServiceTypeConstants.TTS)
            || lower.equals("text-to-speech")) {
            return ModelServiceRegistry.ServiceType.tts;
        }

        if (lower.equals(ServiceTypeConstants.STT)
            || lower.equals("speech-to-text")) {
            return ModelServiceRegistry.ServiceType.stt;
        }

        if (lower.equals(ServiceTypeConstants.IMG_GEN)
            || lower.equals("imggen")
            || lower.equals("img-gen")
            || lower.equals("image-generation")
            || lower.equals("image-generate")) {
            return ModelServiceRegistry.ServiceType.imgGen;
        }

        if (lower.equals(ServiceTypeConstants.IMG_EDIT)
            || lower.equals("img-edit")
            || lower.equals("image-edit")
            || lower.equals("image-editing")) {
            return ModelServiceRegistry.ServiceType.imgEdit;
        }

        if (lower.equals(ServiceTypeConstants.VID_GEN)
            || lower.equals("vidgen")
            || lower.equals("vid-gen")
            || lower.equals("video-generation")
            || lower.equals("video-generate")) {
            return ModelServiceRegistry.ServiceType.vidGen;
        }

        return null;
    }
}