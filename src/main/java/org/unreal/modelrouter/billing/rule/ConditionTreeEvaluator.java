package org.unreal.modelrouter.billing.rule;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * 通用条件树评估器。
 *
 * <p>递归求值 AND/OR/NOT 逻辑节点与叶子条件（field + op + value）。
 * 这是规则引擎唯一的核心逻辑：后续新增任何规则类型/分档维度，
 * 只需扩展 UsageContext 字段（加 getter + get() case），本类无需改动。
 *
 * <p>线程安全：无状态组件，可安全并发调用。
 */
@Component
public class ConditionTreeEvaluator {

    /**
     * 评估条件树是否命中。
     *
     * @param node match_json 反序列化后的根节点（null/缺失 → 无条件，总是命中）
     * @param ctx  计费上下文
     * @return true=命中
     */
    public boolean evaluate(JsonNode node, UsageContext ctx) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return true;
        }

        String operator = node.path("operator").asText(null);
        JsonNode conditions = node.path("conditions");

        // 逻辑运算符节点（AND / OR / NOT）
        if (operator != null && conditions.isArray()) {
            return switch (operator) {
                case "AND" -> allMatch(conditions, ctx);
                case "OR" -> anyMatch(conditions, ctx);
                case "NOT" -> !evaluate(conditions.size() > 0 ? conditions.get(0) : null, ctx);
                default -> false; // 未知运算符不命中
            };
        }

        // 叶子条件：field + op + value
        String field = node.path("field").asText(null);
        String op = node.path("op").asText(null);
        if (field == null || op == null) {
            return false;
        }
        Object fieldValue = ctx.get(field);
        return compare(fieldValue, op, node.path("value"));
    }

    private boolean allMatch(JsonNode conditions, UsageContext ctx) {
        for (JsonNode child : conditions) {
            if (!evaluate(child, ctx)) {
                return false;
            }
        }
        return true;
    }

    private boolean anyMatch(JsonNode conditions, UsageContext ctx) {
        for (JsonNode child : conditions) {
            if (evaluate(child, ctx)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 叶子比较：支持 ==、!=、>、<、>=、&lt;=、between（[lower, upper)）、in、notIn。
     * 未知字段（fieldValue 为 null）一律不命中。
     */
    private boolean compare(Object fieldValue, String op, JsonNode targetValue) {
        if (fieldValue == null) {
            return false;
        }
        return switch (op) {
            case "==" -> equalsValue(fieldValue, targetValue);
            case "!=" -> !equalsValue(fieldValue, targetValue);
            case ">" -> numericCompare(fieldValue, targetValue) > 0;
            case "<" -> numericCompare(fieldValue, targetValue) < 0;
            case ">=" -> numericCompare(fieldValue, targetValue) >= 0;
            case "<=" -> numericCompare(fieldValue, targetValue) <= 0;
            case "between" -> {
                if (targetValue.isArray() && targetValue.size() == 2) {
                    // [lower, upper)：含下界、不含上界（与现有阶梯口径一致）
                    yield numericCompare(fieldValue, targetValue.get(0)) >= 0
                            && numericCompare(fieldValue, targetValue.get(1)) < 0;
                }
                yield false;
            }
            case "in" -> {
                if (targetValue.isArray()) {
                    boolean found = false;
                    for (JsonNode v : targetValue) {
                        if (equalsValue(fieldValue, v)) { found = true; break; }
                    }
                    yield found;
                }
                yield false;
            }
            case "notIn" -> {
                if (targetValue.isArray()) {
                    for (JsonNode v : targetValue) {
                        if (equalsValue(fieldValue, v)) {
                            yield false;
                        }
                    }
                    yield true;
                }
                yield true; // notIn + 空数组 = 总是 true
            }
            default -> false;
        };
    }

    private boolean equalsValue(Object fieldValue, JsonNode targetValue) {
        if (fieldValue instanceof Number n) {
            return targetValue.isNumber()
                    && targetValue.asDouble() == n.doubleValue();
        }
        if (fieldValue instanceof Boolean b) {
            return targetValue.isBoolean()
                    && targetValue.asBoolean() == b;
        }
        if (fieldValue instanceof String s) {
            return targetValue.isTextual()
                    && targetValue.asText().equals(s);
        }
        return false;
    }

    private int numericCompare(Object fieldValue, JsonNode targetValue) {
        double d1 = fieldValue instanceof Number n ? n.doubleValue() : 0;
        double d2 = targetValue.isNumber() ? targetValue.asDouble() : 0;
        return Double.compare(d1, d2);
    }
}