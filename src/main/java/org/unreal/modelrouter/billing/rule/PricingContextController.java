package org.unreal.modelrouter.billing.rule;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.billing.rule.RuleAdminDtos.ContextFieldDto;

import java.util.List;
import java.util.Map;

/**
 * 上下文字段元数据 API：前端据此动态渲染条件编辑器
 * （字段下拉 → 操作符下拉 → 值输入控件），完全数据驱动。
 */
@RestController
@RequestMapping("/api/admin/pricing")
@PreAuthorize("hasRole('ADMIN')")
public class PricingContextController {

    /** 可用字段及其操作符（valueType：int / boolean / string） */
    private static final Map<String, List<String>> OPERATORS = Map.of(
            "int", List.of("==", "!=", ">", "<", ">=", "<=", "between", "in"),
            "boolean", List.of("==", "!="),
            "string", List.of("==", "!=", "in", "notIn")
    );

    private static final List<ContextFieldDto> FIELDS = List.of(
            new ContextFieldDto("tokenCount", "Token数量", "int", OPERATORS.get("int")),
            new ContextFieldDto("outputTokenCount", "输出Token数量", "int", OPERATORS.get("int")),
            new ContextFieldDto("contextLength", "上下文长度", "int", OPERATORS.get("int")),
            new ContextFieldDto("stream", "流式请求", "boolean", OPERATORS.get("boolean")),
            new ContextFieldDto("hasToolCall", "调用工具", "boolean", OPERATORS.get("boolean")),
            new ContextFieldDto("hour", "时间·小时", "int", List.of("between", "==", "!=", "in")),
            new ContextFieldDto("weekday", "星期几(1-7)", "int", List.of("==", "in")),
            new ContextFieldDto("isWeekend", "是否周末", "boolean", OPERATORS.get("boolean")),
            new ContextFieldDto("month", "月份(1-12)", "int", List.of("==", "in")),
            new ContextFieldDto("modelType", "模型类型", "string", OPERATORS.get("string")),
            new ContextFieldDto("vendor", "厂商", "string", OPERATORS.get("string")),
            new ContextFieldDto("cacheHit", "缓存命中", "boolean", OPERATORS.get("boolean")),
            new ContextFieldDto("thinkingEnabled", "启用思考", "boolean", OPERATORS.get("boolean"))
    );

    @GetMapping("/context-fields")
    public List<ContextFieldDto> contextFields() {
        return FIELDS;
    }
}