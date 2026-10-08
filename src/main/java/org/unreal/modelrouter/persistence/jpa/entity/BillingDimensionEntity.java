package org.unreal.modelrouter.persistence.jpa.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计费维度定义（数据驱动，替代六维硬编码）。
 *
 * <p>dimension_key 为唯一业务键（如 normalPrice/output/customXxx），计费引擎按 key 从
 * price_json 取值；display_name 为展示名（合并原 billing_dimension_alias 功能）；
 * value_type：price（单价）/ flag（开关）/ discount（折扣 0-100）。
 */
@Data
@Entity
@Table(name = "ai_billing_dimension",
        uniqueConstraints = @UniqueConstraint(name = "uq_billing_dimension",
                columnNames = {"group_key", "dimension_key"}))
public class BillingDimensionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "group_key", nullable = false, length = 16)
    private String groupKey;

    @Column(name = "dimension_key", nullable = false, length = 32)
    private String dimensionKey;

    @Column(name = "display_name", nullable = false, length = 60)
    private String displayName;

    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    @Column(name = "value_type", nullable = false, length = 10)
    private String valueType;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}