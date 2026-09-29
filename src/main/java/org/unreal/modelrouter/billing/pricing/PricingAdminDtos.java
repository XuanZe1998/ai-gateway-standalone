package org.unreal.modelrouter.billing.pricing;

import java.math.BigDecimal;
import java.util.List;

/** 模型定价管理 DTO（显式白名单，不序列化平台实体/路由实例）。 */
public final class PricingAdminDtos {
    private PricingAdminDtos() {}

    /** 列表行：一个运行中模型的定价状态 */
    public record PricingRow(String serviceType, String modelId, Long platformModelId, String vendor,
                             Integer billingMode, BigDecimal inputPrice, BigDecimal cacheHitInputPrice,
                             BigDecimal outputPrice, BigDecimal thinkingPrice, int tierCount,
                             boolean configured) {}

    /** 阶梯档位行（lower/upper 为 K 值，与 ai_model_price_tier 表存储口径一致） */
    public record TierRow(Long id, Integer tierOrderNo, BigDecimal lowerLimitK, BigDecimal upperLimitK,
                          boolean unlimited, BigDecimal inputPrice, BigDecimal cacheHitInputPrice,
                          BigDecimal outputPrice, BigDecimal cacheCreateInputPrice,
                          BigDecimal cacheHitExplicitInputPrice, BigDecimal thinkingPrice) {}

    /** 编辑回显视图：主定价全部可编辑字段（enable 为 null 时前端按默认值处理） */
    public record PricingEditable(String serviceType, String modelId, String vendor, Integer billingMode,
                                  BigDecimal inputPrice, BigDecimal cacheHitInputPrice, BigDecimal outputPrice,
                                  BigDecimal cacheCreateInputPrice, BigDecimal cacheHitExplicitInputPrice,
                                  Boolean enableInputToken, Boolean enableCacheHitInput, Boolean enableOutputToken,
                                  Boolean enableCacheCreateInput, Boolean enableCacheHitExplicitInput,
                                  Integer thinkingBillingMode, BigDecimal thinkingPrice, Integer discount) {}

    /** 详情：编辑回显 + 阶梯档位 */
    public record PricingDetail(PricingEditable editable, List<TierRow> tiers) {}

    /** 保存主定价（手动修改入口；与 PricingEditable 字段一致） */
    public record PricingSave(String serviceType, String modelId, String vendor, Integer billingMode,
                              BigDecimal inputPrice, BigDecimal cacheHitInputPrice, BigDecimal outputPrice,
                              BigDecimal cacheCreateInputPrice, BigDecimal cacheHitExplicitInputPrice,
                              Boolean enableInputToken, Boolean enableCacheHitInput, Boolean enableOutputToken,
                              Boolean enableCacheCreateInput, Boolean enableCacheHitExplicitInput,
                              Integer thinkingBillingMode, BigDecimal thinkingPrice, Integer discount) {}

    /** 保存阶梯档位（整体替换；至少一个档位） */
    public record TiersSave(String serviceType, String modelId, List<TierRow> tiers) {}
}

