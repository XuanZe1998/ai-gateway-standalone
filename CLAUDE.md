# CLAUDE.md — JAiRouter 项目文档

<!-- 版本信息 -->
> **文档版本**: 1.0.0  
> **最后更新**: 2026-09-28  
> **Git 提交**: 05931b92  
> **作者**: XuanZe1998
<!-- /版本信息 -->



## 项目概览

JAiRouter（ModelRouter）：Spring Boot 3.5.5 + WebFlux + JDK17 的 **LLM API 聚合网关**，兼容 OpenAI API。
- 单 JAR 部署，PostgreSQL 持久化，与算力平台共用 PG（作为中转代理独立部署）
- 核心：接收 OpenAI/Anthropic 格式请求 → 选实例 → 代理转发（支持 SSE 流式）→ 计费

## 构建（开发机专用）

系统 `mvn` 和项目 `mvnw` 均已损坏，**必须用 classworlds launcher 直调**：

```bash
M2="D:/soft/apache-maven-3.6.3" && JAVA_HOME="/c/Program Files/Java/jdk-17.0.1" && \
"$JAVA_HOME/bin/java" -classpath "$M2/boot/plexus-classworlds-2.6.0.jar" \
  -Dclassworlds.conf="$M2/bin/m2.conf" -Dmaven.home="$M2" \
  -Dmaven.multiModuleProjectDirectory="D:/workspace/lm_aggregation_platform_server" \
  org.codehaus.plexus.classworlds.launcher.Launcher test-compile \
  -s "$M2/conf/settings.xml" -Dcheckstyle.skip=true -Dspotbugs.skip=true
```

- **验证至少跑 `test-compile`**（不只 `compile`），否则测试代码编译错误发现不了
- 跳过 checkstyle/spotbugs（项目有大量既有告警）
- 运行：`java -jar target/model-router-*.jar --spring.profiles.active=dev|test|prod`

## 包结构

```
org.unreal.modelrouter
├── auth/         # 认证：API Key（本地+算力平台）/ JWT，UserIdentity Reactor Context 传播
├── billing/      # 计费：六维分项计费 + 阶梯计费 + 免费额度 + 余额扣减预警
│   └── usage/    # TokenUsage 归一化层（TokenUsage/UsageStyle/VendorUsageStyleResolver/TokenUsageExtractor）
├── config/       # 实例 CRUD、配置同步
├── monitor/      # OpenTelemetry 链路追踪
├── persistence/  # JPA 实体/仓库
└── router/       # 路由、适配器、负载均衡、熔断限流、协议透传
```

## 对外接口与请求链路

| 端点 | 说明 |
|---|---|
| `/api/v1/chat/completions` | **OpenAI 原生协议透传**（ProtocolGatewayController），当前主链路 |
| `/api/v1/messages` | Anthropic 协议端点，**已临时下线**（@PostMapping 注释，返回 404；底层转换链路保留） |
| `/api/v1/chat/internalCompletions` | 旧信封端点（UniversalController），内部保留 |
| `/api/v1/embeddings` `/rerank` `/audio/*` `/images/*` | 其余服务类型走 UniversalController |
| `/internal/refresh` `/internal/refresh/platform` | 配置/平台数据刷新，需 HMAC 签名 |

### 透传主链路（/chat/completions）

```
HTTP POST → CorsWebFilter → SpringSecurityAuthenticationFilter（API Key/JWT → UserIdentity）
  → ProtocolGatewayController → ProtocolPassthroughService
    → ModelServiceRegistry.selectInstance（模型名→状态→健康→熔断→限流→负载均衡）
    → BalanceCheckService（实名 403 / 免费额度放行 / 余额 ≤0 返回 402）
    → TokenUsageExtractor 归一化 usage
    → 流式：Sinks.Many 桥接（上游独立 subscribe，客户端断连不影响计费）
    → 非流式：exchangeToMono 原样透传
    → 完成后：BillingService.recordBilling @Async（六维计费 → 折扣 → 落表 → 扣余额 → 预警）
```

### 流式计费可靠性（重要）

两条链路（StreamingRequestProcessor / ProtocolPassthroughService.doStreaming）均采用 **Sinks.Many 桥接**模式：
- 上游 `bodyToFlux` 独立 `.subscribe()`（fire-and-forget），客户端断连不反传杀上游
- 上游始终读完整条流 → 末尾 usage chunk 不丢 → 计费不漏单
- `doOnError` 仅处理上游真实错误；客户端断开按已捕获 usage 正常计费，无 usage 则记失败账单

## 计费系统（六维分项计费）

### 归一化层（billing/usage/）

厂商 usage 语义不一致是资损根因（DeepSeek/OpenAI/国产 `prompt_tokens` 含缓存命中 vs Anthropic `input_tokens` 为净量）。归一化层统一为 6 维净量：

| 维度 | 含义 |
|---|---|
| `normalInput` | 普通输入（缓存未命中净量） |
| `cacheHit` | 隐式缓存命中（DeepSeek/OpenAI `prompt_cache_hit_tokens`/`cached_tokens`） |
| `cacheCreateExplicit` | 显式缓存创建（Anthropic `cache_creation_input_tokens`） |
| `cacheHitExplicit` | 显式缓存命中（Anthropic `cache_read_input_tokens`） |
| `normalOutput` | 普通输出（= 输出总量 − 思考） |
| `thinking` | 思考 token（`completion_tokens_details.reasoning_tokens`，**根级兜底**：嵌套为 0 时回退读 usage 根级 `reasoning_tokens`，覆盖 kimi 等国产厂商） |

- `TokenUsageExtractor` 按 `UsageStyle`（由 `VendorUsageStyleResolver` 根据 vendor/baseUrl 判定）归一化；UNKNOWN 厂商同时尝试两种缓存字段，缺失置 0，漏提并入普通输入按全价计（平台不亏）
- `billableTotal()` = 6 维之和，用于免费额度扣减；`legacyPromptTokens()` = 输入侧 4 维之和，用于阶梯档位匹配和旧 `prompt_tokens` 列
- Anthropic 无 `total_tokens` 字段，rawTotal 兜底补上 cache_creation/cache_read（防对账失真）
- 3 处计费调用点（BaseAdapter 非流式 / StreamingRequestProcessor 流式 / ProtocolPassthroughService 透传）共用同一 Extractor + 同一 BillingService，两条对话链路计费统一

### 计费公式（BillingService.buildRecord）

1. **价格解析**：`billing_mode=1` 整体价读主表；`=2` 阶梯按 `legacyPromptTokens` 匹配 `ai_model_price_tier` 档位（档位 K 值已在同步层 ×1000 转 token），5 个价格字段读命中档位；主表价格单位元/M token，同步时 ÷1e6
2. **enable 归并（防资损）**：未启用的维度**归并到已启用的同类维度**，不直接置 0——上游返回了 token 就必须计费：
   - 输入侧：`enableCacheHitInput/enableCacheCreateInput/enableCacheHitExplicitInput` 为 false 时，对应 token 累加进 `normalInput` 按普通输入价计费
   - `enableInputToken=false` → 输入侧全置 0；`enableOutputToken=false` → normalOutput+thinking 全置 0
3. **思考模式**：`thinking_billing_mode=1` 并入输出按 outputPrice；`=2` 单独按 thinkingPrice（阶梯时取档位 thinkingPrice）；`=3` 不计费置 0
4. **六维费用** = Σ(各维度 token × 对应价格)
5. **折扣**：`finalCost = originalCost × 模型折扣 × 用户×模型折扣 × 企业补贴折扣`（DiscountCalculationService，防弹：查询失败/负折扣降级为无折扣）
6. **落表**：`ai_billing_record` 记录归并后 6 维 token + 价格快照 + billing_mode/thinking_billing_mode/vendor + 6 分项费用 + 折扣快照
7. **后续**：命中免费额度 total_cost=0；否则按折后金额扣 `ai_account_balance` 余额（允许扣负）+ 下穿阈值线预警短信

### 关键纪律

- **pricing 缓存**：`ModelPricingService` @PostConstruct 全量加载 + 按需同步（仅 pricing==null 时）；**算力平台改模型计费配置后必须调 `/internal/refresh` 或重启**，否则旧快照生效
- 平台侧配置纪律：档位价格置 NULL ⟺ 对应 enable 关闭，否则该维度按 0 元计费漏收

### 余额与预警

- 请求前 `BalanceCheckService`：实名认证（user_type=1 需 verify_status=4；user_type=2 需 verify_status=2，否则 403）→ 文本类免费额度 >0 放行 → `ai_account_balance` 余额 ≤0 返回 402（account_id=company_id/user_id，account_type=1/2）
- 计费后 `BalanceDeductionService`：无条件扣减（允许负），原生查询重读真实余额，基于"下穿阈值线"事件发短信（`last_warn_threshold` 状态位防重复；充值回正后再次下穿会重新预警）
- 免费额度：`ai_user_free_quota` 按 user_id，chat/embedding/rerank 默认 100 万 token，响应完成后按 `billableTotal` 扣减，超支扣光并锁 `trial_exhausted`（不截断响应）

## 认证链路

- 传入方式：`X-API-Key: <key>` 优先，`Authorization: Bearer <key>` 兜底
- `CustomReactiveAuthenticationManager`：先本地 ApiKeyService → 失败走 `PlatformAuthCache`（正缓存 60s 携带 expire_time 校验/负缓存 30s）→ 未命中回退 `PlatformDataSyncService.lookupApiKey()`（AES/ECB 解密 key_hash 比对 + 实名校验 + 企业关联）
- **key 禁用/删除/编辑后必须调 `/internal/refresh` 清缓存**（多实例需每台刷新）
- 注意：`ai_api_key.key_hash` 实为 AES 密文非哈希

## 多环境

| | dev | test | prod |
|---|---|---|---|
| PG schema | sldd-dev | sldd-test | sldd-prod |
| 日志 | DEBUG | DEBUG/INFO | WARN |
| Swagger | ✅ | ✅ | ❌ |
| 响应脱敏 | ❌ | ❌ | ✅ |

配置优先级：环境变量 > application-{profile}.yml > application.yml > classpath:config/**/*.yml

## 服务类型

`chat`, `embedding`, `rerank`, `tts`, `stt`, `imgGen`, `imgEdit`, `vidGen`；算力平台 modelType 数字编码 1-6 映射由 `ServiceTypeResolver`/`PlatformDataSyncService` 处理。实例 cache key 统一连字符格式。

## 关键组件速查

| 组件 | 职责 |
|---|---|
| `ProtocolGatewayController` / `ProtocolPassthroughService` | 协议纯净透传（OpenAI 透传；Anthropic 双向转换——Converter/StreamTranslator，当前已下线入口） |
| `UniversalController` / `BaseAdapter` | 旧信封链路；BaseAdapter 子类扩展依赖时用 `ApplicationContextProvider.getBean()` 避免改全部 6 个 impl |
| `ModelServiceRegistry` | 实例缓存 + selectInstance 路由（模型名→状态→健康→熔断→限流→负载均衡） |
| `StreamingRequestProcessor` / `NonStreamingRequestProcessor` | 旧链路流式/非流式处理；流式按 `jairouter.adapter.stream-usage-injection` 注入 `stream_options.include_usage=true` |
| `PlatformDataSyncService` | 算力平台数据同步（模型/渠道/API Key/定价）；阶梯模型过滤用 `hasUsablePricing`（billingMode=2 主表价格可空，不被过滤） |
| `ModelPricingService` | 定价缓存（整体/阶梯、5 价 5 enable、thinking 模式） |
| `BillingService` | 六维计费 + 折扣 + 落表 + 触发扣款 |
| `BalanceCheckService` / `BalanceDeductionService` / `FreeQuotaService` | 请求前校验 / 计费后扣减+预警 / 免费额度 |
| `ServerChecker` / `ServiceStateManager` | 30s TCP 健康探测；配置刷新时 `resetAllHealthStatus()` 防新增实例继承旧不健康状态 |
| `InternalRefreshController` | `/internal/refresh*`：离线构建新 cache 原子替换；清认证缓存 + 重置熔断器 + 重置健康状态 |

## 重试与容错

- `RetryPolicy` 仅对超时/连接异常等基础设施错误重试；4xx 业务错误（402/403）直接透传不重试
- 上游 4xx/5xx 错误体按协议格式透传；仅基础设施故障触发 fallback
- WebFilter 顺序：CachedBody → CORS → Tracing → Security → Sanitization

## 当前状态

- 计费系统：六维分项计费 + 阶梯计费 + 思考 token 差异化定价已上线（billing_mode/thinking_billing_mode/enable 标志位由算力平台 `ai_model` + `ai_model_price_tier` 配置）
- Anthropic 透传端点已临时下线（恢复：取消 ProtocolGatewayController 中 @PostMapping 注释）
- 依赖算力平台 DDL：`ai_model` +11 计费列、`ai_model_price_tier` 表（含 thinking_price）、`ai_billing_record` +19 列；存量迁移 billing_mode=1/enable 默认/thinking_billing_mode=1
- 历史修复细节见 git log 与 `.qwen/PROJECT_SUMMARY.md`（超大类重构进行中）
