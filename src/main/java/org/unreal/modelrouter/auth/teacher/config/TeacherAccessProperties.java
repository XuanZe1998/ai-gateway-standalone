package org.unreal.modelrouter.auth.teacher.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 教师统一认证和设备绑定访问配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jairouter.teacher-access")
public class TeacherAccessProperties {

    public static final String TOKEN_PREFIX = "ntit_at_";

    private boolean enabled;
    private String issuer = "https://ai.ntit.edu.cn";
    private String audience = "ai-gateway";
    private String publicBaseUrl = "https://ai.ntit.edu.cn";
    private String tokenSecret;
    private Duration accessTokenTtl = Duration.ofMinutes(5);
    private boolean requireClientCertificate = true;
    private int maxDevicesPerTeacher = 2;
    private List<String> defaultPermissions = List.of(
            "USER", "READ", "WRITE", "CHAT", "EMBEDDING", "RERANK",
            "TTS", "STT", "IMGGEN", "IMGEDIT", "VIDGEN"
    );
}
