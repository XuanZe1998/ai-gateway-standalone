package org.unreal.modelrouter.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.persistence.jpa.entity.BillingDimensionEntity;
import org.unreal.modelrouter.persistence.jpa.entity.PricingRuleEntity;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformModelEntity;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformModelPriceTierEntity;
import org.unreal.modelrouter.persistence.jpa.repository.BillingDimensionRepository;
import org.unreal.modelrouter.persistence.jpa.repository.PricingRuleRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformModelPriceTierRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformModelRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 定价规则引擎数据迁移（启动时自动执行，幂等）。
 *
 * <p>职责：
 * <ol>
 *   <li>初始化默认计费维度（text/voice/image 三组，六维 + 折扣）</li>
 *   <li>把整体计费（billingMode=1）迁移为 1 条无条件兜底规则</li>
 *   <li>把阶梯计费（billingMode=2）迁移为 N 条 tokenCount 区间规则</li>
 * </ol>
 *
 * <p>迁移只预填规则数据，不改 billing_mode：老模型仍走老逻辑，管理员切换为
 * billingMode=3（规则计费）后新引擎才生效。老表数据保留不删。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(2)
public class PricingRuleMigrationService implements ApplicationRunner {

    private final PlatformModelRepository modelRepository;
    private final PlatformModelPriceTierRepository tierRepository;
    private final PricingRuleRepository ruleRepository;
    private final BillingDimensionRepository dimensionRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            initDefaultDimensions();
            migrateOverallPricing();
            migrateTieredPricing();
        } catch (Exception e) {
            log.error("定价规则迁移失败（不影响启动，后续可手动补迁）: {}", e.getMessage(), e);
        }
    }

    // ===== 1. 默认维度 =====

    private void initDefaultDimensions() {
        if (dimensionRepository.count() > 0) {
            return;
        }
        // dimension_key, display_name, unit, value_type, sort_order
        String[][] defaults = {
                {"normalPrice", "普通输入（未命中缓存）", "元 / 百万 Token", "price", "1"},
                {"output", "普通输出", "元 / 百万 Token", "price", "2"},
                {"cacheHit", "缓存命中输入", "元 / 百万 Token", "price", "3"},
                {"cacheCreate", "显式缓存创建", "元 / 百万 Token", "price", "4"},
                {"cacheHitExplicit", "显式缓存命中", "元 / 百万 Token", "price", "5"},
                {"thinking", "思考Token", "元 / 百万 Token", "price", "6"},
                {"discount", "折扣", "%", "discount", "99"}
        };
        LocalDateTime now = LocalDateTime.now();
        for (String group : List.of("text", "voice", "image")) {
            for (String[] row : defaults) {
                BillingDimensionEntity e = new BillingDimensionEntity();
                e.setGroupKey(group);
                e.setDimensionKey(row[0]);
                e.setDisplayName(row[1]);
                e.setUnit(row[2]);
                e.setValueType(row[3]);
                e.setSortOrder(Integer.parseInt(row[4]));
                e.setEnabled(true);
                e.setCreatedAt(now);
                e.setUpdatedAt(now);
                dimensionRepository.save(e);
            }
        }
        log.info("已初始化默认计费维度: {} 组 × {} 维度", 3, defaults.length);
    }

    // ===== 2. 整体计费迁移 =====

    private void migrateOverallPricing() {
        if (ruleRepository.count() > 0) {
            return; // 已迁移过（或已存在手工规则），跳过
        }
        List<PlatformModelEntity> models = modelRepository.findAll().stream()
                .filter(m -> !Boolean.TRUE.equals(m.getDeleted()))
                .filter(m -> !isVideoModel(m))
                .filter(m -> !Integer.valueOf(2).equals(m.getBillingMode()))
                .filter(m -> !Integer.valueOf(3).equals(m.getBillingMode()))
                .filter(m -> m.getId() != null)
                .toList();
        int count = 0;
        for (PlatformModelEntity m : models) {
            ObjectNode match = objectMapper.createObjectNode();
            match.put("operator", "AND");
            match.set("conditions", objectMapper.createArrayNode());
            saveRule(m.getId(), "整体兜底", match.toString(), buildPriceJson(
                    m.getInputPrice(), m.getCacheHitInputPrice(), m.getOutputPrice(),
                    m.getCacheCreateInputPrice(), m.getCacheHitExplicitInputPrice(), m.getThinkingPrice(),
                    m.getDiscount()), 100);
            count++;
        }
        if (count > 0) log.info("已迁移 {} 个整体计费模型为规则数据（billingMode 保持 1，不生效）", count);
    }

    // ===== 3. 阶梯计费迁移 =====

    private void migrateTieredPricing() {
        if (ruleRepository.count() > 0) {
            return;
        }
        List<PlatformModelEntity> models = modelRepository.findAll().stream()
                .filter(m -> !Boolean.TRUE.equals(m.getDeleted()))
                .filter(m -> !isVideoModel(m))
                .filter(m -> Integer.valueOf(2).equals(m.getBillingMode()))
                .filter(m -> m.getId() != null)
                .toList();
        long migratedModels = 0;
        for (PlatformModelEntity m : models) {
            List<PlatformModelPriceTierEntity> tiers = tierRepository.findByModelIdOrderByTierOrderAsc(m.getId());
            if (tiers.isEmpty()) continue;
            int priority = 100;
            for (PlatformModelPriceTierEntity t : tiers) {
                saveRule(m.getId(), "档位" + t.getTierOrder(), buildTokenMatchCondition(t), buildPriceJson(
                        t.getInputPrice(), t.getCacheHitInputPrice(), t.getOutputPrice(),
                        t.getCacheCreateInputPrice(), t.getCacheHitExplicitInputPrice(), t.getThinkingPrice(),
                        m.getDiscount()), priority);
                priority += 10;
            }
            migratedModels++;
        }
        if (migratedModels > 0) log.info("已迁移 {} 个阶梯计费模型为规则数据", migratedModels);
    }

    // ===== 工具 =====

    /** 阶梯档位条件树：tokenCount ∈ [lower*1000, upper*1000)；不限量档无上界 */
    private String buildTokenMatchCondition(PlatformModelPriceTierEntity t) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("operator", "AND");
        ArrayNode conditions = root.putArray("conditions");
        BigDecimal k = new BigDecimal("1000");
        conditions.add(leaf("tokenCount", ">=", t.getTierLowerLimit() != null
                ? t.getTierLowerLimit().multiply(k) : BigDecimal.ZERO));
        if (!Boolean.TRUE.equals(t.getIsUnlimited()) && t.getTierUpperLimit() != null) {
            conditions.add(leaf("tokenCount", "<", t.getTierUpperLimit().multiply(k)));
        }
        return root.toString();
    }

    private ObjectNode leaf(String field, String op, BigDecimal value) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("field", field);
        n.put("op", op);
        n.set("value", objectMapper.getNodeFactory().numberNode(value));
        return n;
    }

    private String buildPriceJson(BigDecimal input, BigDecimal cacheHit, BigDecimal output,
                                  BigDecimal create, BigDecimal explicit, BigDecimal thinking,
                                  Integer discount) {
        ObjectNode p = objectMapper.createObjectNode();
        putPrice(p, "normalPrice", input);
        putPrice(p, "output", output);
        putPrice(p, "cacheHit", cacheHit);
        putPrice(p, "cacheCreate", create);
        putPrice(p, "cacheHitExplicit", explicit);
        putPrice(p, "thinking", thinking);
        if (discount != null) p.put("discount", discount);
        return p.toString();
    }

    private void putPrice(ObjectNode p, String key, BigDecimal value) {
        if (value != null) p.put(key, value);
    }

    private void saveRule(Long modelId, String name, String matchJson, String priceJson, int priority) {
        PricingRuleEntity e = new PricingRuleEntity();
        e.setModelId(modelId);
        e.setRuleName(name);
        e.setMatchJson(matchJson);
        e.setPriceJson(priceJson);
        e.setPriority(priority);
        e.setEnabled(true);
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        ruleRepository.save(e);
    }

    private boolean isVideoModel(PlatformModelEntity m) {
        return m.getModelType() != null && "3".equals(m.getModelType().trim());
    }
}