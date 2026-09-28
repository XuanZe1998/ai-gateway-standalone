# 流式 chat 请求 stream_options 自动注入设计

## 1. 背景与问题

JAiRouter 作为 OpenAI 兼容的 LLM API 聚合网关，从 `UniversalController.chatCompletions()` 接入流式请求后，会经 `BaseAdapter` → `StreamingRequestProcessor` 代理到上游实例。

当前 `ChatDTO.Request` 未定义 `stream_options` 字段，`NormalOpenAiAdapter` 构造下游请求时也不会透传该参数。对于**火山方舟（Volcengine ARK）**等平台的 chat 模型，其流式响应默认不返回 `usage`；只有显式在请求中设置 `stream_options.include_usage=true` 时，才会在 `data: [DONE]` 之前额外返回一个 `usage` chunk。因此当前链路出现：

- 流式响应没有 `usage`；
- `StreamingRequestProcessor` 捕获到的 token 数为 0；
- 异步计费记录 `totalCost=0`，实际未产生扣费。

> 注意：`StreamingRequestProcessor.captureUsageFromChunk` 已具备从 chunk 的 `usage` 字段提取 prompt/completion/total tokens 的能力，`choices` 为空数组不影响提取。

## 2. 目标与非目标

### 2.1 目标

- 对指定平台（火山方舟及后续同类平台）的流式 chat 请求，自动向下游注入 `stream_options.include_usage=true`；
- 保持非流式请求、非 chat 请求、其他平台请求完全不变；
- 新增平台支持时，尽量只改配置，少改代码；
- 不影响现有熔断、限流、重试、计费、预警等核心链路。

### 2.2 非目标

- 不提供本地 token 估算（tiktoken 等）作为 usage 缺失时的兜底；
- 不修改 `ChatDTO.Request` 暴露 `stream_options` 给客户端（本期不透传，网关统一控制）；
- 不动 `ai_channel` / `ai_model` 等算力平台表结构；
- 不强制所有平台都注入 `stream_options`（避免把不支持该参数的平台打挂）。

## 3. 方案概述

采用**配置驱动的 URL 匹配**方案：

1. 在 `application.yml` 中定义需要注入 `stream_options` 的实例 baseUrl 正则列表，以及注入的具体选项；
2. `StreamingRequestProcessor` 在真正发起 WebClient 请求前，判断当前实例 baseUrl 是否命中配置；
3. 命中且为 chat 流式请求时，在请求体中注入 `stream_options.include_usage=true`；
4. 上游响应返回 usage 后，由现有的 `captureUsageFromChunk` 捕获并走现有计费链路。

该方案兼顾“本周快速闭环火山方舟”和“后续低成本的扩展”。

## 4. 配置设计

```yaml
jairouter:
  adapter:
    stream-usage-injection:
      # 总开关，默认 false，避免未配置时产生意外行为
      enabled: true
      # 需要注入的实例 baseUrl 正则列表（不区分大小写匹配）
      patterns:
        - ".*ark\\.cn-beijing\\.volces\\.com.*"
        # 后续新平台只需追加一行，如：
        # - ".*some-other-cloud\\.com.*"
      # 注入到 stream_options 里的字段
      options:
        include_usage: true
        chunk_include_usage: false
```

- `enabled`：总开关，默认 `false`。生产环境确认无误后再开启。
- `patterns`：正则列表，与 `ModelInstance.baseUrl` 做不区分大小写匹配。
- `options`：需要注入的字段。本期只注入 `include_usage=true`；`chunk_include_usage` 保留为 `false`，因为 `include_usage` 已能满足计费需求，且避免每个 chunk 都带 usage 增加响应体积。

## 5. 代码变更

### 5.1 新增配置绑定类

新增 `org.unreal.modelrouter.router.adapter.config.StreamUsageInjectionProperties`：

- 字段：`boolean enabled`、`List<String> patterns`、`Map<String, Object> options`
- 使用 `@ConfigurationProperties(prefix = "jairouter.adapter.stream-usage-injection")`
- 提供 `matches(String baseUrl)` 方法做正则匹配

### 5.2 StreamingRequestProcessor 改造

在 `processStreamingRequest` 中，调用 `requestSpec.bodyValue(request)` 之前：

1. 判断 `serviceType == ServiceType.chat`；
2. 判断请求体表示的是流式请求（ObjectNode/Map 中 `stream == true`）；
3. 调用 `StreamUsageInjectionProperties.matches(selectedInstance.getBaseUrl())`；
4. 命中时，在请求体上设置 `stream_options` 节点/字段；
5. 保持原 `requestSpec.bodyValue(request)` 不变。

> 实现上新增一个私有方法 `injectStreamOptionsIfNeeded(Object request, ServiceType serviceType, ModelInstance instance)`，由 `processStreamingRequest` 调用。

### 5.3 请求体类型处理

`StreamingRequestProcessor` 收到的 `request` 经过 `BaseAdapter.transformRequest` 后，对于 chat 请求通常是 `com.fasterxml.jackson.databind.node.ObjectNode`。处理策略：

- `ObjectNode`：直接 `set("stream_options", optionsNode)`；
- `Map<String, Object>`： put `stream_options`；
- 其他类型：使用 `objectMapper.valueToTree` 转为 `JsonNode` 后注入，再转回 Object；如果转换失败，记录 warn 日志并不注入，避免阻断主链路。

### 5.4 不改动 BaseAdapter 核心链路

- `BaseAdapter.processRequest` 的余额校验、实例选择、重试逻辑保持不变；
- `BaseAdapter.processStreamingRequest` 只增加一行调用 `injectStreamOptionsIfNeeded` 的入口，不改变返回类型和异常路径；
- `StreamingRequestProcessor` 原有的 `doOnComplete` / `doOnError` 计费逻辑保持不变。

## 6. 数据流

```
客户端 POST /api/v1/chat/completions (stream=true)
  → UniversalController.chatCompletions()
    → BaseAdapter.chat()
      → BaseAdapter.processRequest()
        → 余额校验 / 实例选择
        → BaseAdapter.processStreamingRequest()
          → transformRequest(request) 得到 ObjectNode
          → injectStreamOptionsIfNeeded(ObjectNode, chat, selectedInstance)
            → baseUrl 命中 pattern
            → ObjectNode.set("stream_options", {include_usage: true})
          → StreamingRequestProcessor.processStreamingRequest(transformedRequest, ...)
            → WebClient POST 到 ark.cn-beijing.volces.com/api/v3/chat/completions
            → Flux<String> 接收 SSE chunk
              → 中间 chunk：可能无 usage
              → [DONE] 前额外 chunk：{usage: {...}, choices: []}
            → captureUsageFromChunk 捕获最终 usage
            → doOnComplete → recordStreamingTokenUsage → BillingService.recordBilling
```

## 7. 兼容性 & 风险控制

| 风险 | 控制措施 |
|---|---|
| 不支持 `stream_options` 的平台被注入后返回 400 | 只有 `patterns` 命中的实例才会注入，默认总开关 `enabled=false` |
| 误匹配导致非目标平台被注入 | `patterns` 使用精确正则（如 `ark\.cn-beijing\.volces\.com`），并在测试环境验证 |
| 请求体类型不是 ObjectNode/Map，注入失败 | 兜底处理：转换失败只记录 warn，继续按原请求发送 |
| 注入逻辑影响非 chat / 非流式请求 | 在 `injectStreamOptionsIfNeeded` 中严格判断 `serviceType == chat` 且 `stream == true` |
| 计费链路被重复触发 | 复用现有 `AtomicBoolean billingRecorded`，不新增计费点 |
| 响应格式被改变影响客户端 | `StreamingRequestProcessor` 只注入上游请求参数，不改变向下游返回的 SSE chunk 格式 |

## 8. 测试策略

### 8.1 单元测试

- `StreamUsageInjectionProperties.matches()`：测试正则命中/未命中、大小写不敏感、空列表。
- `StreamingRequestProcessor` 注入逻辑：
  - 命中 pattern + stream=true → 请求体包含 `stream_options.include_usage=true`；
  - 命中 pattern + stream=false → 不注入；
  - 未命中 pattern + stream=true → 不注入；
  - enabled=false → 不注入；
  - 请求体为不可识别类型 → 不抛异常。

### 8.2 集成测试

- 使用 WireMock / MockWebServer 模拟火山方舟 SSE 响应：
  - 请求体带 `stream_options.include_usage=true`；
  - 返回 `[DONE]` 前 usage chunk；
  - 验证 `BillingService.recordBilling` 被调用且 tokens > 0。

### 8.3 回归测试

- 非流式 chat 请求 usage 不受影响；
- 其他 adapter（ollama/vllm 等）流式请求不受影响；
- 不匹配的 baseUrl 的 normal adapter 实例不受影响。

## 9. 回滚方案

- 配置回滚：将 `jairouter.adapter.stream-usage-injection.enabled` 设为 `false`，重启或等待配置刷新后生效；
- 代码回滚：revert 本次提交的 3 个文件（配置类 + processor 改动 + 配置 YAML）；
- 灰度：先在 test 环境开启，验证无误后再在 prod 开启。

## 10. 后续演进

- 若算力平台侧后续支持在 `ai_channel` 表扩展字段（如 `stream_options_policy`），可将配置下沉到 DB，实现实例级精细化管理；
- 若平台支持 `chunk_include_usage`，可通过调整 `options.chunk_include_usage` 开启，无需改代码。
