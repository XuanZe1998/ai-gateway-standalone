package org.unreal.modelrouter.persistence.jpa.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计费维度显示别名（仅展示层）。
 *
 * <p>按计费组（text/voice/image）全局生效，一个维度一条：dimension_key 与前端
 * 策略键一致（normalPrice/output/cacheHit/cacheCreate/cacheHitExplicit/thinking/discount），
 * display_name 为空时不落库（视为恢复默认名）。底层六维计费字段与计费逻辑不受影响。
 */
@Data
@Entity
@Table(name = "billing_dimension_alias",
        uniqueConstraints = @UniqueConstraint(name = "uq_billing_dimension_alias",
                columnNames = {"group_key", "dimension_key"}))
public class BillingDimensionAliasEntity {

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

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
