package org.unreal.modelrouter.billing.rule;

import java.util.List;

/** 定价规则引擎管理 DTO（维度 + 规则 + 上下文字段元数据）。 */
public final class RuleAdminDtos {
    private RuleAdminDtos() {}

    // ===== 计费维度 =====
    public record DimensionDto(Long id, String groupKey, String dimensionKey,
                               String displayName, String unit, String valueType,
                               int sortOrder, boolean enabled) {}

    public record DimensionCreateDto(String groupKey, String dimensionKey,
                                     String displayName, String unit, String valueType) {}

    public record DimensionUpdateDto(String displayName, String unit,
                                     Integer sortOrder, Boolean enabled) {}

    // ===== 计费规则 =====
    public record RuleDto(Long id, Long modelId, String modelName, String ruleName,
                          String matchJson, String priceJson,
                          int priority, boolean enabled) {}

    public record RuleCreateDto(String serviceType, String modelName, String ruleName,
                                String matchJson, String priceJson, Integer priority) {}

    public record RuleUpdateDto(String ruleName, String matchJson,
                                String priceJson, Boolean enabled) {}

    public record PriorityDto(int priority) {}

    public record ReorderDto(List<Long> orderedRuleIds) {}

    // ===== 上下文字段元数据（前端动态渲染条件编辑器） =====
    public record ContextFieldDto(String field, String label, String valueType,
                                  List<String> operators) {}

    public record OperatorDto(String valueType, List<String> operators) {}
}