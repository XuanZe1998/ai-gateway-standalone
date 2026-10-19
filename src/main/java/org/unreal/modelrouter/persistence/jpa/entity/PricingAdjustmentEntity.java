package org.unreal.modelrouter.persistence.jpa.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Entity
@Table(name = "ai_pricing_adjustment")
public class PricingAdjustmentEntity {
    @Id
    @Column(name = "scope_key", length = 300)
    private String scopeKey;
    @Column(nullable = false)
    private Boolean enabled;
    @Column(nullable = false, length = 20)
    private String mode;
    @Column(name = "adjustment_value", nullable = false, precision = 20, scale = 8)
    private BigDecimal value;
    @Version
    private Long version;
}
