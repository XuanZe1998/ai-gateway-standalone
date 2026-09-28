package org.unreal.modelrouter.catalog;

import org.springframework.stereotype.Component;
import org.unreal.modelrouter.billing.DiscountBreakdown;
import org.unreal.modelrouter.billing.ModelPricingService.ModelPricing;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformModelEntity;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformModelPriceTierEntity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import static org.unreal.modelrouter.catalog.ModelSquareDtos.*;

@Component
public class ModelSquarePricing {
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final String TOKEN_UNIT = "元 / 百万 Token";
    public Scheme present(String serviceType, ModelPricing p, DiscountBreakdown d,
                          PlatformModelEntity raw, List<PlatformModelPriceTierEntity> rawTiers) {
        if (p == null) return unknown("未匹配到实际路由的计费配置");
        List<PriceLine> rows = new ArrayList<>();
        String mode = "整体计费";
        List<String> notes = new ArrayList<>();
        if ("vidGen".equals(serviceType)) {
            if (p.getPriceMode() == null || p.getVideoPriceRules().isEmpty()) return unknown("视频收费规则未配置");
            String unit = p.getBillingUnit() == null || p.getBillingUnit().isBlank() ? "second" : p.getBillingUnit();
            if (!List.of("second", "token").contains(unit)) return unknown("无法确认视频计费单位");
            mode = Integer.valueOf(2).equals(p.getPriceMode()) ? "视频条件定价" : "视频统一价格";
            for (var rule : p.getVideoPriceRules()) {
                if (rule.outputResolution() == null || rule.outputResolution().isBlank()) return unknown("视频收费规则缺少分辨率");
                String condition = "分辨率：" + rule.outputResolution();
                if (Integer.valueOf(2).equals(p.getPriceMode())) {
                    if (rule.hasVideoInput() == null) return unknown("视频条件定价缺少输入条件");
                    condition += Boolean.TRUE.equals(rule.hasVideoInput()) ? "；有视频输入" : "；无视频输入";
                }
                rows.add(line("视频生成", "token".equals(unit) ? TOKEN_UNIT : "元 / 秒",
                        rule.price(), true, true, "token".equals(unit), d, condition));
            }
            notes.add("创建任务不扣费；首次查询到成功时按实际用量及任务创建时的价格快照结算一次。");
        } else if (Integer.valueOf(2).equals(p.getBillingMode())) {
            mode = "输入 Token 阶梯计费";
            if (p.getTiers().isEmpty()) return unknown("阶梯价格未配置");
            for (int i = 0; i < p.getTiers().size(); i++) {
                var tier = p.getTiers().get(i);
                var source = rawTiers.stream().filter(r -> sameBoundary(r.getTierLowerLimit(), tier.lowerLimit())
                        && Boolean.TRUE.equals(r.getIsUnlimited()) == tier.unlimited()
                        && (tier.unlimited() || sameBoundary(r.getTierUpperLimit(), tier.upperLimit())))
                        .findFirst().orElse(null);
                String condition = "输入 Token ∈ [" + number(tier.lowerLimit() == null ? BigDecimal.ZERO : tier.lowerLimit())
                        + ", " + (tier.unlimited() || tier.upperLimit() == null ? "∞" : number(tier.upperLimit())) + ")";
                tokenRows(rows, p, d, condition, tier.inputPrice(), tier.outputPrice(), tier.cacheHitInputPrice(),
                        tier.cacheCreateInputPrice(), tier.cacheHitExplicitInputPrice(), tier.thinkingPrice(),
                        source == null ? null : List.of(known(source.getInputPrice(), tier.inputPrice()), known(source.getOutputPrice(), tier.outputPrice()),
                                known(source.getCacheHitInputPrice(), tier.cacheHitInputPrice()), known(source.getCacheCreateInputPrice(), tier.cacheCreateInputPrice()),
                                known(source.getCacheHitExplicitInputPrice(), tier.cacheHitExplicitInputPrice()), known(source.getThinkingPrice(), tier.thinkingPrice())));
            }
            notes.add("按本次输入 Token 总量匹配档位（含下界、不含上界），不是累进阶梯；重叠区间按实际档位顺序取最后一个匹配项。");
        } else {
            tokenRows(rows, p, d, "全部用量", p.getInputPrice(), p.getOutputPrice(), p.getCacheHitInputPrice(),
                    p.getCacheCreateInputPrice(), p.getCacheHitExplicitInputPrice(), p.getThinkingPrice(),
                    raw == null ? null : List.of(known(raw.getInputPrice(), p.getInputPrice()), known(raw.getOutputPrice(), p.getOutputPrice()),
                            known(raw.getCacheHitInputPrice(), p.getCacheHitInputPrice()), known(raw.getCacheCreateInputPrice(), p.getCacheCreateInputPrice()),
                            known(raw.getCacheHitExplicitInputPrice(), p.getCacheHitExplicitInputPrice()), known(raw.getThinkingPrice(), p.getThinkingPrice())));
        }
        boolean configured = rows.stream().noneMatch(r -> "UNKNOWN".equals(r.status()));
        if (!configured) notes.add("部分计费项缺少明确价格，不将缺失值视为免费；请联系管理员确认。");
        notes.add("折后支付比例 = min(模型折扣, 用户折扣) × 企业支付比例；使用现有账单计算规则。");
        if (!"vidGen".equals(serviceType)) notes.add("实际账单逐计费项向上取整到分后汇总；单价不是一次请求的固定费用。");
        return new Scheme("", mode, configured, d, List.copyOf(rows), List.copyOf(notes));
    }
    private void tokenRows(List<PriceLine> rows, ModelPricing p, DiscountBreakdown d, String condition,
                           BigDecimal input, BigDecimal output, BigDecimal hit, BigDecimal create,
                           BigDecimal explicit, BigDecimal thinking, List<Boolean> known) {
        rows.add(line("普通输入", TOKEN_UNIT, input, p.isEnableInputToken(), has(known, 0, input), true, d, condition));
        rows.add(line("普通输出", TOKEN_UNIT, output, p.isEnableOutputToken(), has(known, 1, output), true, d, condition));
        rows.add(line("缓存命中输入", TOKEN_UNIT, hit, p.isEnableInputToken() && p.isEnableCacheHitInput(), has(known, 2, hit), true, d, condition));
        rows.add(line("显式缓存创建", TOKEN_UNIT, create, p.isEnableInputToken() && p.isEnableCacheCreateInput(), has(known, 3, create), true, d, condition));
        rows.add(line("显式缓存命中", TOKEN_UNIT, explicit, p.isEnableInputToken() && p.isEnableCacheHitExplicitInput(), has(known, 4, explicit), true, d, condition));
        if (!p.isEnableOutputToken() || Integer.valueOf(3).equals(p.getThinkingBillingMode())) {
            rows.add(line("思考 Token", TOKEN_UNIT, thinking, false, true, true, d, condition));
        } else if (Integer.valueOf(2).equals(p.getThinkingBillingMode())) {
            rows.add(line("思考 Token", TOKEN_UNIT, thinking, true, has(known, 5, thinking), true, d, condition));
        } else {
            rows.add(new PriceLine("思考 Token", TOKEN_UNIT, null, null, "IN_OUTPUT", condition + "；并入输出，不重复收费"));
        }
    }
    private boolean has(List<Boolean> source, int i, BigDecimal value) {
        return source != null ? source.get(i) : value != null && value.signum() > 0;
    }
    private boolean known(BigDecimal raw, BigDecimal cached) {
        // Positive cache values are explicit; a normalized zero needs matching raw evidence.
        return cached != null && (cached.signum() > 0 || raw != null && raw.signum() == 0);
    }
    private boolean sameBoundary(BigDecimal rawK, BigDecimal cachedTokens) {
        return rawK == null ? cachedTokens == null : cachedTokens != null
                && rawK.multiply(new BigDecimal("1000")).compareTo(cachedTokens) == 0;
    }
    private PriceLine line(String item, String unit, BigDecimal price, boolean enabled, boolean known,
                           boolean perToken, DiscountBreakdown d, String condition) {
        if (!enabled) return new PriceLine(item, unit, null, null, "NOT_CHARGED", condition);
        if (!known || price == null || price.signum() < 0) return new PriceLine(item, unit, null, null, "UNKNOWN", condition);
        BigDecimal standard = (perToken ? price.multiply(MILLION) : price).stripTrailingZeros();
        BigDecimal effective = standard.multiply(d.finalDiscountRate()).stripTrailingZeros();
        return new PriceLine(item, unit, standard, effective, "CHARGED", condition);
    }
    private Scheme unknown(String reason) {
        return new Scheme("", "价格信息未配置／暂不可确认", false, null, List.of(), List.of(reason));
    }
    private String number(BigDecimal v) { return v.stripTrailingZeros().toPlainString(); }
}
