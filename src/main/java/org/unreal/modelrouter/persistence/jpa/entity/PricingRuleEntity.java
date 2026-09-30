package org.unreal.modelrouter.persistence.jpa.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计费规则（数据驱动，替代 ai_model_price_tier 的硬编码分档）。
 *
 * <p>match_json：条件树（AND/OR/叶子 field+op+value），由 ConditionTreeEvaluator 解释；
 * price_json：{dimension_key: 单价}，key 来自 ai_billing_dimension；
 * priority 小值先匹配，同一模型内唯一。
 */
@Data
@Entity
@Table(name = "ai_model_price_rule")
public class PricingRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "model_id", nullable = false)
    private Long modelId;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    @Column(name = "match_json", nullable = false, columnDefinition = "jsonb")
    private String matchJson;

    @Column(name = "price_json", nullable = false, columnDefinition = "jsonb")
    private String priceJson;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}