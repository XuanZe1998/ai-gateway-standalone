package org.unreal.modelrouter.billing.pricing;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.billing.ModelPricingService;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformModelEntity;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformModelPriceTierEntity;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformModelPriceTierRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformModelRepository;
import org.unreal.modelrouter.persistence.jpa.repository.PricingRuleRepository;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.unreal.modelrouter.billing.pricing.PricingAdminDtos.*;

/**
 * 模型定价管理服务（仅 standalone 部署使用）。
 *
 * <p>直接读写 ai_model / ai_model_price_tier 表：standalone 下无平台侧写入，
 * 本服务是唯一写入方。保存后立即刷新 {@link ModelPricingService} 缓存，
 * 模型广场展示与计费链路即时生效。
 *
 * <p>整体/阶梯软互斥：billingMode 是唯一开关；整体模式保存只写主价字段（挡位与档价不动），
 * 阶梯模式保存只写各档价格（主价字段不动），切换不清空另一模式的数据，切回来仍在。
 *
 * <p>阶梯档位弹窗只保存区间；价格在编辑定价弹窗的阶梯矩阵中按档维护，
 * 挡位区间整体替换时价格按序继承（超出丢弃、新增留空）。
 *
 * <p>约定：ai_model.id 由本地分配（表无自增）；status='1' + deleted=false 才会被
 * 定价缓存与模型广场加载；视频模型（vidGen）一期不支持（走 ai_model_video_price 子表）。
 */
@Service
@RequiredArgsConstructor
public class PricingAdminService {

    private final ModelServiceRegistry registry;
    private final PlatformModelRepository modelRepository;
    private final PlatformModelPriceTierRepository tierRepository;
    private final ModelPricingService pricingService;
    private final BillingDimensionAliasService aliasService;
    private final PricingRuleRepository ruleRepository;

    /** 列表：所有运行中（active、非视频）模型实例 + 定价配置状态 */
    public List<PricingRow> list() {
        Map<String, PlatformModelEntity> byName = new HashMap<>();
        modelRepository.findAll().stream()
                .filter(m -> !Boolean.TRUE.equals(m.getDeleted()))
                .forEach(m -> {
                    if (m.getRealName() != null && !m.getRealName().isBlank()) {
                        byName.putIfAbsent(m.getRealName().trim().toLowerCase(), m);
                    }
                });
        Map<Long, List<PlatformModelPriceTierEntity>> tiersByModel = tierRepository.findAll().stream()
                .filter(t -> t.getModelId() != null)
                .collect(Collectors.groupingBy(PlatformModelPriceTierEntity::getModelId));

        List<PricingRow> rows = new ArrayList<>();
        registry.getAllInstances().forEach((type, instances) -> {
            if (type == ModelServiceRegistry.ServiceType.vidGen) return; // 一期不支持视频定价
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (var instance : instances) {
                if (instance.getName() == null || instance.getName().isBlank()) continue;
                if (!"active".equalsIgnoreCase(instance.getStatus())) continue;
                names.add(instance.getName());
            }
            for (String name : names) {
                PlatformModelEntity m = byName.get(name.toLowerCase());
                List<PlatformModelPriceTierEntity> tiers = m == null || m.getId() == null
                        ? List.of()
                        : tiersByModel.getOrDefault(m.getId(), List.of());
                rows.add(new PricingRow(type.name(), name,
                        m == null ? null : m.getId(),
                        m == null ? null : m.getVendor(),
                        m == null ? null : m.getBillingMode(),
                        m == null ? null : m.getInputPrice(),
                        m == null ? null : m.getCacheHitInputPrice(),
                        m == null ? null : m.getOutputPrice(),
                        m == null ? null : m.getThinkingPrice(),
                        tiers.size(), usable(m, tiers)));
            }
        });
        rows.sort(Comparator.comparing(PricingRow::serviceType)
                .thenComparing(PricingRow::modelId, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** 详情：主定价回显 + 阶梯档位（含价格供矩阵回显） + 当前计费组维度别名草稿 */
    public PricingDetail detail(String serviceType, String modelId) {
        requireEditable(serviceType, modelId);
        PlatformModelEntity m = findByName(modelId);
        PricingEditable editable = m == null ? defaultEditable(serviceType, modelId) : editableOf(m, serviceType);
        List<TierRow> tiers = m == null || m.getId() == null
                ? List.of()
                : tierRepository.findByModelIdOrderByTierOrderAsc(m.getId()).stream().map(this::tierRowOf).toList();
        return new PricingDetail(editable, tiers, aliasService.aliasesOfServiceType(serviceType));
    }

    /** 保存主定价：整体模式写主价，阶梯模式写档价（软互斥互不清空）；别名整组同事务提交 */
    @Transactional
    public PricingRow save(PricingSave req) {
        requireEditable(req.serviceType(), req.modelId());
        validateSave(req);
        PlatformModelEntity m = findByName(req.modelId());
        if (m == null) {
            m = new PlatformModelEntity();
            m.setId(modelRepository.findMaxId() + 1);
            m.setRealName(req.modelId().trim());
            m.setStatus("1");
            m.setDeleted(false);
            m.setCreateTime(LocalDateTime.now());
        }
        if (m.getModelType() == null || m.getModelType().isBlank()) m.setModelType(modelTypeOf(req.serviceType()));
        if (!"1".equals(m.getStatus())) m.setStatus("1");
        m.setVendor(optional(req.vendor()));
        Integer billingMode = req.billingMode() == null ? 1 : req.billingMode();
        m.setBillingMode(billingMode);

        if (Integer.valueOf(2).equals(billingMode)) {
            // 阶梯模式：主价字段不动（保留整体配置），写各档价格
            applyTierPrices(m, req.tierPrices());
        } else if (!Integer.valueOf(3).equals(billingMode)) {
            // 整体模式：写主价字段（含思考单价），挡位与档价不动
            // 规则模式（3）：不写主价、不写档价——规则数据由独立的规则管理 API 维护
            m.setInputPrice(req.inputPrice());
            m.setCacheHitInputPrice(req.cacheHitInputPrice());
            m.setOutputPrice(req.outputPrice());
            m.setCacheCreateInputPrice(req.cacheCreateInputPrice());
            m.setCacheHitExplicitInputPrice(req.cacheHitExplicitInputPrice());
            m.setThinkingPrice(req.thinkingPrice());
        }
        m.setEnableInputToken(req.enableInputToken());
        m.setEnableCacheHitInput(req.enableCacheHitInput());
        m.setEnableOutputToken(req.enableOutputToken());
        m.setEnableCacheCreateInput(req.enableCacheCreateInput());
        m.setEnableCacheHitExplicitInput(req.enableCacheHitExplicitInput());
        m.setThinkingBillingMode(req.thinkingBillingMode() == null ? 1 : req.thinkingBillingMode());
        m.setDiscount(req.discount());
        m.setUpdateTime(LocalDateTime.now());
        modelRepository.saveAndFlush(m);

        // 维度显示别名：整组草稿提交（null/空 = 恢复默认名），与定价保存同事务
        if (req.dimensionAliases() != null) {
            aliasService.replaceAliases(aliasService.groupKeyOf(req.serviceType()), req.dimensionAliases());
        }

        pricingService.refreshPricing(); // 立即生效，无需重启

        List<PlatformModelPriceTierEntity> tiers = m.getId() == null
                ? List.of()
                : tierRepository.findByModelIdOrderByTierOrderAsc(m.getId());
        return new PricingRow(req.serviceType(), req.modelId(), m.getId(), m.getVendor(), m.getBillingMode(),
                m.getInputPrice(), m.getCacheHitInputPrice(), m.getOutputPrice(), m.getThinkingPrice(),
                tiers.size(), usable(m, tiers));
    }

    /** 保存阶梯档位区间（整体替换；价格按序继承：超出丢弃、新增留空；不切换计费模式） */
    @Transactional
    public PricingRow saveTiers(TiersSave req) {
        requireEditable(req.serviceType(), req.modelId());
        PlatformModelEntity m = findByName(req.modelId());
        if (m == null) {
            // 档位可以先于主定价设置：自动创建空壳 ai_model 行，仅挂靠档位，价格留空后续补填
            m = new PlatformModelEntity();
            m.setId(modelRepository.findMaxId() + 1);
            m.setRealName(req.modelId().trim());
            m.setStatus("1");
            m.setDeleted(false);
            m.setCreateTime(LocalDateTime.now());
            if (m.getModelType() == null || m.getModelType().isBlank()) m.setModelType(modelTypeOf(req.serviceType()));
            modelRepository.saveAndFlush(m);
        }
        if (req.tiers() == null || req.tiers().isEmpty()) throw bad("阶梯计费至少需要一个档位");
        for (int i = 0; i < req.tiers().size(); i++) {
            TierRangeRow t = req.tiers().get(i);
            if (!t.unlimited() && (t.upperLimitK() == null || t.upperLimitK().signum() <= 0))
                throw bad("第 " + (i + 1) + " 档需要有效上限（K）或勾选不限量");
            if (t.lowerLimitK() != null && t.lowerLimitK().signum() < 0)
                throw bad("第 " + (i + 1) + " 档下限不能为负数");
            if (!t.unlimited() && t.lowerLimitK() != null && t.upperLimitK() != null
                    && t.upperLimitK().compareTo(t.lowerLimitK()) <= 0)
                throw bad("第 " + (i + 1) + " 档上限必须大于下限");
        }
        // 价格按序继承（旧档位第 i 档 → 新档位第 i 档；超出丢弃、新增留空）
        List<PlatformModelPriceTierEntity> oldTiers = tierRepository.findByModelIdOrderByTierOrderAsc(m.getId());
        m.setUpdateTime(LocalDateTime.now());
        modelRepository.saveAndFlush(m);
        tierRepository.deleteByModelId(m.getId());

        long nextId = tierRepository.findMaxId() + 1;
        List<PlatformModelPriceTierEntity> entities = new ArrayList<>();
        for (int i = 0; i < req.tiers().size(); i++) {
            TierRangeRow t = req.tiers().get(i);
            PlatformModelPriceTierEntity e = new PlatformModelPriceTierEntity();
            e.setId(nextId + i);
            e.setModelId(m.getId());
            e.setTierOrder(i + 1);
            e.setTierLowerLimit(t.lowerLimitK());
            e.setTierUpperLimit(t.unlimited() ? null : t.upperLimitK());
            e.setIsUnlimited(t.unlimited());
            if (i < oldTiers.size()) { // 按序继承价格
                PlatformModelPriceTierEntity o = oldTiers.get(i);
                e.setInputPrice(o.getInputPrice());
                e.setCacheHitInputPrice(o.getCacheHitInputPrice());
                e.setOutputPrice(o.getOutputPrice());
                e.setCacheCreateInputPrice(o.getCacheCreateInputPrice());
                e.setCacheHitExplicitInputPrice(o.getCacheHitExplicitInputPrice());
                e.setThinkingPrice(o.getThinkingPrice());
            }
            entities.add(e);
        }
        tierRepository.saveAll(entities);
        pricingService.refreshPricing();

        return new PricingRow(req.serviceType(), req.modelId(), m.getId(), m.getVendor(), m.getBillingMode(),
                m.getInputPrice(), m.getCacheHitInputPrice(), m.getOutputPrice(), m.getThinkingPrice(),
                entities.size(), usable(m, entities));
    }

    /** 清除定价（软删除：deleted=true + status='2'，保留行避免破坏平台表结构；档位一并物理删除） */
    @Transactional
    public boolean delete(String serviceType, String modelId) {
        PlatformModelEntity m = findByName(modelId);
        if (m == null) return false;
        if (m.getId() != null) {
            tierRepository.deleteByModelId(m.getId());
            ruleRepository.deleteByModelId(m.getId()); // 规则计费数据一并清除
        }
        m.setDeleted(true);
        m.setStatus("2");
        m.setUpdateTime(LocalDateTime.now());
        modelRepository.saveAndFlush(m);
        pricingService.refreshPricing();
        return true;
    }

    // ===== 内部工具 =====

    /**
     * 阶梯模式：按 tierOrder 对位写各档价格。
     * 价格条数超过挡位数时多余忽略（以挡位为准）；不足时多余挡位价格不动。
     */
    private void applyTierPrices(PlatformModelEntity m, List<TierPriceRow> prices) {
        if (prices == null || prices.isEmpty() || m.getId() == null) return;
        List<PlatformModelPriceTierEntity> tiers = tierRepository.findByModelIdOrderByTierOrderAsc(m.getId());
        int n = Math.min(tiers.size(), prices.size());
        for (int i = 0; i < n; i++) {
            TierPriceRow p = prices.get(i);
            if (negative(p.inputPrice()) || negative(p.cacheHitInputPrice()) || negative(p.outputPrice())
                    || negative(p.cacheCreateInputPrice()) || negative(p.cacheHitExplicitInputPrice())
                    || negative(p.thinkingPrice())) {
                throw bad("第 " + (i + 1) + " 档价格不能为负数");
            }
            PlatformModelPriceTierEntity t = tiers.get(i);
            t.setInputPrice(p.inputPrice());
            t.setCacheHitInputPrice(p.cacheHitInputPrice());
            t.setOutputPrice(p.outputPrice());
            t.setCacheCreateInputPrice(p.cacheCreateInputPrice());
            t.setCacheHitExplicitInputPrice(p.cacheHitExplicitInputPrice());
            t.setThinkingPrice(p.thinkingPrice());
        }
        if (n > 0) tierRepository.saveAll(tiers.subList(0, n));
    }

    /** 校验待编辑模型：服务类型合法、非视频、正在运行 */
    private void requireEditable(String serviceType, String modelId) {
        if (serviceType == null || modelId == null || modelId.isBlank())
            throw bad("服务类型与模型标识必填");
        if (modelId.length() > 255) throw bad("模型标识过长");
        ModelServiceRegistry.ServiceType type;
        try {
            type = ModelServiceRegistry.ServiceType.valueOf(serviceType);
        } catch (IllegalArgumentException e) {
            throw bad("无效的服务类型: " + serviceType);
        }
        if (type == ModelServiceRegistry.ServiceType.vidGen)
            throw bad("第一版暂不支持视频模型定价");
        boolean running = registry.getAllInstances()
                .getOrDefault(type, List.of())
                .stream()
                .anyMatch(i -> modelId.equals(i.getName()) && "active".equalsIgnoreCase(i.getStatus()));
        if (!running) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模型不存在或未运行: " + modelId);
    }

    private void validateSave(PricingSave req) {
        int billingMode = req.billingMode() == null ? 1 : req.billingMode();
        if (billingMode != 1 && billingMode != 2 && billingMode != 3) throw bad("计费模式无效");
        if (billingMode == 1 && (req.inputPrice() == null || req.outputPrice() == null))
            throw bad("整体计费模式下输入与输出单价必填");
        if (req.inputPrice() != null && req.inputPrice().signum() < 0) throw bad("输入单价不能为负数");
        if (req.outputPrice() != null && req.outputPrice().signum() < 0) throw bad("输出单价不能为负数");
        if (req.discount() != null && (req.discount() < 0 || req.discount() > 100))
            throw bad("折扣范围为 0-100");
        if (req.thinkingBillingMode() != null
                && (req.thinkingBillingMode() < 1 || req.thinkingBillingMode() > 3))
            throw bad("思考计费模式无效");
    }

    /**
     * 定价可用性（与展示口径一致）：
     * 整体计费需输入/输出主价齐备；阶梯计费需至少一档且每档输入/输出价齐备。
     */
    private boolean usable(PlatformModelEntity m, List<PlatformModelPriceTierEntity> tiers) {
        if (m == null) return false;
        if (Integer.valueOf(3).equals(m.getBillingMode())) {
            // 规则计费：至少一条启用规则（优先按优先级取第一条）
            return m.getId() != null && ruleRepository.countByModelId(m.getId()) > 0;
        }
        if (Integer.valueOf(2).equals(m.getBillingMode())) {
            if (tiers.isEmpty()) return false;
            return tiers.stream().allMatch(t -> t.getInputPrice() != null && t.getOutputPrice() != null);
        }
        return m.getInputPrice() != null && m.getOutputPrice() != null;
    }

    private PlatformModelEntity findByName(String modelId) {
        if (modelId == null) return null;
        return modelRepository.findByRealNameAndDeletedFalse(modelId.trim()).orElse(null);
    }

    private PricingEditable editableOf(PlatformModelEntity m, String serviceType) {
        return new PricingEditable(serviceType, m.getRealName(), m.getVendor(), m.getBillingMode(),
                m.getInputPrice(), m.getCacheHitInputPrice(), m.getOutputPrice(),
                m.getCacheCreateInputPrice(), m.getCacheHitExplicitInputPrice(),
                m.getEnableInputToken(), m.getEnableCacheHitInput(), m.getEnableOutputToken(),
                m.getEnableCacheCreateInput(), m.getEnableCacheHitExplicitInput(),
                m.getThinkingBillingMode(), m.getThinkingPrice(), m.getDiscount());
    }

    private PricingEditable defaultEditable(String serviceType, String modelId) {
        return new PricingEditable(serviceType, modelId, null, 1,
                null, null, null, null, null,
                true, true, true, false, false, 1, null, null);
    }

    private TierRow tierRowOf(PlatformModelPriceTierEntity t) {
        return new TierRow(t.getId(), t.getTierOrder(), t.getTierLowerLimit(), t.getTierUpperLimit(),
                Boolean.TRUE.equals(t.getIsUnlimited()), t.getInputPrice(), t.getCacheHitInputPrice(),
                t.getOutputPrice(), t.getCacheCreateInputPrice(), t.getCacheHitExplicitInputPrice(),
                t.getThinkingPrice());
    }

    private String modelTypeOf(String serviceType) {
        return switch (serviceType) {
            case "imgGen" -> "2"; case "vidGen" -> "3"; case "tts" -> "4";
            case "embedding" -> "5"; case "rerank" -> "6"; case "stt" -> "stt";
            case "imgEdit" -> "imgEdit"; default -> "1";
        };
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean negative(BigDecimal v) {
        return v != null && v.signum() < 0;
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
