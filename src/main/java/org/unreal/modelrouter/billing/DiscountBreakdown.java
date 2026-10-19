package org.unreal.modelrouter.billing;

import java.math.BigDecimal;

/**
 * 多级折扣率聚合结果。
 */
public record DiscountBreakdown(
        BigDecimal modelDiscountRate,
        BigDecimal userDiscountRate,
        BigDecimal enterpriseDiscountRate,
        BigDecimal finalDiscountRate,
        BigDecimal fixedAdjustment
) {
    public DiscountBreakdown(BigDecimal modelRate, BigDecimal userRate, BigDecimal enterpriseRate, BigDecimal finalRate) {
        this(modelRate, userRate, enterpriseRate, finalRate, BigDecimal.ZERO);
    }

    /** Fixed adjustment is expressed per quoted unit, e.g. per million tokens or per image. */
    public BigDecimal adjustedUnitPrice(BigDecimal price, BigDecimal unitsPerQuote) {
        return price.add(fixedAdjustment.divide(unitsPerQuote)).max(BigDecimal.ZERO);
    }
}
