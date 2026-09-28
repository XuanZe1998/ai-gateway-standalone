package org.unreal.modelrouter.persistence.jpa.entity.platform;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 映射算力平台 ai_model_price_tier 表（只读）— 阶梯计费档位。
 *
 * <p>仅当 ai_model.billing_mode=2（阶梯计费）时使用。
 * <p>不含 enable 配置（继承主表 ai_model）；thinking_price 仅当主表 thinking_billing_mode=2 时有效。
 *
 * <p>⚠ tier_lower_limit/tier_upper_limit 表里存的是 K 值（表单值，如 128 表示 128K），
 *    同步到 ModelPricing.PriceTier 时需 ×1000 转成 token 数，否则档位匹配错误（资损）。
 */
@Data
@Entity
@Table(name = "ai_model_price_tier")
public class PlatformModelPriceTierEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "model_id")
    private Long modelId;

    @Column(name = "tier_order")
    private Integer tierOrder;

    /** 档位下限（K 值，使用时 ×1000 转 token） */
    @Column(name = "tier_lower_limit")
    private BigDecimal tierLowerLimit;

    /** 档位上限（K 值，使用时 ×1000 转 token；is_unlimited=true 时为 null） */
    @Column(name = "tier_upper_limit")
    private BigDecimal tierUpperLimit;

    @Column(name = "is_unlimited")
    private Boolean isUnlimited;

    @Column(name = "input_price")
    private BigDecimal inputPrice;

    @Column(name = "cache_hit_input_price")
    private BigDecimal cacheHitInputPrice;

    @Column(name = "output_price")
    private BigDecimal outputPrice;

    @Column(name = "cache_create_input_price")
    private BigDecimal cacheCreateInputPrice;

    @Column(name = "cache_hit_explicit_input_price")
    private BigDecimal cacheHitExplicitInputPrice;

    /** 思考token单价（元/M token），仅当主表 thinking_billing_mode=2 时有效 */
    @Column(name = "thinking_price")
    private BigDecimal thinkingPrice;
}
