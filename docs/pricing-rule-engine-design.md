# 定价规则引擎技术设计文档

> 版本：v1.0 | 日期：2026-09-30 | 范围：后端引擎 + 存储表 + API + 迁移方案（前端不动）

---

## 1. 背景与目标

### 1.1 现状

当前定价系统存在三处硬编码：
- **计费维度**：六维（输入/输出/缓存命中/缓存创建/缓存显式命中/思考），列固定在 `ai_model` 和 `ai_model_price_tier` 表中
- **分档规则**：仅支持按 token 量上下限分档，匹配逻辑写死在 `BillingService.matchTier()`
- **维度名称**：别名表 `billing_dimension_alias` 按组覆盖，但维度集合本身是固定的

新增分档维度（如按时间段、按周末/工作日）或新增计费维度需要改表结构 + 改后端匹配代码 + 改前端 UI。

### 1.2 目标

将计费规则从"代码写死"变为"数据驱动"：
- 后端实现**通用条件树引擎**，只有一次开发，后续新增规则类型不再改后端代码
- 前端实现**表单式条件树编辑器**，管理员自由组合匹配条件和计费维度
- 计费维度也规则化，支持自定义新增维度
- **保留三种计费模式并列**：整体计费、阶梯计费、规则计费
- **自动迁移**现有数据到规则化存储
- `isHoliday` 字段暂不实现

### 1.3 实施范围

本次仅做**后端**：存储表 + 条件树引擎 + 规则匹配引擎 + 管理 API + 数据迁移脚本。
前端 UI 不在本次范围，后端跑通后再做前端。

---

## 2. 存储设计

### 2.1 新增表

#### 2.1.1 `ai_billing_dimension`（维度定义表）

替代六维硬编码，合并 `billing_dimension_alias` 表功能。

```sql
CREATE TABLE ai_billing_dimension (
    id           BIGSERIAL PRIMARY KEY,
    group_key    VARCHAR(16)  NOT NULL,    -- 计费组：text / voice / image
    dimension_key VARCHAR(32) NOT NULL,    -- 唯一键：normalPrice / output / customXxx
    display_name VARCHAR(60)  NOT NULL,    -- 显示名（原别名表功能）
    unit         VARCHAR(20)  NOT NULL,    -- 单位文案：元/百万Token、元/秒
    value_type   VARCHAR(10)  NOT NULL,    -- price（单价）/ flag（开关）/ discount（折扣）
    sort_order   INT          NOT NULL DEFAULT 0,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_billing_dimension UNIQUE (group_key, dimension_key)
);
```

#### 2.1.2 `ai_model_price_rule`（计费规则表）

替代 `ai_model_price_tier`，存储条件树 + 动态维度价格。

```sql
CREATE TABLE ai_model_price_rule (
    id         BIGSERIAL PRIMARY KEY,
    model_id   BIGINT       NOT NULL,      -- 关联 ai_model.id
    rule_name  VARCHAR(100) NOT NULL,      -- 可读名："工作日高峰"、"0-128K"
    match_json JSONB        NOT NULL,      -- 条件树（AND/OR/leaf）
    price_json JSONB        NOT NULL,      -- {"dimension_key": 单价} 动态维度价格
    priority   INT          NOT NULL DEFAULT 100,  -- 匹配优先级（小值先匹配）
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_price_rule_model ON ai_model_price_rule (model_id, priority);
CREATE UNIQUE INDEX uq_price_rule_priority ON ai_model_price_rule (model_id, priority);
```

### 2.2 match_json 结构

```json
// 按时间段分档
{
  "operator": "AND",
  "conditions": [
    {"field": "isWeekday", "op": "==", "value": true},
    {"field": "hour", "op": "between", "value": [8, 22]}
  ]
}

// 按 token 分档（现有阶梯逻辑变成数据）
{
  "operator": "AND",
  "conditions": [
    {"field": "tokenCount", "op": ">=", "value": 0},
    {"field": "tokenCount", "op": "<", "value": 128000}
  ]
}

// 组合：周末 + 大请求
{
  "operator": "AND",
  "conditions": [
    {"field": "isWeekend", "op": "==", "value": true},
    {"field": "tokenCount", "op": ">=", "value": 100000}
  ]
}

// 无条件（整体计费 = 兜底规则，conditions 为空数组）
{
  "operator": "AND",
  "conditions": []
}
```

### 2.3 price_json 结构

```json
{
  "normalPrice": 5.0,
  "output": 10.0,
  "cacheHit": 2.0,
  "thinking": 3.0,
  "discount": 80
}
```

- key 来自 `ai_billing_dimension.dimension_key`
- value 类型由维度的 `value_type` 决定：price=数值（元/M token）、flag=布尔、discount=0-100 整数
- 未出现的 key 表示该维度不参与本规则计费

### 2.4 现有表的关系

| 现有表 | 新表 | 关系 |
|---|---|---|
| `ai_model` 六列（input_price 等） | `ai_model_price_rule.price_json` | 迁移后数据等价，老列保留不删 |
| `ai_model_price_tier` 六列 | `ai_model_price_rule.price_json` | 迁移后数据等价，老表保留不删 |
| `billing_dimension_alias` | `ai_billing_dimension.display_name` | 合并，别名表迁移后废弃 |
| `ai_model.billing_mode` | `ai_model.billing_mode` | 扩展：1=整体, 2=阶梯, **3=规则** |

---

## 3. UsageContext 设计

### 3.1 字段清单

| 字段 | 类型 | 说明 | 初期实现 |
|---|---|---|---|
| `tokenCount` | int | 本次输入 token 总量 | ✅ |
| `outputTokenCount` | int | 输出 token 量 | ✅ |
| `stream` | boolean | 是否流式请求 | ✅ |
| `hasToolCall` | boolean | 是否调用了工具 | ✅ |
| `contextLength` | int | 上下文长度 | ✅ |
| `batchSize` | int | 批量请求大小 | 预留（getter 返回 null） |
| `hour` | int | 请求小时（0-23） | ✅ |
| `weekday` | int | 星期几（1-7） | ✅ |
| `isWeekend` | boolean | 是否周末 | ✅ |
| `isHoliday` | boolean | 是否法定节假日 | ❌ 不实现 |
| `month` | int | 月份（1-12） | ✅ |
| `dateRange` | string | 日期区间 | 预留 |
| `userType` | string | 用户类型 | 预留 |
| `accountTier` | string | 账户等级 | 预留 |
| `apiKeyType` | string | API Key 类型 | 预留 |
| `hasFreeQuota` | boolean | 是否有剩余免费额度 | 预留 |
| `department` | string | 部门/组织 | 预留 |
| `modelType` | string | 模型类型 | ✅ |
| `vendor` | string | 厂商 | ✅ |
| `modelVersion` | string | 模型版本 | 预留 |
| `cacheHit` | boolean | 是否缓存命中 | ✅ |
| `thinkingEnabled` | boolean | 是否启用思考 | ✅ |

### 3.2 Java 实现

```java
package org.unreal.modelrouter.billing.rule;

/**
 * 计费上下文：请求处理时构建，传递给条件树引擎和规则匹配引擎。
 * 初期实现的字段从现有 BillingContext 中提取，预留字段 getter 返回 null。
 */
public class UsageContext {
    // ===== 初期实现 =====
    private final int tokenCount;
    private final int outputTokenCount;
    private final boolean stream;
    private final boolean hasToolCall;
    private final int contextLength;
    private final int hour;
    private final int weekday;
    private final boolean isWeekend;
    private final int month;
    private final String modelType;
    private final String vendor;
    private final boolean cacheHit;
    private final boolean thinkingEnabled;

    // 构造器（从 BillingContext / HttpRequest 提取）
    public UsageContext(int tokenCount, int outputTokenCount, boolean stream,
                        boolean hasToolCall, int contextLength,
                        int hour, int weekday, boolean isWeekend, int month,
                        String modelType, String vendor,
                        boolean cacheHit, boolean thinkingEnabled) {
        this.tokenCount = tokenCount;
        this.outputTokenCount = outputTokenCount;
        this.stream = stream;
        this.hasToolCall = hasToolCall;
        this.contextLength = contextLength;
        this.hour = hour;
        this.weekday = weekday;
        this.isWeekend = isWeekend;
        this.month = month;
        this.modelType = modelType;
        this.vendor = vendor;
        this.cacheHit = cacheHit;
        this.thinkingEnabled = thinkingEnabled;
    }

    /**
     * 通用字段取值：条件树引擎按字段名取值。
     * 未知字段返回 null，叶子条件比较时 null != targetValue → 不命中。
     */
    public Object get(String field) {
        return switch (field) {
            case "tokenCount" -> tokenCount;
            case "outputTokenCount" -> outputTokenCount;
            case "stream" -> stream;
            case "hasToolCall" -> hasToolCall;
            case "contextLength" -> contextLength;
            case "hour" -> hour;
            case "weekday" -> weekday;
            case "isWeekend" -> isWeekend;
            case "month" -> month;
            case "modelType" -> modelType;
            case "vendor" -> vendor;
            case "cacheHit" -> cacheHit;
            case "thinkingEnabled" -> thinkingEnabled;
            // 预留字段（后续按需开放，只需加一个 case）
            default -> null;
        };
    }

    // 标准 getter（供规则引擎内部使用）
    public int getTokenCount() { return tokenCount; }
    public int getHour() { return hour; }
    public boolean isWeekend() { return isWeekend; }
    // ... 其余 getter 省略
}
```

### 3.3 UsageContext 构建时机

在 `BillingService.recordBilling()` 中，现有 `BillingContext` 已包含 tokenCount、outputTokenCount、stream 等信息。新增一步：从 `BillingContext` + 请求时间提取 `UsageContext`。

```java
// BillingService.recordBilling() 中新增
UsageContext usageCtx = new UsageContext(
    promptTokens,            // tokenCount
    completionTokens,         // outputTokenCount
    ctx.isStream(),           // stream
    ctx.hasToolCall(),        // hasToolCall
    promptTokens,             // contextLength（初期 = promptTokens）
    now.getHour(),            // hour
    now.getDayOfWeek().getValue(), // weekday (1-7)
    now.getDayOfWeek().getValue() >= 6, // isWeekend
    now.getMonthValue(),      // month
    ctx.getModelType(),        // modelType
    ctx.getVendor(),          // vendor
    cacheHit > 0,              // cacheHit
    thinkingMode != 1           // thinkingEnabled
);
```

---

## 4. 条件树引擎

### 4.1 核心类

```java
package org.unreal.modelrouter.billing.rule;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * 通用条件树评估器。递归求值 AND/OR/NOT + 叶子条件比较。
 * 这是唯一需要写的后端逻辑，以后新增规则类型不需要改这里。
 */
@Component
public class ConditionTreeEvaluator {

    /**
     * 评估条件树是否命中。
     * @param node 条件树根节点（从 match_json 反序列化）
     * @param ctx  计费上下文
     * @return true=命中
     */
    public boolean evaluate(JsonNode node, UsageContext ctx) {
        if (node == null || node.isNull()) return true; // null = 无条件 = 总是命中

        String operator = node.path("operator").asText(null);
        JsonNode conditions = node.path("conditions");

        // 逻辑运算符节点
        if (operator != null && conditions.isArray()) {
            return switch (operator) {
                case "AND" -> {
                    boolean allMatch = true;
                    for (JsonNode child : conditions) {
                        if (!evaluate(child, ctx)) { allMatch = false; break; }
                    }
                    yield allMatch;
                }
                case "OR" -> {
                    boolean anyMatch = false;
                    for (JsonNode child : conditions) {
                        if (evaluate(child, ctx)) { anyMatch = true; break; }
                    }
                    yield anyMatch;
                }
                case "NOT" -> {
                    JsonNode first = conditions.size() > 0 ? conditions.get(0) : null;
                    yield !evaluate(first, ctx);
                }
                default -> false;
            };
        }

        // 叶子条件：field + op + value
        String field = node.path("field").asText(null);
        String op = node.path("op").asText(null);
        JsonNode valueNode = node.path("value");

        if (field == null || op == null) return false;

        Object fieldValue = ctx.get(field);
        return compare(fieldValue, op, valueNode);
    }

    /**
     * 通用比较：支持 ==、!=、>、<、>=、<=、between、in、notIn
     */
    private boolean compare(Object fieldValue, String op, JsonNode targetValue) {
        if (fieldValue == null) return false; // 未知字段不命中

        return switch (op) {
            case "==" -> equalsValue(fieldValue, targetValue);
            case "!=" -> !equalsValue(fieldValue, targetValue);
            case ">"  -> numericCompare(fieldValue, targetValue) > 0;
            case "<"  -> numericCompare(fieldValue, targetValue) < 0;
            case ">=" -> numericCompare(fieldValue, targetValue) >= 0;
            case "<=" -> numericCompare(fieldValue, targetValue) <= 0;
            case "between" -> {
                if (targetValue.isArray() && targetValue.size() == 2) {
                    int cmp1 = numericCompare(fieldValue, targetValue.get(0));
                    int cmp2 = numericCompare(fieldValue, targetValue.get(1));
                    yield cmp1 >= 0 && cmp2 < 0; // [lower, upper)
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
                    boolean found = false;
                    for (JsonNode v : targetValue) {
                        if (equalsValue(fieldValue, v)) { found = true; break; }
                    }
                    yield !found;
                }
                yield true; // notIn + 空数组 = 总是 true
            }
            default -> false;
        };
    }

    private boolean equalsValue(Object fieldValue, JsonNode targetValue) {
        if (fieldValue instanceof Number n) {
            return targetValue.isNumber() && targetValue.asDouble() == n.doubleValue();
        }
        if (fieldValue instanceof Boolean b) {
            return targetValue.isBoolean() && targetValue.asBoolean() == b;
        }
        if (fieldValue instanceof String s) {
            return targetValue.isTextual() && targetValue.asText().equals(s);
        }
        return false;
    }

    private int numericCompare(Object fieldValue, JsonNode targetValue) {
        double d1 = fieldValue instanceof Number n ? n.doubleValue() : 0;
        double d2 = targetValue.isNumber() ? targetValue.asDouble() : 0;
        return Double.compare(d1, d2);
    }
}
```

### 4.2 设计要点

- **递归求值**：AND/OR/NOT 递归调用 `evaluate()`，叶子条件调用 `compare()`
- **短路求值**：AND 遇到 false 立即返回，OR 遇到 true 立即返回
- **类型安全**：`equalsValue()` 按类型分支（Number/Boolean/String），类型不匹配返回 false
- **null 安全**：未知字段（`ctx.get()` 返回 null）永远不命中
- **无状态**：`@Component` 单例，线程安全（无可变状态）

---

## 5. 规则匹配引擎

### 5.1 核心类

```java
package org.unreal.modelrouter.billing.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.billing.ModelPricingService.ModelPricing;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PricingRuleEngine {

    private final ConditionTreeEvaluator evaluator;
    private final PricingRuleRepository ruleRepository;
    private final ObjectMapper objectMapper;

    /**
     * 匹配规则并返回命中的价格维度 map。
     * 规则按 priority 升序遍历，第一条命中的规则即为最终计费规则。
     *
     * @param modelId  ai_model.id
     * @param ctx      计费上下文
     * @return 命中规则的 dimension_key → 单价；无命中返回 null（= 未配置）
     */
    public MatchedPricing match(Long modelId, UsageContext ctx) {
        List<PricingRuleEntity> rules = ruleRepository
                .findByModelIdAndEnabledTrueOrderByPriorityAsc(modelId);

        for (PricingRuleEntity rule : rules) {
            try {
                JsonNode matchTree = objectMapper.readTree(rule.getMatchJson());
                if (evaluator.evaluate(matchTree, ctx)) {
                    JsonNode priceTree = objectMapper.readTree(rule.getPriceJson());
                    Map<String, BigDecimal> prices = parsePriceJson(priceTree);
                    return new MatchedPricing(
                        rule.getId(), rule.getRuleName(), rule.getPriority(), prices);
                }
            } catch (Exception e) {
                log.error("规则匹配异常: ruleId={}, modelId={}", rule.getId(), modelId, e);
                // 单条规则解析异常不影响其他规则，继续匹配下一条
            }
        }
        return null; // 无命中 = 未配置
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
     * 匹配结果
     * @param ruleId    命中规则 ID
     * @param ruleName  规则名称（用于日志/审计）
     * @param priority  优先级
     * @param prices    dimension_key → 单价（元/M token）
     */
    public record MatchedPricing(
        Long ruleId, String ruleName, int priority, Map<String, BigDecimal> prices
    ) {}
}
```

### 5.2 与 BillingService 的集成

在 `BillingService.recordBilling()` 中新增分流逻辑：

```java
// ===== 计费单价选取（三模式分流）=====
if (pricing != null && Integer.valueOf(3).equals(pricing.getBillingMode())) {
    // ===== 规则计费（新引擎）=====
    UsageContext usageCtx = buildUsageContext(ctx, promptTokens, completionTokens,
            thinkingMode, cacheHit, now);
    PricingRuleEngine.MatchedPricing matched = ruleEngine.match(pricing.getModelId(), usageCtx);
    if (matched != null) {
        // 从 matched.prices() 取各维度单价
        inputPrice = matched.prices().getOrDefault("normalPrice", BigDecimal.ZERO);
        outputPrice = matched.prices().getOrDefault("output", BigDecimal.ZERO);
        cacheHitInputPrice = matched.prices().getOrDefault("cacheHit", BigDecimal.ZERO);
        cacheCreateInputPrice = matched.prices().getOrDefault("cacheCreate", BigDecimal.ZERO);
        cacheHitExplicitInputPrice = matched.prices().getOrDefault("cacheHitExplicit", BigDecimal.ZERO);
        if (thinkingMode == 2) {
            thinkingPrice = matched.prices().getOrDefault("thinking", BigDecimal.ZERO);
        }
        // 折扣
        Integer discount = matched.prices().get("discount") != null
                ? matched.prices().get("discount").intValue() : null;
        if (discount != null) {
            pricing = pricing.withDiscountRate(BigDecimal.valueOf(discount / 100.0));
        }
    } else {
        // 无命中规则 = 未配置，资损兜底
        log.warn("规则计费未命中任何规则: modelId={}", pricing.getModelId());
        // 走 hasUsablePricing 拦截，不会到达此处
    }
} else if (pricing != null && Integer.valueOf(2).equals(pricing.getBillingMode())
        && !pricing.getTiers().isEmpty()) {
    // ===== 阶梯计费（现有逻辑）=====
    ModelPricingService.ModelPricing.PriceTier tier = matchTier(pricing.getTiers(), promptTokens);
    // ... 现有代码不变
} else if (pricing != null) {
    // ===== 整体计费（现有逻辑）=====
    // ... 现有代码不变
}
```

### 5.3 与 ModelPricingService 的集成

`ModelPricingService` 需要加载规则化定价数据并缓存。在 `PlatformDataSyncService.syncAllPricings()` 中新增分支：

```java
// 加载规则化定价（billingMode=3）
if (Integer.valueOf(3).equals(m.getBillingMode())) {
    List<PricingRuleEntity> rules = ruleRepository.findByModelIdAndEnabledTrueOrderByPriorityAsc(m.getId());
    // 规则数据随 ModelPricing 一起缓存（新增 rules 字段）
    return new ModelPricing(..., rules);  // ModelPricing 新增 rules 字段
}
```

`ModelPricing` 类新增一个字段：

```java
// 规则计费数据（billingMode=3 时非空）
private final List<PricingRuleData> rules;

public record PricingRuleData(Long ruleId, String ruleName, int priority,
                               String matchJson, String priceJson) {}
```

### 5.4 hasUsablePricing 扩展

```java
public boolean hasUsablePricing(String modelName, String channelId) {
    ModelPricing pricing = getPrice(modelName, channelId);
    if (pricing == null) return false;
    return switch (pricing.getBillingMode()) {
        case 1 -> pricing.getInputPrice() != null && pricing.getOutputPrice() != null;
        case 2 -> !pricing.getTiers().isEmpty()
                && pricing.getTiers().stream()
                    .allMatch(t -> t.inputPrice() != null && t.outputPrice() != null);
        case 3 -> !pricing.getRules().isEmpty();  // 规则模式：至少有一条启用规则
        default -> false;
    };
}
```

---

## 6. 管理 API

### 6.1 维度管理

```java
@RestController
@RequestMapping("/api/admin/billing/dimensions")
public class BillingDimensionController {

    @GetMapping
    List<DimensionDto> list(@RequestParam(required = false) String groupKey);

    @PostMapping
    DimensionDto create(@RequestBody DimensionCreateDto dto);

    @PutMapping("/{id}")
    DimensionDto update(@PathVariable Long id, @RequestBody DimensionUpdateDto dto);

    @DeleteMapping("/{id}")
    void delete(@PathVariable Long id);
}
```

**DTO 定义**：

```java
public record DimensionDto(Long id, String groupKey, String dimensionKey,
                           String displayName, String unit, String valueType,
                           int sortOrder, boolean enabled) {}

public record DimensionCreateDto(String groupKey, String dimensionKey,
                                  String displayName, String unit, String valueType) {}

public record DimensionUpdateDto(String displayName, String unit,
                                  Integer sortOrder, Boolean enabled) {}
```

### 6.2 规则管理

```java
@RestController
@RequestMapping("/api/admin/pricing/rules")
public class PricingRuleController {

    @GetMapping
    List<RuleDto> list(@RequestParam Long modelId);

    @PostMapping
    RuleDto create(@RequestBody RuleCreateDto dto);

    @PutMapping("/{id}")
    RuleDto update(@PathVariable Long id, @RequestBody RuleUpdateDto dto);

    @DeleteMapping("/{id}")
    void delete(@PathVariable Long id);

    @PutMapping("/{id}/priority")
    void updatePriority(@PathVariable Long id, @RequestBody PriorityDto dto);

    @PostMapping("/reorder")
    void reorder(@RequestBody ReorderDto dto);  // 批量调整优先级（拖拽排序用）
}
```

**DTO 定义**：

```java
public record RuleDto(Long id, Long modelId, String ruleName,
                      String matchJson, String priceJson,
                      int priority, boolean enabled) {}

public record RuleCreateDto(Long modelId, String ruleName,
                             String matchJson, String priceJson, Integer priority) {}

public record RuleUpdateDto(String ruleName, String matchJson,
                             String priceJson, Boolean enabled) {}

public record PriorityDto(int priority) {}

public record ReorderDto(List<Long> orderedRuleIds) {}
```

### 6.3 上下文字段元数据

```java
@RestController
@RequestMapping("/api/admin/pricing")
public class PricingContextController {

    @GetMapping("/context-fields")
    List<ContextFieldDto> contextFields();

    @GetMapping("/operators")
    List<OperatorDto> operators(@RequestParam String valueType);
}
```

**返回示例**：

```json
[
  {"field": "tokenCount", "label": "Token数量", "valueType": "int",
   "operators": [">=", "<", "between", "=="]},
  {"field": "hour", "label": "时间·小时", "valueType": "int",
   "operators": ["between", "==", "in"]},
  {"field": "isWeekend", "label": "日期·周末", "valueType": "boolean",
   "operators": ["=="]}
]
```

前端拿到这个元数据后动态渲染条件编辑器：字段下拉 → 操作符下拉 → 值输入控件，完全数据驱动。

---

## 7. 数据迁移

### 7.1 迁移策略

一次性自动迁移，在应用启动时通过 `ApplicationRunner` 执行（与现有 `DatabaseMigrationService` 同模式）：

```java
@Component
@RequiredArgsConstructor
@Order(2)
public class PricingRuleMigrationService implements ApplicationRunner {

    private final PlatformModelRepository modelRepository;
    private final PlatformModelPriceTierRepository tierRepository;
    private final PricingRuleRepository ruleRepository;
    private final BillingDimensionRepository dimensionRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        // 1. 检查是否已迁移（幂等）
        if (ruleRepository.count() > 0) return;

        // 2. 初始化默认维度（六维 + 折扣）
        initDefaultDimensions();

        // 3. 迁移整体计费数据
        migrateOverallPricing();

        // 4. 迁移阶梯计费数据
        migrateTieredPricing();
    }
}
```

### 7.2 初始化默认维度

```java
private void initDefaultDimensions() {
    if (dimensionRepository.count() > 0) return;

    String[][] defaults = {
        // group_key, dimension_key, display_name, unit, value_type, sort_order
        {"text", "normalPrice",  "普通输入（未命中缓存）", "元 / 百万 Token", "price", 1},
        {"text", "output",       "普通输出",              "元 / 百万 Token", "price", 2},
        {"text", "cacheHit",      "缓存命中输入",          "元 / 百万 Token", "price", 3},
        {"text", "cacheCreate",   "显式缓存创建",          "元 / 百万 Token", "price", 4},
        {"text", "cacheHitExplicit", "显式缓存命中",      "元 / 百万 Token", "price", 5},
        {"text", "thinking",     "思考Token",            "元 / 百万 Token", "price", 6},
        {"text", "discount",     "折扣",                 "%",              "discount", 99},
        // voice / image 组初始与 text 相同（后续可独立修改）
    };
    // ... 批量插入，voice 和 image 组复制 text 组维度
}
```

### 7.3 迁移整体计费数据

```java
private void migrateOverallPricing() {
    // billingMode=1 的模型：创建 1 条无条件兜底规则
    List<PlatformModelEntity> models = modelRepository.findAll().stream()
            .filter(m -> !Boolean.TRUE.equals(m.getDeleted()))
            .filter(m -> Integer.valueOf(1).equals(m.getBillingMode()))
            .toList();

    for (PlatformModelEntity m : models) {
        String priceJson = buildPriceJson(m);  // 把六列转成 price_json
        String matchJson = """{"operator":"AND","conditions":[]}"""; // 无条件

        PricingRuleEntity rule = new PricingRuleEntity();
        rule.setModelId(m.getId());
        rule.setRuleName("整体兜底");
        rule.setMatchJson(matchJson);
        rule.setPriceJson(priceJson);
        rule.setPriority(100);
        rule.setEnabled(true);
        ruleRepository.save(rule);
    }
}
```

### 7.4 迁移阶梯计费数据

```java
private void migrateTieredPricing() {
    // billingMode=2 的模型：每个档位创建 1 条 tokenCount 区间规则
    List<PlatformModelEntity> models = modelRepository.findAll().stream()
            .filter(m -> !Boolean.TRUE.equals(m.getDeleted()))
            .filter(m -> Integer.valueOf(2).equals(m.getBillingMode()))
            .toList();

    for (PlatformModelEntity m : models) {
        List<PlatformModelPriceTierEntity> tiers = tierRepository.findByModelIdOrderByTierOrderAsc(m.getId());
        int priority = 100;
        for (PlatformModelPriceTierEntity tier : tiers) {
            // 上下限 K → token：lower * 1000, upper * 1000
            BigDecimal lowerToken = tier.getTierLowerLimit() != null
                    ? tier.getTierLowerLimit().multiply(new BigDecimal("1000")) : BigDecimal.ZERO;
            BigDecimal upperToken = tier.getTierUpperLimit() != null
                    ? tier.getTierUpperLimit().multiply(new BigDecimal("1000")) : null;

            // 构建条件树
            String matchJson = buildTokenTierMatchJson(lowerToken, upperToken, tier.getIsUnlimited());
            String priceJson = buildTierPriceJson(tier);

            PricingRuleEntity rule = new PricingRuleEntity();
            rule.setModelId(m.getId());
            rule.setRuleName("档位" + tier.getTierOrder());
            rule.setMatchJson(matchJson);
            rule.setPriceJson(priceJson);
            rule.setPriority(priority++);
            rule.setEnabled(true);
            ruleRepository.save(rule);
        }
    }
}
```

### 7.5 迁移后

- 老表（`ai_model` 六列、`ai_model_price_tier`、`billing_dimension_alias`）**保留不删**，数据仍在
- 迁移后 `billing_mode` **不改**（1=整体、2=阶梯），仍走老逻辑
- 管理员在前端把模型切换到"规则计费"（`billingMode=3`）后，才开始走新引擎
- 迁移是**预填**规则数据，管理员可以查看已有规则并在此基础上修改，而不是强制切换

---

## 8. 文件清单

### 8.1 新增文件

| 文件 | 说明 |
|---|---|
| `billing/rule/UsageContext.java` | 计费上下文 |
| `billing/rule/ConditionTreeEvaluator.java` | 条件树评估器（通用，不修改） |
| `billing/rule/PricingRuleEngine.java` | 规则匹配引擎 |
| `billing/rule/PricingRuleService.java` | 规则管理服务（CRUD + 优先级调整） |
| `billing/rule/DimensionService.java` | 维度管理服务（CRUD） |
| `billing/rule/PricingRuleController.java` | 规则管理 API |
| `billing/rule/BillingDimensionController.java` | 维度管理 API |
| `billing/rule/PricingContextController.java` | 上下文字段元数据 API |
| `persistence/jpa/entity/PricingRuleEntity.java` | 规则实体 |
| `persistence/jpa/entity/BillingDimensionEntity.java` | 维度实体 |
| `persistence/jpa/repository/PricingRuleRepository.java` | 规则 Repository |
| `persistence/jpa/repository/BillingDimensionRepository.java` | 维度 Repository |
| `persistence/jpa/PricingRuleMigrationService.java` | 数据迁移 |
| `db/migration/postgresql/V15__pricing_rule_engine.sql` | 建表脚本 |

### 8.2 修改文件

| 文件 | 改动 |
|---|---|
| `billing/BillingService.java` | `recordBilling()` 新增 `billingMode=3` 分流分支 |
| `billing/ModelPricingService.java` | `ModelPricing` 加 `rules` 字段；`hasUsablePricing` 加 `case 3` |
| `billing/pricing/PricingAdminDtos.java` | `PricingSave` 加 `billingMode=3` 支持 |
| `billing/pricing/PricingAdminService.java` | `save()` 支持 `billingMode=3` |
| `platform/sync/PlatformDataSyncService.java` | `syncAllPricings()` 加载规则数据 |
| `billing/pricing/BillingDimensionAliasService.java` | 废弃或改为 `DimensionService` 的适配层 |

---

## 9. 不做的事

- 前端 UI 不在本次范围
- `isHoliday` 字段不实现
- 老表数据不删除、老代码不删除（新旧并存）
- 视频模型（vidGen）不纳入规则引擎（仍走现有视频计费逻辑）
- 表达式引擎（SpEL/Aviator）不采用

---

## 10. 风险与应对

| 风险 | 应对 |
|---|---|
| 条件树 JSON 解析异常 | 单条规则异常不阻断，记日志后跳过继续匹配下一条 |
| 规则全不命中 | 走 `hasUsablePricing` 拦截（`billingMode=3` 需至少 1 条启用规则才算可用） |
| 性能：每请求查规则表 | 规则数据随 `ModelPricing` 一起进内存缓存，不查库 |
| 迁移幂等 | `ruleRepository.count() > 0` 跳过迁移 |
| 新旧并存歧义 | `billingMode` 是唯一开关：1/2 走老逻辑，3 走新引擎，无歧义 |

---

## 11. 后续扩展路径

| 需求 | 做法 | 改后端代码？ |
|---|---|---|
| 新增分档维度（如 `userType`） | `UsageContext.get()` 加一个 case | 加一行 |
| 新增操作符（如 `contains`） | `ConditionTreeEvaluator.compare()` 加一个 case | 加一行 |
| 新增计费维度 | `ai_billing_dimension` 表加一行 | 不改代码 |
| 新增规则类型 | 前端组合条件树 | 不改代码 |
| 节假日分档 | `UsageContext` 加 `isHoliday` getter + 节假日数据源 | 加一个 getter |

后端引擎写完后，后续扩展绝大多数只需加数据或加一行 getter，不需要改核心逻辑。
