package org.unreal.modelrouter.cluster;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/** Adds replica identity and loaded configuration revision to Actuator info. */
@Component
public class ClusterInfoContributor implements InfoContributor {

    private final ClusterProperties properties;
    private final ObjectProvider<ClusterConfigSynchronizer> synchronizerProvider;

    public ClusterInfoContributor(final ClusterProperties properties,
                                  final ObjectProvider<ClusterConfigSynchronizer> synchronizerProvider) {
        this.properties = properties;
        this.synchronizerProvider = synchronizerProvider;
    }

    @Override
    public void contribute(final Info.Builder builder) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("enabled", properties.isEnabled());
        details.put("instanceId", properties.getInstanceId());
        details.put("distributedRateLimit", properties.getRateLimit().isEnabled());
        ClusterConfigSynchronizer synchronizer = synchronizerProvider.getIfAvailable();
        details.put("configRevision",
                synchronizer == null ? "disabled" : synchronizer.getLoadedRevision());
        builder.withDetail("cluster", details);
    }
}
