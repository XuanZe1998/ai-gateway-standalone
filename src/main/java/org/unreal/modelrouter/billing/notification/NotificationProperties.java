package org.unreal.modelrouter.billing.notification;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 通知服务配置。
 * 在 application.yml 或独立配置文件中通过 jairouter.billing.notification 前缀绑定。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jairouter.billing.notification")
public class NotificationProperties {

    /**
     * 是否启用通知服务（默认关闭，需配置算力平台地址后开启）
     */
    private boolean enabled = false;

    /**
     * 算力平台基础 URL（如 http://localhost:8080）
     */
    private String computePlatformBaseUrl = "http://localhost:8080";

    /**
     * 服务间内部通信密钥（HMAC-SHA256），仅用于 /internal/refresh 端点验签。
     * 不配置时跳过验签（开发环境兼容）。
     */
    private String internalSecret = "";

    /**
     * 预警冷却时间（小时），同一企业在冷却期内不重复发送预警
     */
    private int alertCooldownHours = 1;
}
