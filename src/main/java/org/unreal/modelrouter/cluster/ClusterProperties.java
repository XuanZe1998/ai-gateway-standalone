package org.unreal.modelrouter.cluster;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cluster deployment settings.
 */
@Data
@Component
@ConfigurationProperties(prefix = "jairouter.cluster")
public class ClusterProperties {

    private boolean enabled;
    private String instanceId = "local";
    private final ConfigSync configSync = new ConfigSync();
    private final RateLimit rateLimit = new RateLimit();

    /** Shared configuration polling settings. */
    @Data
    public static class ConfigSync {
        private boolean enabled;
        private long intervalMs = 3000L;
    }

    /** Redis-backed distributed rate limiting settings. */
    @Data
    public static class RateLimit {
        private boolean enabled;
        private boolean failOpen;
        private long timeoutMs = 250L;
        private String keyPrefix = "jairouter:rate-limit:";
    }
}
