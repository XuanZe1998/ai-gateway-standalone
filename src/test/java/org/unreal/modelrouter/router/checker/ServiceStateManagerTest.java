// 文件说明：测试 ServiceStateManagerTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.router.checker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.router.model.ModelRouterProperties;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceStateManagerTest {

    private ServiceStateManager manager;

    @BeforeEach
    void setUp() {
        manager = new ServiceStateManager();
    }

    @Test
    void resetAllHealthStatus_shouldClearBothServiceAndInstanceHealthCaches() {
        // Given: 预先写入一些不健康状态
        manager.updateServiceHealthStatus("chat", false);

        ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
        instance.setInstanceId("kimi-k2.6");
        manager.updateInstanceHealthStatus("chat", instance, false);

        assertThat(manager.isServiceHealthy("chat")).isFalse();
        assertThat(manager.isInstanceHealthy("chat", instance)).isFalse();

        // When: 重置所有健康状态
        manager.resetAllHealthStatus();

        // Then: 服务级和实例级状态都应恢复为默认值（true）
        assertThat(manager.isServiceHealthy("chat")).isTrue();
        assertThat(manager.isInstanceHealthy("chat", instance)).isTrue();
        assertThat(manager.getAllServiceHealthStatus()).isEmpty();
        assertThat(manager.getAllInstanceHealthStatus()).isEmpty();
    }

    @Test
    void isServiceHealthy_unknownService_shouldDefaultToTrue() {
        assertThat(manager.isServiceHealthy("unknown")).isTrue();
    }

    @Test
    void isInstanceHealthy_unknownInstance_shouldDefaultToTrue() {
        ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
        instance.setInstanceId("new-model");
        assertThat(manager.isInstanceHealthy("chat", instance)).isTrue();
    }
}
