// 文件说明：测试 ClusterConfigSynchronizerTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.cluster;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.unreal.modelrouter.persistence.jpa.entity.ConfigEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ConfigRepository;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.ratelimit.RateLimitManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClusterConfigSynchronizerTest {

    @Test
    void shouldRefreshRuntimeStateWhenSharedRevisionChanges() {
        ConfigRepository repository = mock(ConfigRepository.class);
        ModelServiceRegistry registry = mock(ModelServiceRegistry.class);
        RateLimitManager rateLimitManager = mock(RateLimitManager.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ClusterProperties properties = new ClusterProperties();
        properties.setInstanceId("gateway-test");

        ConfigEntity entity = ConfigEntity.builder()
                .configKey("model-router-config")
                .version(2)
                .isLatest(true)
                .configValue("{}")
                .createdAt(LocalDateTime.now().minusMinutes(1L))
                .updatedAt(LocalDateTime.now())
                .build();
        when(repository.findFirstByConfigKeyAndIsLatestTrue("model-router-config"))
                .thenReturn(Optional.of(entity));

        ClusterConfigSynchronizer synchronizer = new ClusterConfigSynchronizer(
                repository, registry, rateLimitManager, scheduler, properties);
        synchronizer.refreshIfChanged();

        verify(registry).refreshFromMergedConfig();
        verify(rateLimitManager).updateConfiguration();
    }
}
