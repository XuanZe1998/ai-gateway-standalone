package org.unreal.modelrouter.billing.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.billing.ModelPricingService;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则匹配引擎（billingMode=3 专用）。
 *
 * <p>按 priority 升序遍历模型规则，第一条命中的规则即为最终计费规则；
 * 规则数据已随 ModelPricing 进入内存缓存（计费链路不查库），
 * 本引擎从缓存数据匹配，保证请求路径零数据库访问。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingRuleEngine {

    private final ConditionTreeEvaluator evaluator;
    private final ObjectMapper objectMapper;

    /**
     * 从内存缓存中的规则列表匹配第一条命中规则。
     *
     * @param rules 规则数据（来自 ModelPricing.getRules()，已按 priority 升序）
     * @param ctx   计费上下文
     * @return 命中结果；无命中返回 null（= 该请求无适用规则，计费侧兜底）
     */
    public MatchedPricing match(List<ModelPricingService.ModelPricing.PricingRuleData> rules, UsageContext ctx) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        for (var rule : rules) {
            try {
                JsonNode matchTree = objectMapper.readTree(rule.matchJson());
                if (evaluator.evaluate(matchTree, ctx)) {
                    JsonNode priceTree = objectMapper.readTree(rule.priceJson());
                    return new MatchedPricing(
                            rule.ruleId(), rule.ruleName(), rule.priority(), parsePriceJson(priceTree));
                }
            } catch (Exception e) {
                log.error("规则匹配异常: ruleId={}, ruleName={}", rule.ruleId(), rule.ruleName(), e);
                // 单条规则解析异常不影响其他规则，继续匹配下一条
            }
        }
        return null;
    }

    private Map<String, BigDecimal> parsePriceJson(JsonNode priceTree) {
        Map<String, BigDecimal> prices = new HashMap<>();
        priceTree.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNumber()) {
                prices.put(entry.getKey(), entry.getValue().decimalValue());
            }
        });
        return prices;
    }

    /**
     * 命中结果
     *
     * @param ruleId   命中规则 ID（审计留痕）
     * @param ruleName 规则名称
     * @param priority 优先级
     * @param prices   dimension_key → 单价（元/M token）
     */
    public record MatchedPricing(Long ruleId, String ruleName, int priority,
                                 Map<String, BigDecimal> prices) {}
}