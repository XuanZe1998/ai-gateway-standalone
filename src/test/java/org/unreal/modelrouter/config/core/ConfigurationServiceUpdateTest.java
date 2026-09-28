// 文件说明：测试 ConfigurationServiceUpdateTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.config.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.unreal.modelrouter.common.dto.LoadBalanceConfig;
import org.unreal.modelrouter.config.core.manager.ConfigComparisonService;
import org.unreal.modelrouter.config.core.manager.ConfigValidator;
import org.unreal.modelrouter.config.core.manager.ConfigVersionManager;
import org.unreal.modelrouter.config.core.manager.InstanceManager;
import org.unreal.modelrouter.config.core.manager.TracingConfigManager;
import org.unreal.modelrouter.config.dto.UpdateServiceConfigRequest;
import org.unreal.modelrouter.monitor.tracing.config.SamplingConfigurationValidator;
import org.unreal.modelrouter.persistence.store.StoreManager;
import org.unreal.modelrouter.router.checker.ServiceStateManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigurationServiceUpdateTest {

    @Mock
    private StoreManager storeManager;
    @Mock
    private ConfigurationHelper configurationHelper;
    @Mock
    private ConfigMergeService configMergeService;
    @Mock
    private ServiceStateManager serviceStateManager;
    @Mock
    private SamplingConfigurationValidator samplingValidator;
    @Mock
    private ServiceConfigManager serviceConfigManager;
    @Mock
    private InstanceManager instanceManager;
    @Mock
    private ConfigVersionManager configVersionManager;
    @Mock
    private ConfigValidator configValidator;
    @Mock
    private TracingConfigManager tracingConfigManager;
    @Mock
    private ConfigComparisonService configComparisonService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ConfigurationService configurationService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(configurationService, "eventPublisher", eventPublisher);
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateServiceUsesYamlBaselineAndPreservesUneditedConfiguration() {
        Map<String, Object> chatConfig = new HashMap<>();
        chatConfig.put("adapter", "normal");
        chatConfig.put("instances", new ArrayList<>(List.of(Map.of("name", "chat-model"))));
        chatConfig.put("fallback", Map.of("enabled", true));
        chatConfig.put("loadBalance", Map.of("type", "random"));

        Map<String, Object> rerankConfig = new HashMap<>();
        rerankConfig.put("adapter", "rerank-adapter");

        Map<String, Object> services = new HashMap<>();
        services.put("chat", chatConfig);
        services.put("rerank", rerankConfig);
        Map<String, Object> yamlBaseline = new HashMap<>();
        yamlBaseline.put("services", services);

        when(configMergeService.getPersistedConfig()).thenReturn(yamlBaseline);
        when(configVersionManager.saveAsNewVersion(any(), anyString(), anyString())).thenReturn(1);

        UpdateServiceConfigRequest request = UpdateServiceConfigRequest.builder()
                .adapter("openai")
                .loadBalance(LoadBalanceConfig.builder().type("round-robin").build())
                .build();

        configurationService.updateServiceConfigDto("chat", request);

        ArgumentCaptor<Map<String, Object>> configCaptor = ArgumentCaptor.forClass(Map.class);
        verify(storeManager).saveConfig(anyString(), configCaptor.capture());
        Map<String, Object> savedServices = (Map<String, Object>) configCaptor.getValue().get("services");
        Map<String, Object> savedChat = (Map<String, Object>) savedServices.get("chat");

        assertEquals("openai", savedChat.get("adapter"));
        assertEquals(chatConfig.get("instances"), savedChat.get("instances"));
        assertEquals(chatConfig.get("fallback"), savedChat.get("fallback"));
        assertEquals(rerankConfig, savedServices.get("rerank"));
        assertEquals("round-robin", ((Map<String, Object>) savedChat.get("loadBalance")).get("type"));
        assertTrue(savedServices.containsKey("rerank"));
    }
}
