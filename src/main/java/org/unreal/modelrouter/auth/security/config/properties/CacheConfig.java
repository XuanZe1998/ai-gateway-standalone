package org.unreal.modelrouter.auth.security.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 缓存配置类
 */
@Data
public class CacheConfig {
    /**
     * 是否启用缓存
     */
    private boolean enabled = true;

    /**
     * Redis配置
     */
    @Valid
    @NotNull
    private RedisConfig redis = new RedisConfig();

    /**
     * 内存缓存配置
     */
    @Valid
    @NotNull
    private InMemoryConfig inMemory = new InMemoryConfig();

    /**
     * Redis缓存配置
     */
    @Data
    public static class RedisConfig {
        /**
         * 是否启用Redis缓存
         */
        private boolean enabled = false;

        /**
         * Redis主机地址
         */
        @NotBlank
        private String host = "localhost";

        /**
         * Redis端口
         */
        @Min(1)
        @Max(65535)
        private int port = 6379;

        /**
         * Redis密码
         */
        private String password;

        /**
         * Redis数据库索引
         */
        @Min(0)
        @Max(15)
        private int database = 0;

        /**
         * 连接超时时间（毫秒）
         */
        @Min(100)
        @Max(30000)
        private int connectionTimeout = 2000;

        /**
         * 读取超时时间（毫秒）
         */
        @Min(100)
        @Max(30000)
        private int readTimeout = 2000;

        /**
         * 缓存键前缀
         */
        @NotBlank
        private String keyPrefix = "jairouter:security:";

        /**
         * 默认过期时间（秒）
         */
        @Min(60)
        @Max(86400)
        private long defaultTtlSeconds = 3600;
    }

    /**
     * 内存缓存配置
     */
    @Data
    public static class InMemoryConfig {
        /**
         * 最大缓存条目数
         */
        @Min(100)
        @Max(100000)
        private int maxSize = 10000;

        /**
         * 默认过期时间（秒）
         */
        @Min(60)
        @Max(86400)
        private long defaultTtlSeconds = 3600;

        /**
         * 清理过期条目的间隔时间（分钟）
         */
        @Min(1)
        @Max(60)
        private int cleanupIntervalMinutes = 5;

        /**
         * 平台认证缓存 - 有效 key 的 TTL（秒）
         * 算力平台 key 的禁用/删除/编辑依赖 /internal/refresh 清空缓存；
         * 过期时间由缓存条目自身携带并校验。保持较短 TTL 可缩小非过期类变更的窗口。
         */
        @Min(10)
        @Max(3600)
        private long platformAuthPositiveTtlSeconds = 60;

        /**
         * 平台认证缓存 - 无效 key 的负缓存 TTL（秒）
         */
        @Min(5)
        @Max(300)
        private long platformAuthNegativeTtlSeconds = 30;
    }
}
