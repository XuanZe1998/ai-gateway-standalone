// 文件说明：ModelPricingService：负责计费与余额管理中的组件实现。
package org.unreal.modelrouter.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.platform.sync.PlatformDataSyncService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModelPricingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelPricingService.class);

    private volatile Map<String, ModelPricing> pricingCache = new ConcurrentHashMap<>();

    private final PlatformDataSyncService platformDataSyncService;
    private final ObjectMapper objectMapper;

    public ModelPricingService(@Nullable PlatformDataSyncService platformDataSyncService, ObjectMapper objectMapper) {
        this.platformDataSyncService = platformDataSyncService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void loadPricing() {
        try {
            refreshPricing();
        } catch (Exception e) {
            // 空定价缓存将导致全局（含六维 token 链路）按 0 元落账，必须 ERROR 告警：
            // 常见触发为网关先于平台侧 ai_model 新列发布部署（SELECT 报列不存在）
            LOGGER.error("初始化定价加载失败，将以空定价缓存启动（所有计费链路按 0 元落账），请立即检查平台侧元数据发布状态: {}", e.getMessage());
        }
    }

    public void refreshPricing() {
        Map<String, ModelPricing> newCache = new ConcurrentHashMap<>();

        // 从算力平台 ai_model 表加载定价
        if (platformDataSyncService != null) {
            try {
                List<ModelPricing> pricings = platformDataSyncService.syncAllPricings();
                for (ModelPricing pricing : pricings) {
                    String key = pricingKey(pricing.getModelName(), pricing.getChannelId());
                    newCache.put(key, pricing);
                }
                LOGGER.info("从算力平台同步 {} 个模型定价", newCache.size());
            } catch (Exception e) {
                LOGGER.warn("从算力平台同步定价失败: {}", e.getMessage());
                throw new RuntimeException("从算力平台同步定价失败", e);
            }
        }

        this.pricingCache = newCache;
        LOGGER.info("Model pricing cache refreshed, {} entries loaded", newCache.size());
    }

    /**
     * 从算力平台同步指定模型的定价
     */
    public void syncPricingFromPlatform(String modelName) {
        if (platformDataSyncService == null) {
            return;
        }
        Optional<ModelPricing> pricing = platformDataSyncService.getModelPricing(modelName);
        pricing.ifPresent(p -> {
            String key = pricingKey(modelName, p.getChannelId());
            pricingCache.put(key, p);
            LOGGER.debug("同步模型定价: {} = input:{}, output:{}, discount:{}",
                    key, p.getInputPrice(), p.getOutputPrice(), p.getDiscountRate());
        });
    }

    public ModelPricing getPrice(String modelName, String channelId) {
        String key = pricingKey(modelName, channelId);
        ModelPricing pricing = pricingCache.get(key);
        if (pricing != null) {
            return pricing;
        }
        // fallback: 只按模型名查
        return pricingCache.get(pricingKey(modelName, null));
    }

    /**
     * 判断模型是否配置了可用计费（供调用前校验）。
     * 整体计费需输入/输出价齐全；阶梯计费需至少一档且每档输入/输出价齐全
     * （防止只建挡位不填价格导致 0 元计费资损）。
     */
    public boolean hasUsablePricing(String modelName, String channelId) {
        ModelPricing pricing = getPrice(modelName, channelId);
        if (pricing == null) return false;
        return switch (pricing.getBillingMode()) {
            case 2 -> !pricing.getTiers().isEmpty()
                    && pricing.getTiers().stream()
                        .allMatch(t -> t.inputPrice() != null && t.outputPrice() != null);
            case 3 -> !pricing.getRules().isEmpty(); // 规则模式：至少一条启用规则
            default -> pricing.getInputPrice() != null && pricing.getOutputPrice() != null;
        };
    }


    /**
     * 视频计费规则快照（JSON 字符串）：任务创建时锁定提交时刻的计费规则，
     * 结算时优先用快照计价——平台侧后续改价/删规则不影响已提交任务的账单生成。
     * 快照仅含视频计费相关字段（priceMode/billingUnit/modelId/启用规则行），
     * price 为已转换单价（second=元/秒、token=元/token）；
     * 定价未命中、非视频模型（priceMode 为 null）或无可锁定的启用规则行
     * （规则列表为空）返回 null，结算回退实时定价。
     */
    public String snapshotVideoPricing(String modelName, String channelId) {
        ModelPricing pricing = getPrice(modelName, channelId);
        if (pricing == null || pricing.getPriceMode() == null
                || pricing.getVideoPriceRules() == null || pricing.getVideoPriceRules().isEmpty()) {
            // 无可锁定的启用规则行：快照置空，结算回退实时定价。规则行由同步层过滤
            // 停用/逻辑删除/价格非正数行，模型已配 priceMode 但规则行未录入或全被过滤时
            // 列表为空——此时若锁死空快照，结算将永久拒计费（不回退实时），平台后续
            // 补录规则也无法补救，形成漏计费；置空则恢复 V7 前「无规则时回退实时」口径
            return null;
        }
        try {
            return objectMapper.writeValueAsString(new VideoPricingSnapshot(
                    pricing.getPriceMode(), pricing.getBillingUnit(),
                    pricing.getModelId(), pricing.getVideoPriceRules()));
        } catch (Exception e) {
            LOGGER.warn("视频计费规则快照序列化失败，快照置空（结算回退实时定价）: model={}: {}",
                    modelName, e.getMessage());
            return null;
        }
    }

    /** 视频计费规则快照反序列化：失败返回 null（结算回退实时定价），不抛异常 */
    public VideoPricingSnapshot parseVideoPricingSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, VideoPricingSnapshot.class);
        } catch (Exception e) {
            LOGGER.warn("视频计费规则快照解析失败，回退实时定价: {}", e.getMessage());
            return null;
        }
    }

    private String pricingKey(String modelName, String channelId) {
        if (channelId != null && !channelId.isEmpty()) {
            return modelName + ":" + channelId;
        }
        return modelName;
    }

    public static class ModelPricing {
        private final String modelName;
        private final String channelId;
        private final Long modelId;
        private final String vendor;
        private final Integer billingMode;        // 1=整体, 2=阶梯；null 默认 1
        // 5 个价格（元/token，已从 元/M token 转换）
        private final BigDecimal inputPrice;
        private final BigDecimal cacheHitInputPrice;
        private final BigDecimal outputPrice;
        private final BigDecimal cacheCreateInputPrice;
        private final BigDecimal cacheHitExplicitInputPrice;
        // 5 个 enable 标志位
        private final boolean enableInputToken;
        private final boolean enableCacheHitInput;
        private final boolean enableOutputToken;
        private final boolean enableCacheCreateInput;
        private final boolean enableCacheHitExplicitInput;
        // 思考 token
        private final Integer thinkingBillingMode; // 1=计入输出, 2=单独计费, 3=不计费；null 默认 1
        private final BigDecimal thinkingPrice;    // 元/token
        // 折扣
        private final BigDecimal discountRate;     // 小数（0.8=8折）
        // 阶梯档位（billingMode=2 时非空）
        private final List<PriceTier> tiers;
        // 规则计费数据（billingMode=3 时非空，已按 priority 升序）
        private final List<PricingRuleData> rules;
        // ===== 视频模型计费元数据（modelType=3 专用，非视频模型为 null/空）=====
        private final Integer priceMode;        // 1=统一价格, 2=按条件定价；null 默认 1
        private final String billingUnit;       // second=元/秒, token=元/M token；null 默认 second
        private final List<VideoPriceRule> videoPriceRules; // 启用行的分辨率价格规则（无则空列表）

        /** 阶梯档位（lowerLimit/upperLimit 已是 token 数，K 值在同步层 ×1000 转换完成） */
        public record PriceTier(BigDecimal lowerLimit, BigDecimal upperLimit, boolean unlimited,
                                BigDecimal inputPrice, BigDecimal cacheHitInputPrice, BigDecimal outputPrice,
                                BigDecimal cacheCreateInputPrice, BigDecimal cacheHitExplicitInputPrice,
                                BigDecimal thinkingPrice) {
            /** 兼容旧调用方：无 thinkingPrice 时补 ZERO（null 亦归一为 ZERO） */
            public PriceTier(BigDecimal lowerLimit, BigDecimal upperLimit, boolean unlimited,
                             BigDecimal inputPrice, BigDecimal cacheHitInputPrice, BigDecimal outputPrice,
                             BigDecimal cacheCreateInputPrice, BigDecimal cacheHitExplicitInputPrice) {
                this(lowerLimit, upperLimit, unlimited, inputPrice, cacheHitInputPrice, outputPrice,
                        cacheCreateInputPrice, cacheHitExplicitInputPrice, BigDecimal.ZERO);
            }
            public PriceTier {
                thinkingPrice = thinkingPrice != null ? thinkingPrice : BigDecimal.ZERO;
            }
        }

        /** 规则计费行（billingMode=3）：match_json 条件树 + price_json 动态维度价格 */
        public record PricingRuleData(Long ruleId, String ruleName, int priority,
                                      String matchJson, String priceJson) {}

        /**
         * 视频模型分辨率价格规则行（仅启用行）。
         *
         * @param outputResolution 输出分辨率，已归一化为大写（480P/720P/1080P/4K）
         * @param hasVideoInput    是否有视频输入（统一价格模式为 null，不参与匹配）
         * @param price            单价：second 单位=元/秒；token 单位=元/token（同步层已 ÷1e6 转换）
         */
        public record VideoPriceRule(String outputResolution, Boolean hasVideoInput, BigDecimal price) {
            public VideoPriceRule {
                price = price != null ? price : BigDecimal.ZERO;
            }
        }

        /** 旧版兼容构造器（仅 input/output，其余默认），保留以防其他调用方 */
        public ModelPricing(String modelName, String channelId,
                            BigDecimal inputPrice, BigDecimal outputPrice) {
            this(modelName, channelId, inputPrice, outputPrice, BigDecimal.ONE, null);
        }

        public ModelPricing(String modelName, String channelId,
                            BigDecimal inputPrice, BigDecimal outputPrice,
                            BigDecimal discountRate) {
            this(modelName, channelId, inputPrice, outputPrice, discountRate, null);
        }

        public ModelPricing(String modelName, String channelId,
                            BigDecimal inputPrice, BigDecimal outputPrice,
                            BigDecimal discountRate, Long modelId) {
            this.modelName = modelName;
            this.channelId = channelId;
            this.inputPrice = inputPrice;
            this.outputPrice = outputPrice;
            this.discountRate = discountRate != null ? discountRate : BigDecimal.ONE;
            this.modelId = modelId;
            // 新字段默认值：旧模型按整体计费 + 思考并入输出 + 全 enable（向后兼容）
            this.vendor = null;
            this.billingMode = 1;
            this.cacheHitInputPrice = BigDecimal.ZERO;
            this.cacheCreateInputPrice = BigDecimal.ZERO;
            this.cacheHitExplicitInputPrice = BigDecimal.ZERO;
            this.enableInputToken = true;
            this.enableCacheHitInput = true;
            this.enableOutputToken = true;
            this.enableCacheCreateInput = false;
            this.enableCacheHitExplicitInput = false;
            this.thinkingBillingMode = 1;
            this.thinkingPrice = BigDecimal.ZERO;
            this.tiers = List.of();
            this.rules = List.of();
            // 视频计费元数据默认值：非视频模型（priceMode 为 null 时计费侧不走视频分支）
            this.priceMode = null;
            this.billingUnit = null;
            this.videoPriceRules = List.of();
        }

        /** 全参构造器（P3 同步层使用） */
        public ModelPricing(String modelName, String channelId, Long modelId, String vendor,
                            Integer billingMode,
                            BigDecimal inputPrice, BigDecimal cacheHitInputPrice, BigDecimal outputPrice,
                            BigDecimal cacheCreateInputPrice, BigDecimal cacheHitExplicitInputPrice,
                            boolean enableInputToken, boolean enableCacheHitInput, boolean enableOutputToken,
                            boolean enableCacheCreateInput, boolean enableCacheHitExplicitInput,
                            Integer thinkingBillingMode, BigDecimal thinkingPrice,
                            BigDecimal discountRate, List<PriceTier> tiers) {
            this(modelName, channelId, modelId, vendor, billingMode,
                    inputPrice, cacheHitInputPrice, outputPrice,
                    cacheCreateInputPrice, cacheHitExplicitInputPrice,
                    enableInputToken, enableCacheHitInput, enableOutputToken,
                    enableCacheCreateInput, enableCacheHitExplicitInput,
                    thinkingBillingMode, thinkingPrice, discountRate, tiers,
                    null, null, List.of());
        }

        /** 全参构造器（含视频模型计费元数据，视频定价同步使用） */
        public ModelPricing(String modelName, String channelId, Long modelId, String vendor,
                            Integer billingMode,
                            BigDecimal inputPrice, BigDecimal cacheHitInputPrice, BigDecimal outputPrice,
                            BigDecimal cacheCreateInputPrice, BigDecimal cacheHitExplicitInputPrice,
                            boolean enableInputToken, boolean enableCacheHitInput, boolean enableOutputToken,
                            boolean enableCacheCreateInput, boolean enableCacheHitExplicitInput,
                            Integer thinkingBillingMode, BigDecimal thinkingPrice,
                            BigDecimal discountRate, List<PriceTier> tiers,
                            Integer priceMode, String billingUnit, List<VideoPriceRule> videoPriceRules) {
            this.modelName = modelName;
            this.channelId = channelId;
            this.modelId = modelId;
            this.vendor = vendor;
            this.billingMode = billingMode != null ? billingMode : 1;
            this.inputPrice = inputPrice != null ? inputPrice : BigDecimal.ZERO;
            this.cacheHitInputPrice = cacheHitInputPrice != null ? cacheHitInputPrice : BigDecimal.ZERO;
            this.outputPrice = outputPrice != null ? outputPrice : BigDecimal.ZERO;
            this.cacheCreateInputPrice = cacheCreateInputPrice != null ? cacheCreateInputPrice : BigDecimal.ZERO;
            this.cacheHitExplicitInputPrice = cacheHitExplicitInputPrice != null ? cacheHitExplicitInputPrice : BigDecimal.ZERO;
            this.enableInputToken = enableInputToken;
            this.enableCacheHitInput = enableCacheHitInput;
            this.enableOutputToken = enableOutputToken;
            this.enableCacheCreateInput = enableCacheCreateInput;
            this.enableCacheHitExplicitInput = enableCacheHitExplicitInput;
            this.thinkingBillingMode = thinkingBillingMode != null ? thinkingBillingMode : 1;
            this.thinkingPrice = thinkingPrice != null ? thinkingPrice : BigDecimal.ZERO;
            this.discountRate = discountRate != null ? discountRate : BigDecimal.ONE;
            this.tiers = tiers != null ? tiers : List.of();
            this.priceMode = priceMode;
            this.billingUnit = billingUnit;
            this.videoPriceRules = videoPriceRules != null ? videoPriceRules : List.of();
            this.rules = List.of();
        }

        /** 全参构造器（含视频计费元数据 + 规则化计费，规则引擎同步使用） */
        public ModelPricing(String modelName, String channelId, Long modelId, String vendor,
                            Integer billingMode,
                            BigDecimal inputPrice, BigDecimal cacheHitInputPrice, BigDecimal outputPrice,
                            BigDecimal cacheCreateInputPrice, BigDecimal cacheHitExplicitInputPrice,
                            boolean enableInputToken, boolean enableCacheHitInput, boolean enableOutputToken,
                            boolean enableCacheCreateInput, boolean enableCacheHitExplicitInput,
                            Integer thinkingBillingMode, BigDecimal thinkingPrice,
                            BigDecimal discountRate, List<PriceTier> tiers,
                            Integer priceMode, String billingUnit, List<VideoPriceRule> videoPriceRules,
                            List<PricingRuleData> rules) {
            this.modelName = modelName;
            this.channelId = channelId;
            this.modelId = modelId;
            this.vendor = vendor;
            this.billingMode = billingMode != null ? billingMode : 1;
            this.inputPrice = inputPrice != null ? inputPrice : BigDecimal.ZERO;
            this.cacheHitInputPrice = cacheHitInputPrice != null ? cacheHitInputPrice : BigDecimal.ZERO;
            this.outputPrice = outputPrice != null ? outputPrice : BigDecimal.ZERO;
            this.cacheCreateInputPrice = cacheCreateInputPrice != null ? cacheCreateInputPrice : BigDecimal.ZERO;
            this.cacheHitExplicitInputPrice = cacheHitExplicitInputPrice != null ? cacheHitExplicitInputPrice : BigDecimal.ZERO;
            this.enableInputToken = enableInputToken;
            this.enableCacheHitInput = enableCacheHitInput;
            this.enableOutputToken = enableOutputToken;
            this.enableCacheCreateInput = enableCacheCreateInput;
            this.enableCacheHitExplicitInput = enableCacheHitExplicitInput;
            this.thinkingBillingMode = thinkingBillingMode != null ? thinkingBillingMode : 1;
            this.thinkingPrice = thinkingPrice != null ? thinkingPrice : BigDecimal.ZERO;
            this.discountRate = discountRate != null ? discountRate : BigDecimal.ONE;
            this.tiers = tiers != null ? List.copyOf(tiers) : List.of();
            this.priceMode = priceMode;
            this.billingUnit = billingUnit;
            this.videoPriceRules = videoPriceRules != null ? List.copyOf(videoPriceRules) : List.of();
            this.rules = rules != null ? List.copyOf(rules) : List.of();
        }

        public String getModelName() { return modelName; }
        public String getChannelId() { return channelId; }
        public Long getModelId() { return modelId; }
        public String getVendor() { return vendor; }
        public Integer getBillingMode() { return billingMode; }
        public BigDecimal getInputPrice() { return inputPrice; }
        public BigDecimal getCacheHitInputPrice() { return cacheHitInputPrice; }
        public BigDecimal getOutputPrice() { return outputPrice; }
        public BigDecimal getCacheCreateInputPrice() { return cacheCreateInputPrice; }
        public BigDecimal getCacheHitExplicitInputPrice() { return cacheHitExplicitInputPrice; }
        public boolean isEnableInputToken() { return enableInputToken; }
        public boolean isEnableCacheHitInput() { return enableCacheHitInput; }
        public boolean isEnableOutputToken() { return enableOutputToken; }
        public boolean isEnableCacheCreateInput() { return enableCacheCreateInput; }
        public boolean isEnableCacheHitExplicitInput() { return enableCacheHitExplicitInput; }
        public Integer getThinkingBillingMode() { return thinkingBillingMode; }
        public BigDecimal getThinkingPrice() { return thinkingPrice; }
        public BigDecimal getDiscountRate() { return discountRate; }
        public List<PriceTier> getTiers() { return tiers; }
        public Integer getPriceMode() { return priceMode; }
        public String getBillingUnit() { return billingUnit; }
        public List<VideoPriceRule> getVideoPriceRules() { return videoPriceRules; }
        public List<PricingRuleData> getRules() { return rules; }
    }

    /**
     * 视频计费规则快照（任务创建时从定价缓存快照，结算时优先使用）：
     * 锁提交时刻的计费规则，平台侧后续规则变更不影响已提交任务的账单生成。
     * priceMode 为 null 时视为统一价格（1）；billingUnit 为空时按 second 计费；
     * rules 为创建时启用的规则行（已过滤停用/删除/价格非正数行），price 为已转换单价。
     */
    public record VideoPricingSnapshot(Integer priceMode, String billingUnit, Long modelId,
                                       List<ModelPricing.VideoPriceRule> rules) {
        public VideoPricingSnapshot {
            rules = rules != null ? rules : List.of();
        }
    }
}
