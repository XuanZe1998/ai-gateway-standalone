package org.unreal.modelrouter.platform.sync;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.auth.security.util.RealNameAuthUtils;
import org.unreal.modelrouter.billing.ModelPricingService;
import org.unreal.modelrouter.persistence.jpa.entity.platform.*;
import org.unreal.modelrouter.persistence.jpa.repository.platform.*;
import org.unreal.modelrouter.router.model.ModelRouterProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 算力平台数据同步服务
 *
 * 从算力平台表（ai_model, ai_channel, ai_api_key）读取数据，
 * 翻译为 JAiRouter 运行时所需的配置结构。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformDataSyncService {

    private final PlatformModelRepository modelRepository;
    private final PlatformChannelRepository channelRepository;
    private final PlatformApiKeyRepository apiKeyRepository;
    private final PlatformEnterpriseRepository enterpriseRepository;
    private final PlatformSystemUserRepository systemUserRepository;
    private final org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformUserCompanyRepository userCompanyRepository;
    private final PlatformModelPriceTierRepository priceTierRepository;
    private final PlatformVideoPriceRepository videoPriceRepository;

    /**
     * 获取所有有效的原始模型实体（供 ModelServiceRegistry 按 modelType 分组）
     */
    public List<PlatformModelEntity> syncPlatformModels() {
        // 算力平台 ai_model.status：1=上架，2=下架/草稿；只加载已上架模型
        List<PlatformModelEntity> models = modelRepository.findAllOnlineModels();
        log.info("从算力平台加载 {} 个有效模型", models.size());
        return models;
    }

    /**
     * 获取所有有效的渠道配置，按 ID 索引（供翻译时批量查渠道，避免 N+1）
     */
    public Map<Long, PlatformChannelEntity> syncChannelsById() {
        List<PlatformChannelEntity> channels = channelRepository.findAll();
        log.info("从算力平台加载 {} 个渠道", channels.size());
        return channels.stream()
                .filter(c -> Boolean.FALSE.equals(c.getDeleted()))
                .collect(Collectors.toMap(PlatformChannelEntity::getId, c -> c, (a, b) -> a));
    }

    /**
     * 将平台模型实体翻译为 JAiRouter 实例（供 ModelServiceRegistry 调用）
     * 使用预加载的渠道 Map 避免逐个查 DB。
     */
    public ModelRouterProperties.ModelInstance translateToInstance(PlatformModelEntity model,
                                                                    Map<Long, PlatformChannelEntity> channelMap) {
        return translateModelToInstance(model, channelMap);
    }

    /**
     * 将平台模型实体翻译为 JAiRouter 实例（兼容旧调用，内部逐个查 DB）
     */
    public ModelRouterProperties.ModelInstance translateToInstance(PlatformModelEntity model) {
        return translateModelToInstance(model, null);
    }

    /**
     * 获取所有有效的模型配置（用于构建 ModelServiceRegistry 的实例列表）
     */
    public List<ModelRouterProperties.ModelInstance> syncModelInstances() {
        Map<Long, PlatformChannelEntity> channelMap = syncChannelsById();
        return syncPlatformModels().stream()
                .map(model -> translateModelToInstance(model, channelMap))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有有效的渠道配置（用于构建 ServiceConfig）
     */
    public Map<String, PlatformChannelEntity> syncChannels() {
        List<PlatformChannelEntity> channels = channelRepository.findAll();
        log.info("从算力平台加载 {} 个渠道", channels.size());

        return channels.stream()
                .filter(c -> Boolean.FALSE.equals(c.getDeleted()))
                .filter(c -> c.getName() != null)
                .collect(Collectors.toMap(PlatformChannelEntity::getName, c -> c, (a, b) -> a));
    }

    /**
     * 获取所有有效的 API Key（用于替换本地缓存）
     */
    public List<PlatformApiKeyEntity> syncApiKeys() {
        List<PlatformApiKeyEntity> keys = apiKeyRepository.findAll();
        log.info("从算力平台加载 {} 个 API Key", keys.size());

        return keys.stream()
                .filter(k -> Boolean.FALSE.equals(k.getDeleted()))
                .filter(k -> !isExpired(k))
                .filter(k -> isApiKeyActive(k))
                .collect(Collectors.toList());
    }

    /**
     * 根据模型真实名称查询定价信息（用于 ModelPricingService）
     */
    public Optional<ModelPricingService.ModelPricing> getModelPricing(String realName) {
        return modelRepository.findByRealNameAndDeletedFalse(realName)
                .filter(PlatformDataSyncService::hasUsablePricing)
                .map(m -> buildPricing(m, m.getChannelId()));
    }

    /**
     * 定价可用性判断：视频模型（model_type=3）价格在子表 ai_model_video_price，一律放行
     * （规则行缺失时由计费侧拒计费兜底）；整体计费（billingMode!=2）要求主表
     * input/output 价格非空；阶梯计费（billingMode=2）主表价格可为 null（价格在档位表中），直接放行。
     */
    private static boolean hasUsablePricing(PlatformModelEntity m) {
        if (isVideoModel(m)) {
            return true;
        }
        if (Integer.valueOf(2).equals(m.getBillingMode())) {
            return true;
        }
        return m.getInputPrice() != null && m.getOutputPrice() != null;
    }

    /** 视频模型判定：算力平台 modelType 字典中 3=视频生成 */
    private static boolean isVideoModel(PlatformModelEntity m) {
        return m.getModelType() != null && "3".equals(m.getModelType().trim());
    }

    /**
     * 从 PlatformModelEntity 构造 ModelPricing（ getModelPricing / syncAllPricings 共用）。
     *
     * <p>处理：价格单位转换（元/M token → 元/token）、enable 标志位默认值兜底、
     * 阶梯档位加载（billingMode=2 时，tier_lower/upper_limit 是 K 值需 ×1000 转 token）、
     * 视频模型分辨率价格规则加载（model_type=3，分辨率归一化大写、enabled 过滤、单位转换）。
     */
    private ModelPricingService.ModelPricing buildPricing(PlatformModelEntity m, String channelId) {
        BigDecimal divisor = new BigDecimal("1000000");
        BigDecimal inputPrice = toPerToken(m.getInputPrice(), divisor);
        BigDecimal cacheHitPrice = toPerToken(m.getCacheHitInputPrice(), divisor);
        BigDecimal outputPrice = toPerToken(m.getOutputPrice(), divisor);
        BigDecimal cacheCreatePrice = toPerToken(m.getCacheCreateInputPrice(), divisor);
        BigDecimal cacheHitExplicitPrice = toPerToken(m.getCacheHitExplicitInputPrice(), divisor);
        BigDecimal thinkingPrice = toPerToken(m.getThinkingPrice(), divisor);

        BigDecimal discountRate = m.getDiscount() != null
                ? new BigDecimal(m.getDiscount()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)
                : BigDecimal.ONE;

        // enable 标志位默认值：input/cacheHit/output 默认 true（向后兼容），显式缓存默认 false（与元数据文档一致）
        boolean enableInput = !Boolean.FALSE.equals(m.getEnableInputToken());
        boolean enableCacheHit = !Boolean.FALSE.equals(m.getEnableCacheHitInput());
        boolean enableOutput = !Boolean.FALSE.equals(m.getEnableOutputToken());
        boolean enableCacheCreate = Boolean.TRUE.equals(m.getEnableCacheCreateInput());
        boolean enableCacheHitExplicit = Boolean.TRUE.equals(m.getEnableCacheHitExplicitInput());

        // 阶梯档位（billingMode=2 时加载，⚠ tier_lower/upper_limit 表存 K 值，×1000 转 token）
        List<ModelPricingService.ModelPricing.PriceTier> tiers = List.of();
        if (Integer.valueOf(2).equals(m.getBillingMode()) && m.getId() != null) {
            BigDecimal k = new BigDecimal("1000");
            tiers = priceTierRepository.findByModelIdOrderByTierOrderAsc(m.getId()).stream()
                    .map(t -> new ModelPricingService.ModelPricing.PriceTier(
                            t.getTierLowerLimit() != null ? t.getTierLowerLimit().multiply(k) : null,
                            Boolean.TRUE.equals(t.getIsUnlimited()) ? null
                                    : (t.getTierUpperLimit() != null ? t.getTierUpperLimit().multiply(k) : null),
                            Boolean.TRUE.equals(t.getIsUnlimited()),
                            toPerToken(t.getInputPrice(), divisor),
                            toPerToken(t.getCacheHitInputPrice(), divisor),
                            toPerToken(t.getOutputPrice(), divisor),
                            toPerToken(t.getCacheCreateInputPrice(), divisor),
                            toPerToken(t.getCacheHitExplicitInputPrice(), divisor),
                            // 思考token档位价：仅 thinking_billing_mode=2 时有效，其余模式 NULL 也不读取（计费侧先判模式）
                            toPerToken(t.getThinkingPrice(), divisor)))
                    .toList();
        }

        // 视频模型分辨率价格规则（model_type=3；非视频模型 priceMode/billingUnit 置 null，计费侧不走视频分支）
        Integer priceMode = null;
        String billingUnit = null;
        List<ModelPricingService.ModelPricing.VideoPriceRule> videoRules = List.of();
        if (isVideoModel(m)) {
            priceMode = m.getPriceMode() != null ? m.getPriceMode() : 1;
            final String unit = m.getBillingUnit() != null && !m.getBillingUnit().isBlank()
                    ? m.getBillingUnit() : "second";
            billingUnit = unit;
            if (m.getId() != null) {
                videoRules = videoPriceRepository.findByModelIdAndDeletedFalseOrderById(m.getId()).stream()
                        // 停用规则（enabled=false）不参与计费；null 视为启用（默认 true）
                        .filter(r -> !Boolean.FALSE.equals(r.getEnabled()))
                        // 逻辑删除行（平台数据异常遗留）不参与计费（Repository 已过滤，此处双保险）
                        .filter(r -> !Boolean.TRUE.equals(r.getDeleted()))
                        // 价格为空或非正数（平台配置疏忽：先建行后填价）不参与计费，防止按 0 元落账
                        .filter(r -> r.getPrice() != null && r.getPrice().compareTo(BigDecimal.ZERO) > 0)
                        .map(r -> new ModelPricingService.ModelPricing.VideoPriceRule(
                                normalizeResolution(r.getOutputResolution()),
                                r.getHasVideoInput(),
                                toVideoUnitPrice(r.getPrice(), unit, divisor)))
                        .toList();
            }
        }

        return new ModelPricingService.ModelPricing(
                m.getRealName(), channelId, m.getId(), m.getVendor(),
                m.getBillingMode(),
                inputPrice, cacheHitPrice, outputPrice, cacheCreatePrice, cacheHitExplicitPrice,
                enableInput, enableCacheHit, enableOutput, enableCacheCreate, enableCacheHitExplicit,
                m.getThinkingBillingMode(), thinkingPrice,
                discountRate, tiers,
                priceMode, billingUnit, videoRules);
    }

    /** 元/M token → 元/token（null 返回 ZERO） */
    private static BigDecimal toPerToken(BigDecimal pricePerMillion, BigDecimal divisor) {
        return pricePerMillion != null
                ? pricePerMillion.divide(divisor, 10, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }

    /**
     * 视频规则单价单位转换：second（元/秒）保持原值；token（元/M token）÷1e6 转元/token。
     */
    private static BigDecimal toVideoUnitPrice(BigDecimal price, String billingUnit, BigDecimal divisor) {
        if (price == null) {
            return BigDecimal.ZERO;
        }
        if ("token".equals(billingUnit)) {
            return price.divide(divisor, 10, RoundingMode.HALF_UP);
        }
        return price;
    }

    /**
     * 分辨率归一化为大写（"720p"→"720P"、"4k"→"4K"），与元数据口径一致，
     * 消除上游响应（小写 p/k）与元数据（大写）之间的大小写差异。
     */
    private static String normalizeResolution(String resolution) {
        if (resolution == null || resolution.isBlank()) {
            return null;
        }
        return resolution.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 根据 API Key 查找用户信息（用于认证和计费）。
     * 算力平台 ai_api_key.key_hash 存的是 AES 加密后的密文（不是哈希），
     * 需要遍历有效 key 解密后与请求中的明文比对。
     * 同时通过 user_id → sldd_system_users 校验实名认证状态并关联 ai_enterprise。
     */
    public Optional<UserIdentity> getUserIdentityByApiKey(String apiKey) {
        ApiKeyLookupResult result = lookupApiKey(apiKey);
        return Optional.ofNullable(result.identity());
    }

    /**
     * API Key 查找结果，区分三种情况：未找到 / 已过期 / 有效。
     */
    public record ApiKeyLookupResult(UserIdentity identity, boolean expired, java.time.LocalDateTime expireTime) {
        /** 是否找到了匹配的 key（不管是否过期） */
        public boolean isFound() { return identity != null || expired; }
    }

    /**
     * 根据 API Key 查找并返回详细结果（区分未找到 / 已过期 / 有效）。
     * 先解密匹配，匹配成功后再检查过期，确保能精确区分三种状态。
     */
    public ApiKeyLookupResult lookupApiKey(String apiKey) {
        List<PlatformApiKeyEntity> activeKeys = apiKeyRepository.findByDeletedFalse();
        for (PlatformApiKeyEntity entity : activeKeys) {
            if (!isApiKeyActive(entity)) {
                continue;
            }
            try {
                String decrypted = PlatformAesCryptoUtil.decrypt(entity.getKeyHash());
                if (apiKey.equals(decrypted)) {
                    // 匹配成功 → 再检查是否过期
                    if (isExpired(entity)) {
                        log.info("API Key已过期: id={}, userAccount={}, expireTime={}",
                                entity.getId(), entity.getUserAccount(), entity.getExpireTime());
                        return new ApiKeyLookupResult(null, true, entity.getExpireTime());
                    }
                    // 未过期，校验实名认证状态并关联企业
                    UserLink link = resolveUserLink(entity.getUserId());
                    if (link == null) {
                        log.warn("API Key 关联用户实名认证未通过: id={}, userAccount={}, userId={}",
                                entity.getId(), entity.getUserAccount(), entity.getUserId());
                        return new ApiKeyLookupResult(null, false, null);
                    }
                    String userId = link.systemUserId() != null ? link.systemUserId() : entity.getUserAccount();
                    return new ApiKeyLookupResult(new UserIdentity(
                            userId, entity.getUserAccount(),
                            String.valueOf(entity.getId()),
                            entity.getDescription(),
                            link.enterpriseId(),
                            link.enterpriseName(),
                            link.companyId(),
                            true,
                            link.userType(),
                            entity.getUserId(),
                            link.verifyStatus()
                    ), false, entity.getExpireTime());
                }
            } catch (Exception e) {
                log.debug("解密 API Key [id={}] 失败，跳过: {}", entity.getId(), e.getMessage());
            }
        }
        // 未找到匹配的 key
        return new ApiKeyLookupResult(null, false, null);
    }

    /**
     * 通过 user_id 校验实名认证并尝试关联企业：
     * ai_api_key.user_id → sldd_system_users.id(PK) → 实名认证校验 → company_id → ai_enterprise（可选）
     */
    private UserLink resolveUserLink(Long platformUserId) {
        if (platformUserId == null) {
            log.info("用户关联跳过: platformUserId 为 null");
            return null;
        }
        try {
            var systemUser = systemUserRepository.findById(platformUserId);
            if (systemUser.isEmpty()) {
                log.info("用户关联失败: sldd_system_users 中找不到 id={}", platformUserId);
                return null;
            }
            var user = systemUser.get();

            // 实名认证校验：必须严格成对
            Integer userType = user.getUserType();
            Integer verifyStatus = user.getVerifyStatus();
            if (!RealNameAuthUtils.isRealNameAuthenticated(userType, verifyStatus)) {
                log.warn("实名认证校验失败: platformUserId={}, userType={}, verifyStatus={}",
                        platformUserId, userType, verifyStatus);
                return null;
            }

            // 尝试关联企业（企业用户通常有，个人用户可能没有）
            Long enterpriseId = null;
            String enterpriseName = null;
            String companyId = user.getCompanyId();
            if (companyId != null) {
                var enterprise = enterpriseRepository.findByCompanyIdAndDeletedFalse(companyId);
                if (enterprise.isPresent()) {
                    var ent = enterprise.get();
                    enterpriseId = ent.getId();
                    enterpriseName = userCompanyRepository.findById(ent.getCompanyId())
                            .map(uc -> uc.getCompanyName())
                            .filter(name -> name != null && !name.isBlank())
                            .orElse(ent.getCompanyId());
                    log.info("用户关联成功: platformUserId={} → companyId={} → enterprise(id={}, name={})",
                            platformUserId, companyId, ent.getId(), enterpriseName);
                } else {
                    log.info("用户关联: platformUserId={} 的 company_id={} 未找到有效企业，按个人用户处理",
                            platformUserId, companyId);
                }
            } else {
                log.info("用户关联: platformUserId={} 的 company_id 为 null，按个人用户处理", platformUserId);
            }
            return new UserLink(enterpriseId, enterpriseName, companyId, user.getUserId(), userType, verifyStatus);
        } catch (Exception e) {
            log.error("关联用户异常, platformUserId={}: {}", platformUserId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 为统一认证用户构建与 API Key 认证相同的实名、企业和计费身份。
     *
     * @param platformUserId sldd_system_users 主键
     * @param userAccount OIDC 用户账号
     * @return 实名认证通过后的用户身份
     */
    public Optional<UserIdentity> getUserIdentityBySystemUserId(
            final Long platformUserId, final String userAccount) {
        UserLink link = resolveUserLink(platformUserId);
        if (link == null) {
            return Optional.empty();
        }
        return Optional.of(new UserIdentity(
                link.systemUserId() != null ? link.systemUserId() : String.valueOf(platformUserId),
                userAccount,
                null,
                null,
                link.enterpriseId(),
                link.enterpriseName(),
                link.companyId(),
                true,
                link.userType(),
                platformUserId,
                link.verifyStatus()
        ));
    }

    /** 用户关联结果（含实名认证信息与企业关联信息，企业关联可为空） */
    private record UserLink(Long enterpriseId, String enterpriseName, String companyId,
                            String systemUserId, Integer userType, Integer verifyStatus) {}

    /**
     * 批量加载所有有效模型的定价（用于 ModelPricingService 初始化）
     */
    public List<ModelPricingService.ModelPricing> syncAllPricings() {
        List<PlatformModelEntity> models = modelRepository.findAllOnlineModels();
        return models.stream()
                .filter(PlatformDataSyncService::hasUsablePricing)
                .flatMap(m -> java.util.stream.Stream.of(
                        // 1. 精确匹配：modelName + channelId
                        buildPricing(m, m.getChannelId()),
                        // 2. fallback：只按 modelName（不带渠道）
                        buildPricing(m, null)))
                .collect(Collectors.toList());
    }

    /**
     * 校验 API Key 是否有效（用于 ApiKeyService 的额外校验源）。
     * 算力平台 ai_api_key.key_hash 存的是 AES 加密后的密文（不是哈希），
     * 需要遍历有效 key 解密后与请求中的明文比对。
     */
    public boolean validateApiKey(String apiKey) {
        List<PlatformApiKeyEntity> activeKeys = apiKeyRepository.findByDeletedFalse();
        for (PlatformApiKeyEntity entity : activeKeys) {
            if (!isApiKeyActive(entity)) {
                continue;
            }
            if (isExpired(entity)) {
                continue;
            }
            try {
                String decrypted = PlatformAesCryptoUtil.decrypt(entity.getKeyHash());
                if (apiKey.equals(decrypted)) {
                    return true;
                }
            } catch (Exception e) {
                log.debug("解密 API Key [id={}] 失败，跳过: {}", entity.getId(), e.getMessage());
            }
        }
        return false;
    }

    // ====== 内部方法 ======

    private ModelRouterProperties.ModelInstance translateModelToInstance(PlatformModelEntity model,
                                                                         Map<Long, PlatformChannelEntity> channelMap) {
        if (model.getRealName() == null) {
            String id = model.getId() != null ? String.valueOf(model.getId()) : "unknown";
            log.warn("模型[id={}] 缺少 realName，跳过", id);
            return null;
        }

        // === 0. 查找渠道（优先从预加载 Map 查，兜底逐个查 DB） ===
        Long channelId = parseChannelId(model.getChannelId());
        PlatformChannelEntity channel = null;
        if (channelId != null) {
            if (channelMap != null) {
                channel = channelMap.get(channelId);
            } else {
                channel = channelRepository.findByIdAndDeletedFalse(channelId).orElse(null);
            }
            if (channel == null) {
                log.warn("模型[{}] 关联的渠道[id={}] 不存在或已删除", model.getRealName(), channelId);
            }
        }

        // === 1. baseUrl + path 解析 ===
        // 优先级：渠道服务级 URL（chatCompletionUrl 等） > 模型 baseUrl > 渠道 baseUrl
        // 渠道服务级 URL 可能是完整地址（http://...）或纯路径（/v1/...）
        String channelServiceUrl = resolveChannelServiceUrl(channel, model.getModelType());
        String baseUrl;
        String path;

        if (channelServiceUrl != null && !channelServiceUrl.isBlank()) {
            if (channelServiceUrl.startsWith("http://") || channelServiceUrl.startsWith("https://")) {
                // 渠道配了完整 URL → 直接用作 baseUrl，path 留空
                baseUrl = channelServiceUrl;
                path = "";
                log.debug("模型[{}] 使用渠道完整服务 URL: {}", model.getRealName(), channelServiceUrl);
            } else {
                // 渠道配了自定义路径 → baseUrl 按原规则解析，path 用渠道的
                baseUrl = resolveBaseUrl(model, channel);
                path = channelServiceUrl.startsWith("/") ? channelServiceUrl : "/" + channelServiceUrl;
                log.debug("模型[{}] 使用渠道自定义路径: {} (baseUrl={})", model.getRealName(), path, baseUrl);
            }
        } else {
            // 渠道未配服务级 URL → 兜底硬编码路径
            baseUrl = resolveBaseUrl(model, channel);
            path = detectPath(model);
            log.debug("模型[{}] 使用默认路径: {} (baseUrl={})", model.getRealName(), path, baseUrl);
        }

        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("模型[realName={}] 未配置 baseUrl 且所属渠道也未配置，跳过", model.getRealName());
            return null;
        }

        ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
        instance.setId(model.getId() != null ? String.valueOf(model.getId()) : model.getRealName());
        instance.setInstanceId(model.getId() != null ? String.valueOf(model.getId()) : model.getRealName());
        instance.setName(model.getRealName());
        instance.setBaseUrl(baseUrl);
        instance.setPath(path);
        instance.setWeight(100);
        instance.setStatus("active");
        instance.setAdapter(detectAdapter(model));
        instance.setChannelId(model.getChannelId()); // 设置关联渠道ID（算力平台外键）
        instance.setVendor(model.getVendor()); // 厂商标识，用于 usage 归一化（TokenUsageExtractor）
        instance.setTimeout(channel != null ? channel.getTimeout() : null); // 渠道超时时间（秒）
        log.debug("模型[{}] 渠道超时: {}s", model.getRealName(), instance.getTimeout());

        // === 2. API Key：优先模型 → 回退渠道，未配置则报错跳过 ===
        String apiKey = null;
        if (model.getApiKey() != null && !model.getApiKey().isEmpty()) {
            apiKey = model.getApiKey();
            log.debug("模型[{}] 使用模型级 API Key", model.getRealName());
        } else if (channel != null && channel.getApiKey() != null && !channel.getApiKey().isEmpty()) {
            apiKey = channel.getApiKey();
            log.debug("模型[{}] 使用渠道[{}]级 API Key", model.getRealName(), model.getChannelId());
        }
        if (apiKey == null || apiKey.isEmpty()) {
            log.error("模型[{}] 未配置 API Key（模型和渠道均无），跳过该模型", model.getRealName());
            return null;
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        instance.setHeaders(headers);

        return instance;
    }

    /**
     * 解析 baseUrl：优先模型自身配置，回退到渠道 baseUrl。
     */
    private String resolveBaseUrl(PlatformModelEntity model, PlatformChannelEntity channel) {
        if (model.getBaseUrl() != null && !model.getBaseUrl().isBlank()) {
            return model.getBaseUrl();
        }
        if (channel != null && channel.getBaseUrl() != null && !channel.getBaseUrl().isBlank()) {
            log.debug("模型[{}] 回退到渠道[id={}] 的 baseUrl: {}",
                    model.getRealName(), model.getChannelId(), channel.getBaseUrl());
            return channel.getBaseUrl();
        }
        return null;
    }

    /**
     * 从渠道获取服务级别的 URL（按模型类型匹配对应字段）。
     * 算力平台渠道为每种服务类型配了独立的 URL 后缀，优先于硬编码路径。
     *
     * @return 渠道配置的服务 URL（可能是完整 URL 或纯路径），null 表示未配置
     */
    private String resolveChannelServiceUrl(PlatformChannelEntity channel, String modelType) {
        if (channel == null) {
            return null;
        }
        String type = modelType != null ? modelType.trim() : "";
        return switch (type) {
            case "1", "chat" -> channel.getChatCompletionUrl();
            case "2", "image" -> channel.getImageUrl();
            case "3", "video" -> channel.getVideoUrl();
            case "4", "audio" -> channel.getAudioUrl();
            case "5", "embedding" -> channel.getEmbeddingUrl();
            case "6", "rerank" -> channel.getRerankUrl();
            default -> null; // 未知类型返回 null，由 detectPath() 兜底处理
        };
    }

    private String detectPath(PlatformModelEntity model) {
        // 根据模型类型推断默认 path
        // 算力平台 modelType 字典：1-对话 2-图片生成 3-视频生成 4-语音 5-嵌入 6-重排序
        // 兼容旧字符串格式和新的数字编码
        String modelType = model.getModelType() != null ? model.getModelType().trim() : "";
        return switch (modelType) {
            // 数字编码（新）
            case "1" -> "/v1/chat/completions";
            case "2" -> "/v1/images/generations";
            case "3" -> "/v1/videos/generations";
            case "4" -> "/v1/audio/speech";
            case "5" -> "/v1/embeddings";
            case "6" -> "/v1/rerank";
            // 字符串格式（旧，兼容）
            case "chat" -> "/v1/chat/completions";
            case "embedding" -> "/v1/embeddings";
            case "image" -> "/v1/images/generations";
            case "audio" -> "/v1/audio/speech";
            default -> "/v1/chat/completions";
        };
    }

    private String detectAdapter(PlatformModelEntity model) {
        // 根据厂商或渠道推断 adapter 类型
        String vendor = model.getVendor() != null ? model.getVendor().toLowerCase() : "";
        return switch (vendor) {
            case "openai" -> "normal";
            case "ollama" -> "ollama";
            case "vllm" -> "vllm";
            case "xinference" -> "xinference";
            case "localai" -> "localai";
            default -> "normal";
        };
    }

    private boolean isExpired(PlatformApiKeyEntity key) {
        return key.getExpireTime() != null && key.getExpireTime().isBefore(java.time.LocalDateTime.now());
    }

    /**
     * 判断 API Key 是否为可用状态（正常或体验标志均可直接使用）。
     * 算力平台 ai_api_key.status：1=正常, 0=禁用, 2=体验标志
     */
    private boolean isApiKeyActive(PlatformApiKeyEntity key) {
        if (key == null || key.getStatus() == null) {
            return false;
        }
        int status = key.getStatus();
        return status == PlatformApiKeyEntity.STATUS_NORMAL || status == PlatformApiKeyEntity.STATUS_TRIAL;
    }

    /**
     * 解析渠道 ID。
     * ai_model.channel_id 字段存的是渠道 ID（外键），可能是 String 或 Long 类型。
     */
    private Long parseChannelId(String channelValue) {
        if (channelValue == null || channelValue.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(channelValue.trim());
        } catch (NumberFormatException e) {
            log.warn("渠道 ID 格式错误: '{}'，无法解析为数字", channelValue);
            return null;
        }
    }
}
