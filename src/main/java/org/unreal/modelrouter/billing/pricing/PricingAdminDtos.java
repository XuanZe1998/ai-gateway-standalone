package org.unreal.modelrouter.billing.pricing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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

    /** 详情：编辑回显 + 阶梯档位 + 当前计费组的维度显示别名 */
    public record PricingDetail(PricingEditable editable, List<TierRow> tiers,
                                Map<String, String> dimensionAliases) {}

    /** 手动保存主定价：整体模式写主价字段，阶梯模式写各档价格（tierPrices）+ 整组维度别名 */
    public record PricingSave(String serviceType, String modelId, String vendor, Integer billingMode,
                              BigDecimal inputPrice, BigDecimal cacheHitInputPrice, BigDecimal outputPrice,
                              BigDecimal cacheCreateInputPrice, BigDecimal cacheHitExplicitInputPrice,
                              Boolean enableInputToken, Boolean enableCacheHitInput, Boolean enableOutputToken,
                              Boolean enableCacheCreateInput, Boolean enableCacheHitExplicitInput,
                              Integer thinkingBillingMode, BigDecimal thinkingPrice, Integer discount,
                              Map<String, String> dimensionAliases, List<TierPriceRow> tierPrices) {}

    /** 阶梯模式：每档价格（按 tierOrder 顺序对位写入既有档位；与挡位数量取小） */
    public record TierPriceRow(BigDecimal inputPrice, BigDecimal cacheHitInputPrice, BigDecimal outputPrice,
                               BigDecimal cacheCreateInputPrice, BigDecimal cacheHitExplicitInputPrice,
                               BigDecimal thinkingPrice) {}

    /** 阶梯档位（仅区间；价格在编辑定价弹窗的阶梯矩阵中维护） */
    public record TierRangeRow(BigDecimal lowerLimitK, BigDecimal upperLimitK, boolean unlimited) {}

    /** 保存阶梯档位（整体替换区间；价格按序继承：超出丢弃、新增留空） */
    public record TiersSave(String serviceType, String modelId, List<TierRangeRow> tiers) {}
}

