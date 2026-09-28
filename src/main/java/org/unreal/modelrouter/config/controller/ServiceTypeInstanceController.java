package org.unreal.modelrouter.config.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.common.dto.ServiceInstanceDTO;
import org.unreal.modelrouter.common.dto.InstanceRateLimitDTO;
import org.unreal.modelrouter.common.dto.InstanceCircuitBreakerDTO;
import org.unreal.modelrouter.config.dto.CreateServiceInstanceRequest;
import org.unreal.modelrouter.config.dto.ImportDiscoveredModelsRequest;
import org.unreal.modelrouter.config.dto.ModelDiscoveryRequest;
import org.unreal.modelrouter.config.dto.ModelDiscoveryResult;
import org.unreal.modelrouter.config.dto.ModelImportResult;
import org.unreal.modelrouter.persistence.jpa.entity.ServiceConfigEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceConfigRepository;
import org.unreal.modelrouter.config.core.ServiceConfigManager;
import org.unreal.modelrouter.config.core.ServiceInstanceManager;
import org.unreal.modelrouter.config.core.UpstreamModelDiscoveryException;
import org.unreal.modelrouter.config.core.UpstreamModelDiscoveryService;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按服务类型获取实例配置的控制器
 * 用于前端管理界面按服务类型查询实例
 */
@Slf4j
@RestController
@RequestMapping("/api/config/instance")
@RequiredArgsConstructor
public class ServiceTypeInstanceController {

    private final ServiceConfigRepository serviceConfigRepository;
    private final ServiceConfigManager serviceConfigManager;
    private final ServiceInstanceManager serviceInstanceManager;
    private final UpstreamModelDiscoveryService upstreamModelDiscoveryService;

    /**
     * 根据上游基础地址发现 OpenAI 兼容模型。
     */
    @PostMapping("/discover-models")
    public Mono<ResponseEntity<RouterResponse<ModelDiscoveryResult>>> discoverModels(
            @RequestBody final ModelDiscoveryRequest request) {
        return Mono.defer(() -> upstreamModelDiscoveryService.discover(request))
                .map(result -> ResponseEntity.ok(RouterResponse.success(result, "发现模型成功")))
                .onErrorResume(IllegalArgumentException.class, error -> Mono.just(
                        ResponseEntity.badRequest().body(RouterResponse.error(error.getMessage()))))
                .onErrorResume(UpstreamModelDiscoveryException.class, error -> Mono.just(
                        ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                                .body(RouterResponse.error("发现模型失败: " + error.getMessage()))))
                .onErrorResume(error -> {
                    log.warn("Discover upstream models failed: {}", error.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                            .body(RouterResponse.error("发现模型失败: " + error.getMessage())));
                });
    }

    /**
     * 批量导入发现的模型，同一服务类型下重名模型自动跳过。
     */
    @PostMapping("/{serviceType}/import-models")
    public ResponseEntity<RouterResponse<ModelImportResult>> importDiscoveredModels(
            @PathVariable final String serviceType,
            @RequestBody final ImportDiscoveredModelsRequest request) {
        if (request == null || request.getModelIds() == null || request.getModelIds().isEmpty()) {
            return ResponseEntity.badRequest().body(RouterResponse.error("至少选择一个模型"));
        }
        if (request.getBaseUrl() == null || request.getBaseUrl().isBlank()) {
            return ResponseEntity.badRequest().body(RouterResponse.error("基础 URL 不能为空"));
        }

        ServiceConfigEntity serviceConfig = serviceConfigManager.getOrCreateLatestServiceConfig(serviceType);
        Set<String> existingNames = serviceInstanceManager.getInstancesByServiceConfigId(serviceConfig.getId())
                .stream()
                .map(ServiceInstanceDTO::getName)
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        List<String> requestedModels = request.getModelIds().stream()
                .filter(modelId -> modelId != null && !modelId.isBlank())
                .map(String::trim)
                .toList();
        if (requestedModels.isEmpty()) {
            return ResponseEntity.badRequest().body(RouterResponse.error("至少选择一个有效模型"));
        }
        if (requestedModels.size() > 500) {
            return ResponseEntity.badRequest().body(RouterResponse.error("单次最多导入 500 个模型"));
        }

        Set<String> seenRequestedNames = new HashSet<>();
        List<String> skippedModels = new ArrayList<>();
        List<CreateServiceInstanceRequest> createRequests = new ArrayList<>();

        for (String modelId : requestedModels) {
            String normalizedName = modelId.toLowerCase(Locale.ROOT);
            if (!seenRequestedNames.add(normalizedName) || existingNames.contains(normalizedName)) {
                skippedModels.add(modelId);
                continue;
            }
            createRequests.add(CreateServiceInstanceRequest.builder()
                    .name(modelId)
                    .baseUrl(request.getBaseUrl().trim())
                    .path(request.getPath() == null ? "" : request.getPath().trim())
                    .weight(request.getWeight() == null ? 1 : request.getWeight())
                    .status(request.getStatus() == null ? "active" : request.getStatus())
                    .adapter(request.getAdapter())
                    .headers(request.getHeaders())
                    .build());
        }

        List<ServiceInstanceDTO> created = serviceInstanceManager.createInstances(
                serviceConfig.getId(), createRequests);
        ModelImportResult result = ModelImportResult.builder()
                .importedCount(created.size())
                .skippedCount(skippedModels.size())
                .skippedModels(skippedModels)
                .instances(created)
                .build();
        return ResponseEntity.ok(RouterResponse.success(result,
                "模型导入完成: 新增 " + created.size() + " 个，跳过 " + skippedModels.size() + " 个"));
    }

    /**
     * 获取指定服务类型的所有实例配置
     * @param serviceType 服务类型 (如 chat, embedding, rerank 等)
     * @return 该服务类型的实例列表（包装在 RouterResponse 中）
     */
    @GetMapping("/{serviceType}")
    public ResponseEntity<RouterResponse<List<ServiceInstanceDTO>>> getInstancesByServiceType(
            @PathVariable final String serviceType) {
        log.debug("Getting instances for service type: {}", serviceType);

        // 根据 serviceType 查找 serviceConfig
        ServiceConfigEntity serviceConfig = serviceConfigRepository
                .findFirstByServiceTypeAndIsLatestTrue(serviceType)
                .orElse(null);

        if (serviceConfig == null) {
            log.warn("Service config not found for type: {}", serviceType);
            return ResponseEntity.ok(RouterResponse.success(List.of()));
        }

        // 根据 serviceConfigId 获取实例列表
        List<ServiceInstanceDTO> instances = serviceInstanceManager
                .getInstancesByServiceConfigId(serviceConfig.getId());

        log.debug("Found {} instances for service type: {}", instances.size(), serviceType);
        return ResponseEntity.ok(RouterResponse.success(instances));
    }

    /**
     * 更新指定服务类型的实例配置（通过数据库ID）
     * @param serviceType 服务类型
     * @param instanceId 实例数据库ID
     * @param request 实例配置请求
     * @return 更新后的实例配置
     */
    @PutMapping("/{serviceType}/{instanceId}")
    public ResponseEntity<RouterResponse<ServiceInstanceDTO>> updateInstanceByServiceType(
            @PathVariable final String serviceType,
            @PathVariable final Long instanceId,
            @RequestBody final CreateServiceInstanceRequest request) {
        log.info("Updating instance: serviceType={}, instanceId={}", serviceType, instanceId);

        // 直接通过数据库ID更新实例
        ServiceInstanceDTO updated = serviceInstanceManager.updateInstance(instanceId, request);

        return ResponseEntity.ok(RouterResponse.success(updated, "实例配置更新成功"));
    }

    /**
     * 添加实例到指定服务类型
     * @param serviceType 服务类型
     * @param request 实例配置请求
     * @return 创建的实例配置
     */
    @PostMapping("/{serviceType}")
    public ResponseEntity<RouterResponse<ServiceInstanceDTO>> addInstanceByServiceType(
            @PathVariable final String serviceType,
            @RequestBody final CreateServiceInstanceRequest request) {
        log.info("Adding instance for service type: {}", serviceType);

        // YAML 中声明的服务类型在首次部署时可能尚无数据库父记录。
        // 新增第一个实例时自动补齐服务配置，避免前端必须先手工创建服务。
        ServiceConfigEntity serviceConfig = serviceConfigManager.getOrCreateLatestServiceConfig(serviceType);

        ServiceInstanceDTO created = serviceInstanceManager.createInstance(serviceConfig.getId(), request);
        return ResponseEntity.ok(RouterResponse.success(created, "实例添加成功"));
    }

    /**
     * 删除指定服务类型的实例（通过数据库ID）
     * @param serviceType 服务类型
     * @param instanceId 实例数据库ID
     * @return 操作结果
     */
    @DeleteMapping("/{serviceType}/{instanceId}")
    public ResponseEntity<RouterResponse<Void>> deleteInstanceByServiceType(
            @PathVariable final String serviceType,
            @PathVariable final Long instanceId) {
        log.info("Deleting instance: serviceType={}, instanceId={}", serviceType, instanceId);

        // 直接通过数据库ID删除实例
        serviceInstanceManager.deleteInstance(instanceId);

        return ResponseEntity.ok(RouterResponse.success(null, "实例删除成功"));
    }

    // ==================== 限流器配置 API ====================

    /**
     * 获取实例的限流器配置
     */
    @GetMapping("/{serviceType}/{instanceId}/rate-limit")
    public ResponseEntity<RouterResponse<InstanceRateLimitDTO>> getRateLimitConfig(
            @PathVariable final String serviceType,
            @PathVariable final Long instanceId) {
        log.info("Getting rate limit config: instanceId={}", instanceId);

        InstanceRateLimitDTO config = serviceInstanceManager.getRateLimitConfig(instanceId)
                .orElse(InstanceRateLimitDTO.builder()
                        .instanceId(instanceId)
                        .enabled(false)
                        .algorithm("token-bucket")
                        .capacity(100)
                        .rate(10)
                        .scope("instance")
                        .clientIpEnable(false)
                        .build());

        return ResponseEntity.ok(RouterResponse.success(config));
    }

    /**
     * 保存实例的限流器配置
     */
    @PutMapping("/{serviceType}/{instanceId}/rate-limit")
    public ResponseEntity<RouterResponse<InstanceRateLimitDTO>> saveRateLimitConfig(
            @PathVariable final String serviceType,
            @PathVariable final Long instanceId,
            @RequestBody final InstanceRateLimitDTO config) {
        log.info("Saving rate limit config: instanceId={}, enabled={}", instanceId, config.getEnabled());

        InstanceRateLimitDTO saved = serviceInstanceManager.saveRateLimitConfig(instanceId, config);
        return ResponseEntity.ok(RouterResponse.success(saved, "限流器配置保存成功"));
    }

    // ==================== 熔断器配置 API ====================

    /**
     * 获取实例的熔断器配置
     */
    @GetMapping("/{serviceType}/{instanceId}/circuit-breaker")
    public ResponseEntity<RouterResponse<InstanceCircuitBreakerDTO>> getCircuitBreakerConfig(
            @PathVariable final String serviceType,
            @PathVariable final Long instanceId) {
        log.info("Getting circuit breaker config: instanceId={}", instanceId);

        InstanceCircuitBreakerDTO config = serviceInstanceManager.getCircuitBreakerConfig(instanceId)
                .orElse(InstanceCircuitBreakerDTO.builder()
                        .instanceId(instanceId)
                        .enabled(false)
                        .failureThreshold(5)
                        .timeout(60000)
                        .successThreshold(2)
                        .build());

        return ResponseEntity.ok(RouterResponse.success(config));
    }

    /**
     * 保存实例的熔断器配置
     */
    @PutMapping("/{serviceType}/{instanceId}/circuit-breaker")
    public ResponseEntity<RouterResponse<InstanceCircuitBreakerDTO>> saveCircuitBreakerConfig(
            @PathVariable final String serviceType,
            @PathVariable final Long instanceId,
            @RequestBody final InstanceCircuitBreakerDTO config) {
        log.info("Saving circuit breaker config: instanceId={}, enabled={}", instanceId, config.getEnabled());

        InstanceCircuitBreakerDTO saved = serviceInstanceManager.saveCircuitBreakerConfig(instanceId, config);
        return ResponseEntity.ok(RouterResponse.success(saved, "熔断器配置保存成功"));
    }
}