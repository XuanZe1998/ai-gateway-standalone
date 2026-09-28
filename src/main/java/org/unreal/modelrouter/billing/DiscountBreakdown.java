package org.unreal.modelrouter.billing;

import java.math.BigDecimal;

/**
 * 多级折扣率聚合结果。
 */
public record DiscountBreakdown(
        BigDecimal modelDiscountRate,
        BigDecimal userDiscountRate,
        BigDecimal enterpriseDiscountRate,
        BigDecimal finalDiscountRate
) {
}
