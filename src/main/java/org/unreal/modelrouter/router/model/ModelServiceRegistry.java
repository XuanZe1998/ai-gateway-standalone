package org.unreal.modelrouter.router.model;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.lang.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.router.checker.ServiceStateManager;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreaker;
import org.unreal.modelrouter.router.circuitbreaker.CircuitBreakerManager;
import org.unreal.modelrouter.config.core.ConfigMergeService;
import org.unreal.modelrouter.router.fallback.FallbackManager;
import org.unreal.modelrouter.router.loadbalancer.LoadBalancer;
import org.unreal.modelrouter.router.loadbalancer.LoadBalancerManager;
import org.unreal.modelrouter.router.ratelimit.RateLimitContext;
import org.unreal.modelrouter.router.ratelimit.RateLimitManager;
import org.unreal.modelrouter.persistence.jpa.repository.ServiceInstanceRepository;
import org.unreal.modelrouter.platform.sync.PlatformDataSyncService;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformModelEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 模型服务注册表 - 重构版
 * 负责管理所有模型服务的注册、选择和状态管理
 * 支持动态配置更新和服务发现
 */
@Configuration
@EnableConfigurationProperties(ModelRouterProperties.class)
@org.springframework.context.annotation.DependsOn("jpaDatabaseInitializer")
public class ModelServiceRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelServiceRegistry.class);

    /**
     * 获取所有服务类型
     * @return 服务类型集合
     */
    public Set<String> getAllServiceTypes() {
         return Arrays.stream(ServiceType.values()).map(Enum::name).collect(Collectors.toSet());
    }

    public enum ServiceType {
        chat, embedding, rerank, tts, stt, imgGen, imgEdit, vidGen
    }

    // 依赖组件
    private final LoadBalancerManager loadBalancerManager;
    private final ServiceStateManager serviceStateManager;
    private final RateLimitManager rateLimitManager;
    private final CircuitBreakerManager circuitBreakerManager;
    private final FallbackManager fallbackManager;
    private final ConfigMergeService configMergeService;
    private final org.unreal.modelrouter.config.core.ConfigurationHelper configurationHelper;
    private final PlatformDataSyncService platformDataSyncService;

    @Autowired(required = false)
    private ServiceInstanceRepository serviceInstanceRepository;

    // 配置和缓存
    private final ModelRouterProperties originalProperties; // 原始YAML配置
    private volatile Map<String, Object> currentConfig; // 当前运行时配置
    private final Map<String, WebClient> webClientCache;

    // 运行时服务配置缓存
    private volatile Map<String, ServiceRuntimeConfig> serviceConfigCache;

    public ModelServiceRegistry(final ModelRouterProperties properties,
                                final ServiceStateManager serviceStateManager,
                                final RateLimitManager rateLimitManager,
                                final LoadBalancerManager loadBalancerManager,
                                final CircuitBreakerManager circuitBreakerManager,
                                final FallbackManager fallbackManager,
                                final ConfigMergeService configMergeService,
                                final org.unreal.modelrouter.config.core.ConfigurationHelper configurationHelper,
                                @Nullable final PlatformDataSyncService platformDataSyncService) {
        this.originalProperties = properties;
        this.serviceStateManager = serviceStateManager;
        this.rateLimitManager = rateLimitManager;
        this.loadBalancerManager = loadBalancerManager;
        this.circuitBreakerManager = circuitBreakerManager;
        this.fallbackManager = fallbackManager;
        this.configMergeService = configMergeService;
        this.configurationHelper = configurationHelper;
        this.platformDataSyncService = platformDataSyncService;
        this.webClientCache = new ConcurrentHashMap<>();
        this.serviceConfigCache = new ConcurrentHashMap<>();
    }

    /**
     * 初始化服务注册表
     * 在Spring容器初始化完成后执行
     */
    @PostConstruct
    public void initialize() {
        LOGGER.info("正在初始化ModelServiceRegistry...");

        try {
            // 1. 合并YAML和持久化配置
            LOGGER.debug("开始合并YAML和持久化配置");
            refreshFromMergedConfig();
            LOGGER.debug("配置合并完成，当前配置大小: {}", currentConfig != null ? currentConfig.size() : 0);

            // 2. 初始化各个管理器
            LOGGER.debug("开始初始化各个管理器");
            initializeManagers();
            LOGGER.debug("所有管理器初始化完成");

            LOGGER.info("ModelServiceRegistry初始化完成");
            logCurrentConfiguration();
        } catch (Exception e) {
            LOGGER.error("ModelServiceRegistry初始化失败", e);
            throw new RuntimeException("Failed to initialize ModelServiceRegistry", e);
        }
    }

    /**
     * 从合并配置中刷新运行时配置
     * 当配置发生变化时调用此方法
     *
     * 安全策略：先离线构建完整的新 cache（本地配置 + 平台实例），
     * 构建成功后再一次性替换引用，避免中间态空 cache 导致服务不可用。
     */
    public void refreshFromMergedConfig() {
        LOGGER.info("正在刷新运行时配置...");

        try {
            // 获取合并后的配置
            Map<String, Object> mergedConfig = configMergeService.getPersistedConfig();

            // 调整逻辑：如果有最新的配置文件，则使用最新的，如果没有，则使用默认配置
            if (mergedConfig == null || mergedConfig.isEmpty()) {
                LOGGER.info("未找到合并配置，使用默认配置");
                if (originalProperties != null) {
                    mergedConfig = configurationHelper.convertModelRouterPropertiesToMap(originalProperties);
                } else {
                    LOGGER.warn("原始配置也为空，使用空配置");
                    mergedConfig = new HashMap<>();
                }
            }

            this.currentConfig = mergedConfig;

            // 更新原始Properties对象（用于其他组件访问）
            updateOriginalPropertiesFromConfig(mergedConfig);

            // ★ 先离线构建完整的新 cache（本地配置 + 平台实例），再一次性替换
            Map<String, ServiceRuntimeConfig> newCache = buildNewCache(mergedConfig);

            // 清理 WebClient 缓存，让后续请求按新的 baseUrl 重建
            webClientCache.clear();

            // 一次性替换：newCache 已包含完整的本地配置 + 平台实例
            this.serviceConfigCache = newCache;

            // 重置熔断器（刷新后实例可能换了 baseUrl/密钥，旧熔断状态已无意义）
            circuitBreakerManager.clearAllCircuitBreakers();

            // 重置健康状态缓存（刷新后实例可能新增/变更，旧健康状态不应继承）
            serviceStateManager.resetAllHealthStatus();

            // 重新初始化负载均衡器
            reinitializeLoadBalancers();

            LOGGER.info("运行时配置刷新完成，当前包含 {} 个服务", serviceConfigCache.size());
        } catch (Exception e) {
            LOGGER.error("刷新运行时配置失败", e);
            throw new RuntimeException("刷新运行时配置失败", e);
        }
    }

    /**
     * 仅从算力平台同步实例（不重新加载本地配置）
     * 供 /internal/refresh 端点调用
     *
     * 安全策略：先离线构建新 cache（复制现有 + 平台数据），再一次性替换。
     */
    public void refreshFromPlatformOnly() {
        LOGGER.info("仅从算力平台刷新实例...");
        try {
            // ★ 离线构建：以现有 cache 为基础，叠加平台数据
            Map<String, ServiceRuntimeConfig> newCache = new ConcurrentHashMap<>();
            serviceConfigCache.forEach((key, config) ->
                    newCache.put(key, deepCopyRuntimeConfig(config)));

            mergePlatformInstancesInto(newCache);

            // 清理 WebClient 缓存，让后续请求按新的 baseUrl 重建
            webClientCache.clear();

            // 一次性替换
            this.serviceConfigCache = newCache;

            // 重置熔断器（刷新后实例可能换了 baseUrl/密钥，旧熔断状态已无意义）
            circuitBreakerManager.clearAllCircuitBreakers();

            // 重置健康状态缓存（刷新后实例可能新增/变更，旧健康状态不应继承）
            serviceStateManager.resetAllHealthStatus();

            reinitializeLoadBalancers();
            LOGGER.info("算力平台实例刷新完成，当前包含 {} 个服务", serviceConfigCache.size());
        } catch (Exception e) {
            LOGGER.error("从算力平台刷新实例失败", e);
            throw new RuntimeException("从算力平台刷新实例失败", e);
        }
    }

    /**
     * 离线构建完整的新 cache：本地配置 + 算力平台实例。
     * 构建过程中不影响正在服务的 serviceConfigCache。
     */
    private Map<String, ServiceRuntimeConfig> buildNewCache(final Map<String, Object> mergedConfig) {
        // 第1步：从本地配置构建基础 cache
        Map<String, ServiceRuntimeConfig> newCache = buildBaseCache(mergedConfig);

        // 第2步：合并算力平台实例（平台数据优先级高于本地配置）
        mergePlatformInstancesInto(newCache);

        LOGGER.info("新 cache 构建完成，包含 {} 个服务", newCache.size());
        return newCache;
    }

    /**
     * 从本地合并配置构建基础 cache（不含平台实例）。
     * 原 rebuildServiceConfigCache 的重构版本，不再修改 this.serviceConfigCache。
     */
    @SuppressWarnings("unchecked")
    private Map<String, ServiceRuntimeConfig> buildBaseCache(final Map<String, Object> mergedConfig) {
        Map<String, ServiceRuntimeConfig> cache = new ConcurrentHashMap<>();

        if (mergedConfig.containsKey("services")) {
            Map<String, Object> servicesMap = (Map<String, Object>) mergedConfig.get("services");

            // 添加空值检查，防止NullPointerException
            if (servicesMap != null) {
                for (Map.Entry<String, Object> entry : servicesMap.entrySet()) {
                    String rawKey = entry.getKey();
                    Map<String, Object> serviceConfigMap = (Map<String, Object>) entry.getValue();

                    try {
                        ServiceRuntimeConfig runtimeConfig = buildServiceRuntimeConfig(serviceConfigMap);
                        // 规范化 key 格式，与 getServiceKey(ServiceType) 和 detectServiceKey 保持一致（连字符格式）
                        String serviceKey = rawKey;
                        ModelServiceRegistry.ServiceType parsedType = configurationHelper.parseServiceType(rawKey);
                        if (parsedType != null) {
                            serviceKey = configurationHelper.getServiceConfigKey(parsedType);
                        }
                        cache.put(serviceKey, runtimeConfig);
                    } catch (Exception e) {
                        LOGGER.warn("构建服务 {} 的运行时配置失败: {}", rawKey, e.getMessage());
                    }
                }
            }
        }

        LOGGER.debug("基础 cache 构建完成，包含 {} 个服务", cache.size());
        return cache;
    }

    /**
     * 合并算力平台实例到指定的 cache 中（不影响正在服务的在线 cache）。
     * 平台数据优先级高于本地配置（同名实例会被覆盖）。
     */
    private void mergePlatformInstancesInto(final Map<String, ServiceRuntimeConfig> targetCache) {
        if (platformDataSyncService == null) {
            LOGGER.debug("PlatformDataSyncService 未注入，跳过算力平台实例同步");
            return;
        }

        try {
            List<org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformModelEntity> platformModels =
                    platformDataSyncService.syncPlatformModels();
            if (platformModels.isEmpty()) {
                LOGGER.info("算力平台无可用模型实例，继续使用本地配置");
                return;
            }

            // 预加载全部渠道，避免逐模型 N+1 查询；渠道加载失败不应阻塞模型加载
            java.util.Map<Long, org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformChannelEntity> channelMap;
            try {
                channelMap = platformDataSyncService.syncChannelsById();
            } catch (Exception e) {
                LOGGER.error("预加载算力平台渠道失败，继续按无渠道信息加载模型: {}", e.getMessage(), e);
                channelMap = java.util.Collections.emptyMap();
            }

            LOGGER.info("从算力平台合并 {} 个模型实例（渠道 {} 个）", platformModels.size(), channelMap.size());

            for (org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformModelEntity model : platformModels) {
                try {
                    String serviceKey = detectServiceKey(model);
                    ModelRouterProperties.ModelInstance instance = platformDataSyncService.translateToInstance(model, channelMap);
                    if (instance == null) {
                        continue;
                    }

                    ServiceRuntimeConfig runtimeConfig = targetCache.computeIfAbsent(serviceKey, k -> {
                        ServiceRuntimeConfig config = new ServiceRuntimeConfig();
                        config.setAdapter("normal");
                        return config;
                    });

                    // 去重：复制后替换，避免并发遍历 ConcurrentModificationException
                    List<ModelRouterProperties.ModelInstance> updated =
                            new ArrayList<>(runtimeConfig.getInstances());
                    updated.removeIf(inst -> inst.getName().equals(instance.getName()));
                    updated.add(instance);
                    runtimeConfig.setInstances(updated);

                    LOGGER.debug("已合并算力平台实例: {} -> 服务类型 {}", instance.getName(), serviceKey);
                } catch (Exception e) {
                    String modelName = model.getRealName() != null ? model.getRealName() : String.valueOf(model.getId());
                    LOGGER.error("合并算力平台模型[{}]失败，跳过该模型: {}", modelName, e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            LOGGER.error("合并算力平台实例失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 深拷贝 ServiceRuntimeConfig（浅拷贝实例列表，因为实例对象在刷新窗口内不会被修改）。
     */
    private ServiceRuntimeConfig deepCopyRuntimeConfig(final ServiceRuntimeConfig source) {
        ServiceRuntimeConfig copy = new ServiceRuntimeConfig();
        copy.setAdapter(source.getAdapter());
        copy.setLoadBalanceConfig(source.getLoadBalanceConfig());
        copy.setRateLimitConfig(source.getRateLimitConfig());
        copy.setCircuitBreakerConfig(source.getCircuitBreakerConfig());
        copy.setFallbackConfig(source.getFallbackConfig());
        copy.setInstances(new ArrayList<>(source.getInstances()));
        return copy;
    }

    /**
     * 选择服务实例
     */
    public ModelRouterProperties.ModelInstance selectInstance(final ServiceType serviceType,
                                                              final String modelName,
                                                              final String clientIp) {
        if (serviceType == null) {
            throw new IllegalArgumentException("ServiceType cannot be null");
        }
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new IllegalArgumentException("ModelName cannot be null or empty");
        }

        String serviceKey = getServiceKey(serviceType);
        ServiceRuntimeConfig runtimeConfig = serviceConfigCache.get(serviceKey);

        if (runtimeConfig == null || runtimeConfig.getInstances().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No instances found for model '" + modelName + "' in service type '" + serviceType + "'");
        }

        // 获取指定模型的所有实例
        List<ModelRouterProperties.ModelInstance> modelInstances = runtimeConfig.getInstances().stream()
                .filter(instance -> modelName.equals(instance.getName()))
                // 只选择status为空或者值等于"active"的实例（忽略大小写）
                .filter(instance -> instance.getStatus() != null
                        && "active".equalsIgnoreCase(instance.getStatus()))
                .collect(Collectors.toList());

        if (modelInstances.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No instances found for model '" + modelName + "' in service type '" + serviceType + "'");
        }

        // 健康检查
        List<ModelRouterProperties.ModelInstance> healthyInstances = modelInstances.stream()
                .filter(instance -> serviceStateManager.isInstanceHealthy(serviceType.name(), instance))
                .collect(Collectors.toList());

        if (healthyInstances.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No healthy instances found for model '" + modelName + "' in service type '" + serviceType + "'");
        }

        // 熔断器检查
        List<ModelRouterProperties.ModelInstance> availableInstances = healthyInstances.stream()
                .filter(instance -> circuitBreakerManager.canExecute(instance.getInstanceId(), instance.getBaseUrl()))
                .collect(Collectors.toList());

        if (availableInstances.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No available instances (all in circuit breaker state) for model '" + modelName + "'");
        }

        // 服务级、全局和客户端 IP 限流检查
        RateLimitContext serviceContext = new RateLimitContext(
                serviceType, modelName, clientIp, 1, null, null);
        if (!rateLimitManager.tryAcquireRequest(serviceContext)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Service rate limit exceeded for model '" + modelName + "'");
        }

        // 负载均衡选择实例
        LoadBalancer loadBalancer = loadBalancerManager.getLoadBalancer(serviceType);
        ModelRouterProperties.ModelInstance selectedInstance = selectInstanceWithRateLimit(
                availableInstances, loadBalancer, clientIp, serviceType, modelName);

        if (selectedInstance == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No available instances found after all checks for model '" + modelName + "'");
        }

        loadBalancer.recordCall(selectedInstance);
        return selectedInstance;
    }

    /**
     * 选择实例并进行实例级限流检查
     */
    private ModelRouterProperties.ModelInstance selectInstanceWithRateLimit(
            final List<ModelRouterProperties.ModelInstance> availableInstances,
            final LoadBalancer loadBalancer,
            final String clientIp,
            final ServiceType serviceType,
            final String modelName) {

        List<ModelRouterProperties.ModelInstance> candidateInstances = new ArrayList<>(availableInstances);
        int maxAttempts = Math.min(candidateInstances.size(), 3);

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (candidateInstances.isEmpty()) {
                break;
            }

            ModelRouterProperties.ModelInstance candidate = loadBalancer.selectInstance(candidateInstances, clientIp, serviceType.name().toLowerCase());

            // 实例级限流检查
            RateLimitContext instanceContext = new RateLimitContext(
                    serviceType, modelName, clientIp, 1,
                    candidate.getInstanceId(), candidate.getBaseUrl());

            if (!rateLimitManager.tryAcquireInstance(instanceContext)) {
                LOGGER.warn("Instance rate limit exceeded for instance: {}, trying next instance",
                        candidate.getInstanceId());
                candidateInstances.remove(candidate);
                continue;
            }

            return candidate;
        }

        return null;
    }

    /**
     * 获取WebClient
     */
    public WebClient getClient(final ServiceType serviceType, final String modelName, final String clientIp) {
        ModelRouterProperties.ModelInstance selectedInstance = selectInstance(serviceType, modelName, clientIp);
        return getWebClient(selectedInstance);
    }

    public WebClient getClient(final ServiceType serviceType, final String modelName) {
        return getClient(serviceType, modelName, null);
    }

    /**
     * 获取模型路径
     */
    public String getModelPath(final ServiceType serviceType, final String modelName) {
        String serviceKey = getServiceKey(serviceType);
        ServiceRuntimeConfig runtimeConfig = serviceConfigCache.get(serviceKey);

        if (runtimeConfig == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No service found for service type '" + serviceType + "'");
        }

        Optional<ModelRouterProperties.ModelInstance> instance = runtimeConfig.getInstances().stream()
                .filter(inst -> modelName.equals(inst.getName()))
                .findFirst();

        return instance.map(ModelRouterProperties.ModelInstance::getPath)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No model named '" + modelName + "' found for service type '" + serviceType + "'"));
    }

    /**
     * 获取服务适配器
     */
    public String getServiceAdapter(final ServiceType serviceType) {
        String serviceKey = getServiceKey(serviceType);
        ServiceRuntimeConfig runtimeConfig = serviceConfigCache.get(serviceKey);

        if (runtimeConfig != null && runtimeConfig.getAdapter() != null) {
            return runtimeConfig.getAdapter();
        }

        // 返回全局适配器或默认值
        return Optional.ofNullable(originalProperties.getAdapter()).orElse("normal");
    }

    /**
     * 记录调用完成
     */
    public void recordCallComplete(final ServiceType serviceType, final ModelRouterProperties.ModelInstance instance) {
        LoadBalancer loadBalancer = loadBalancerManager.getLoadBalancer(serviceType);
        if (loadBalancer != null) {
            loadBalancer.recordCallComplete(instance);
        }
        circuitBreakerManager.recordSuccess(instance.getInstanceId(), instance.getBaseUrl());
    }

    /**
     * 记录调用失败
     */
    public void recordCallFailure(final ServiceType serviceType, final ModelRouterProperties.ModelInstance instance) {
        LoadBalancer loadBalancer = loadBalancerManager.getLoadBalancer(serviceType);
        if (loadBalancer != null) {
            loadBalancer.recordCallFailure(instance);
        }
        circuitBreakerManager.recordFailure(instance.getInstanceId(), instance.getBaseUrl());
    }

    /**
     * 获取实例的熔断器状态
     */
    public CircuitBreaker.State getInstanceCircuitBreakerState(final ModelRouterProperties.ModelInstance instance) {
        return circuitBreakerManager.getState(instance.getInstanceId(), instance.getBaseUrl());
    }

    // ==================== 查询方法 ====================

    /**
     * 获取所有可用的服务类型
     */
    public Set<ServiceType> getAvailableServiceTypes() {
        return serviceConfigCache.entrySet().stream()
                .filter(entry -> !entry.getValue().getInstances().isEmpty())
                .map(entry -> parseServiceType(entry.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 获取指定服务类型的所有可用模型
     */
    public Set<String> getAvailableModels(final ServiceType serviceType) {
        String serviceKey = getServiceKey(serviceType);
        ServiceRuntimeConfig runtimeConfig = serviceConfigCache.get(serviceKey);

        if (runtimeConfig == null) {
            return Collections.emptySet();
        }

        return runtimeConfig.getInstances().stream()
                .map(ModelRouterProperties.ModelInstance::getName)
                .collect(Collectors.toSet());
    }

    /**
     * 获取所有实例信息
     */
    public Map<ServiceType, List<ModelRouterProperties.ModelInstance>> getAllInstances() {
        Map<ServiceType, List<ModelRouterProperties.ModelInstance>> result = new HashMap<>();

        for (Map.Entry<String, ServiceRuntimeConfig> entry : serviceConfigCache.entrySet()) {
            ServiceType serviceType = parseServiceType(entry.getKey());
            if (serviceType != null) {
                result.put(serviceType, new ArrayList<>(entry.getValue().getInstances()));
            }
        }

        return result;
    }

    /**
     * 获取服务配置
     */
    public ModelRouterProperties.ServiceConfig getServiceConfig(final ServiceType serviceType) {
        String serviceKey = getServiceKey(serviceType);
        ServiceRuntimeConfig runtimeConfig = serviceConfigCache.get(serviceKey);

        if (runtimeConfig == null) {
            return null;
        }

        // 构造ServiceConfig对象返回
        ModelRouterProperties.ServiceConfig serviceConfig = new ModelRouterProperties.ServiceConfig();
        serviceConfig.setInstances(new ArrayList<>(runtimeConfig.getInstances()));
        serviceConfig.setAdapter(runtimeConfig.getAdapter());
        serviceConfig.setLoadBalance(runtimeConfig.getLoadBalanceConfig());
        serviceConfig.setRateLimit(runtimeConfig.getRateLimitConfig());
        serviceConfig.setCircuitBreaker(runtimeConfig.getCircuitBreakerConfig());
        serviceConfig.setFallback(runtimeConfig.getFallbackConfig());

        return serviceConfig;
    }

    /**
     * 获取负载均衡策略
     */
    public String getLoadBalanceStrategy(final ServiceType serviceType) {
        LoadBalancer loadBalancer = loadBalancerManager.getLoadBalancer(serviceType);
        return loadBalancer != null ? loadBalancer.getClass().getSimpleName() : "Unknown";
    }

    public FallbackManager getFallbackManager() {
        return fallbackManager;
    }

    // ==================== 动态更新方法 ====================

    /**
     * 更新服务实例。
     * 采用原子替换策略（compute + 不可变快照），避免并发修改正在服务中的 config。
     */
    public void updateServiceInstances(final ServiceType serviceType, final List<ModelRouterProperties.ModelInstance> instances) {
        String serviceKey = getServiceKey(serviceType);
        ((ConcurrentHashMap<String, ServiceRuntimeConfig>) serviceConfigCache).compute(serviceKey, (key, existing) -> {
            if (existing == null) {
                return null; // 不存在则不创建
            }
            ServiceRuntimeConfig updated = deepCopyRuntimeConfig(existing);
            updated.setInstances(new ArrayList<>(instances));
            LOGGER.info("已更新服务 {} 的实例，共 {} 个实例", serviceType, instances.size());
            return updated;
        });
    }

    /**
     * 更新服务适配器
     */
    public void updateServiceAdapter(final ServiceType serviceType, final String adapter) {
        String serviceKey = getServiceKey(serviceType);
        ServiceRuntimeConfig runtimeConfig = serviceConfigCache.get(serviceKey);

        if (runtimeConfig != null) {
            runtimeConfig.setAdapter(adapter);
            LOGGER.info("已更新服务 {} 的适配器为: {}", serviceType, adapter);
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 初始化各个管理器
     */
    private void initializeManagers() {
        // 初始化熔断器管理器
        circuitBreakerManager.initialize(originalProperties);

        // 初始化降级管理器
        fallbackManager.initialize(originalProperties);

        LOGGER.debug("所有管理器初始化完成");
    }

    /**
     * 重新初始化负载均衡器
     */
    private void reinitializeLoadBalancers() {
        try {
            for (Map.Entry<String, ServiceRuntimeConfig> entry : serviceConfigCache.entrySet()) {
                ServiceType serviceType = parseServiceType(entry.getKey());
                if (serviceType != null && !entry.getValue().getInstances().isEmpty()) {
                    loadBalancerManager.reinitializeLoadBalancer(serviceType, entry.getValue().getLoadBalanceConfig());
                }
            }
            LOGGER.debug("负载均衡器重新初始化完成");
        } catch (Exception e) {
            LOGGER.warn("重新初始化负载均衡器时发生错误: {}", e.getMessage());
        }
    }

    /**
     * 从合并配置更新原始Properties对象
     */
    @SuppressWarnings("unchecked")
    private void updateOriginalPropertiesFromConfig(final Map<String, Object> mergedConfig) {
        try {
            // 更新全局配置
            if (mergedConfig.containsKey("adapter")) {
                originalProperties.setAdapter((String) mergedConfig.get("adapter"));
            }

            // 更新服务配置
            if (mergedConfig.containsKey("services")) {
                Map<String, Object> servicesMap = (Map<String, Object>) mergedConfig.get("services");
                Map<String, ModelRouterProperties.ServiceConfig> serviceConfigs = new HashMap<>();

                // 添加空值检查，防止NullPointerException
                if (servicesMap != null) {
                    for (Map.Entry<String, Object> entry : servicesMap.entrySet()) {
                        String serviceKey = entry.getKey();
                        Map<String, Object> serviceConfigMap = (Map<String, Object>) entry.getValue();
                        ModelRouterProperties.ServiceConfig serviceConfig =
                                configurationHelper.convertMapToServiceConfig(serviceConfigMap);
                        serviceConfigs.put(serviceKey, serviceConfig);
                    }

                    originalProperties.setServices(serviceConfigs);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("更新原始Properties对象时发生错误: {}", e.getMessage());
        }
    }

    /**
     * 根据算力平台 model_type 推断服务类型 key
     * 算力平台 modelType 字典：1-对话 2-图片生成 3-视频生成 4-语音 5-嵌入 6-重排序
     * 同时兼容旧字符串格式
     *
     * 注意：返回值必须与 getServiceKey(ServiceType) 格式一致（连字符小写），
     * 因为两者都用于访问 serviceConfigCache
     */
    private String detectServiceKey(PlatformModelEntity model) {
        String modelType = model.getModelType() != null ? model.getModelType().trim() : "chat";
        // 数字编码优先匹配
        return switch (modelType) {
            case "1" -> "chat";
            case "2" -> "img-gen";
            case "3" -> "vid-gen";
            case "4" -> "tts";       // 语音模型默认归入 TTS
            case "5" -> "embedding";
            case "6" -> "rerank";
            // 旧字符串格式（兼容）
            case "chat", "chat-completion" -> "chat";
            case "embedding", "embeddings" -> "embedding";
            case "rerank", "re-rank" -> "rerank";
            case "tts", "text-to-speech", "audio" -> "tts";
            case "stt", "speech-to-text" -> "stt";
            case "img-gen", "image-generation", "image" -> "img-gen";
            case "img-edit", "image-editing" -> "img-edit";
            default -> "chat";
        };
    }


    /**
     * 构建服务运行时配置
     */
    @SuppressWarnings("unchecked")
    private ServiceRuntimeConfig buildServiceRuntimeConfig(final Map<String, Object> serviceConfigMap) {
        ServiceRuntimeConfig runtimeConfig = new ServiceRuntimeConfig();

        // 解析实例列表
        if (serviceConfigMap.containsKey("instances")) {
            List<Map<String, Object>> instanceList = (List<Map<String, Object>>) serviceConfigMap.get("instances");
            List<ModelRouterProperties.ModelInstance> instances = instanceList.stream()
                    .map(configurationHelper::convertMapToInstance)
                    .collect(Collectors.toList());
            runtimeConfig.setInstances(instances);
        } else {
            runtimeConfig.setInstances(new ArrayList<>());
        }

        // 解析适配器
        runtimeConfig.setAdapter((String) serviceConfigMap.get("adapter"));

        // 解析负载均衡配置
        if (serviceConfigMap.containsKey("loadBalance")) {
            Map<String, Object> loadBalanceMap = (Map<String, Object>) serviceConfigMap.get("loadBalance");
            ModelRouterProperties.LoadBalanceConfig loadBalanceConfig = new ModelRouterProperties.LoadBalanceConfig();
            if (loadBalanceMap.containsKey("type")) {
                loadBalanceConfig.setType((String) loadBalanceMap.get("type"));
            }
            if (loadBalanceMap.containsKey("hashAlgorithm")) {
                loadBalanceConfig.setHashAlgorithm((String) loadBalanceMap.get("hashAlgorithm"));
            }
            runtimeConfig.setLoadBalanceConfig(loadBalanceConfig);
        } else {
            runtimeConfig.setLoadBalanceConfig(configurationHelper.createDefaultLoadBalanceConfig());
        }

        // 解析其他配置（限流、熔断器、降级）
        parseAdditionalConfigs(serviceConfigMap, runtimeConfig);

        return runtimeConfig;
    }

    /**
     * 解析额外配置（限流、熔断器、降级）
     */
    @SuppressWarnings("unchecked")
    private void parseAdditionalConfigs(final Map<String, Object> serviceConfigMap,
                                        final ServiceRuntimeConfig runtimeConfig) {
        // 限流配置
        if (serviceConfigMap.containsKey("rateLimit")) {
            Map<String, Object> rateLimitMap = (Map<String, Object>) serviceConfigMap.get("rateLimit");
            if (rateLimitMap != null) {
                ModelRouterProperties.RateLimitConfig rateLimitConfig =
                        new ModelRouterProperties.RateLimitConfig();
                configurationHelper.updateRateLimitConfig(rateLimitConfig, rateLimitMap);
                runtimeConfig.setRateLimitConfig(rateLimitConfig);
            } else {
                runtimeConfig.setRateLimitConfig(rateLimitManager.getDefaultRateLimitConfig());
            }
        }

        // 熔断器配置
        if (serviceConfigMap.containsKey("circuitBreaker")) {
            Map<String, Object> circuitBreakerMap =
                    (Map<String, Object>) serviceConfigMap.get("circuitBreaker");
            if (circuitBreakerMap != null) {
                ModelRouterProperties.CircuitBreakerConfig circuitBreakerConfig =
                        new ModelRouterProperties.CircuitBreakerConfig();
                configurationHelper.updateCircuitBreakerConfig(circuitBreakerConfig, circuitBreakerMap);
                runtimeConfig.setCircuitBreakerConfig(circuitBreakerConfig);
            } else {
                runtimeConfig.setCircuitBreakerConfig(circuitBreakerManager.getDefaultCircuitBreakerConfig());
            }
        }

        // 降级配置
        if (serviceConfigMap.containsKey("fallback")) {
            Map<String, Object> fallbackMap = (Map<String, Object>) serviceConfigMap.get("fallback");
            if (fallbackMap != null) {
                ModelRouterProperties.FallbackConfig fallbackConfig =
                        new ModelRouterProperties.FallbackConfig();
                configurationHelper.updateFallbackConfig(fallbackConfig, fallbackMap);
                runtimeConfig.setFallbackConfig(fallbackConfig);
            } else {
                runtimeConfig.setFallbackConfig(fallbackManager.getDefaultFallbackConfig());
            }
        }
    }


    /**
     * 获取WebClient
     */
    private WebClient getWebClient(final ModelRouterProperties.ModelInstance instance) {
        String key = instance.getBaseUrl();
        return webClientCache.computeIfAbsent(key, url -> {
            // 尝试获取追踪WebClient工厂
            try {
                org.unreal.modelrouter.monitor.tracing.client.TracingWebClientFactory tracingFactory = 
                    org.unreal.modelrouter.common.util.ApplicationContextProvider.getBean(
                        org.unreal.modelrouter.monitor.tracing.client.TracingWebClientFactory.class);
                return tracingFactory.createTracingWebClient(url);
            } catch (Exception e) {
                // 如果追踪功能不可用，创建普通WebClient
                return WebClient.builder().baseUrl(url).build();
            }
        });
    }

    /**
     * 获取服务键
     */
    private String getServiceKey(final ServiceType serviceType) {
        return configurationHelper.getServiceConfigKey(serviceType);
    }

    /**
     * 解析服务类型
     */
    private ServiceType parseServiceType(final String serviceKey) {
        return configurationHelper.parseServiceType(serviceKey);
    }

    /**
     * 记录当前配置信息
     */
    private void logCurrentConfiguration() {
        LOGGER.info("当前服务配置概览:");
        for (Map.Entry<String, ServiceRuntimeConfig> entry : serviceConfigCache.entrySet()) {
            String serviceKey = entry.getKey();
            ServiceRuntimeConfig config = entry.getValue();
            LOGGER.info("  服务 {}: {} 个实例, 适配器={}, 负载均衡={}",
                    serviceKey,
                    config.getInstances().size(),
                    config.getAdapter() != null ? config.getAdapter() : "默认",
                    config.getLoadBalanceConfig() != null ? config.getLoadBalanceConfig().getType() : "默认");
        }
    }

}
