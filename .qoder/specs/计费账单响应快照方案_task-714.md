# 计费账单新增 model_id 与模型返回响应快照——最终方案

## 一、modelId 字段：相关改动点全清单

### 1.1 现状核查：modelId 数据源已就绪，改动面收敛在 BillingService

- `ModelPricingService.ModelPricing` 已含 `modelId` 字段（ModelPricingService.java L97），其值由 `PlatformDataSyncService.buildPricing` 传入 `m.getId()`（平台侧 `ai_model.id`，L213），定价缓存每次刷新/按需同步即已携带；
- `BillingService` 的两条落库路径 `buildRecord`（六维）与 `buildVideoRecord`（视频）在方法体内**已持有 `pricing` 对象**，modelId 可直接从 `pricing.getModelId()` 取；
- 结论：**无需改 `BillingContext`，无需改四个调用点**（ProtocolPassthroughService / VideoTaskSettler / BaseAdapter / StreamingRequestProcessor），modelId 的采集与落库全部收敛在 BillingService 两条 build 路径，改动面最小且四条链路天然一致。

### 1.2 改动点清单

| # | 位置 | 改动 |
|---|---|---|
| 1 | DDL（V5 脚本） | `ai_billing_record.model_id BIGINT` 可空 + `COMMENT ON COLUMN` + `CREATE INDEX IF NOT EXISTS idx_billing_model_id` + 回滚（默认注释） |
| 2 | BillingRecordEntity.java | 模型维度区新增 `modelId` 字段（`@Column(name = "model_id")`，Long，可空） |
| 3 | BillingService.java `buildRecord`（六维） | builder 链 `.modelId(pricing != null ? pricing.getModelId() : null)` |
| 4 | 同文件 `buildVideoRecord`（视频） | builder 链 `.modelId(pricing.getModelId())`（视频链路 pricing 必非 null，否则已拒计费） |
| 5 | 平台侧 | **零改动**（`ai_model.id` 已随定价缓存同步） |
| 6 | 测试 | `BillingServiceTest` 增加 modelId 落库/兜底断言 |

边界与口径：
- 六维链路定价未命中时（pricing 为 null，按 0 元落账的历史兜底行为），`model_id` 为 NULL——对账可退化用 `model_name`，`model_name` 列保留不动；
- 历史账单 `model_id` 全 NULL，不做回填（历史定价已不可考），查询兼容；
- 索引 `idx_billing_model_id` 支持「按模型主键精确关联模型表」的对账/统计查询；现有 `idx_billing_model`（model_name）保留。

```java
// BillingRecordEntity 模型维度区新增
/** 模型主键（算力平台 ai_model.id 快照，落账时取自定价缓存；定价未命中或历史数据为 NULL，可退化为 model_name 关联） */
@Column(name = "model_id")
private Long modelId;
```

```java
// BillingService.buildRecord（六维路径）builder 链
.modelName(ctx.getModelName())
.modelId(pricing != null ? pricing.getModelId() : null)
.serviceType(ctx.getServiceType())

// BillingService.buildVideoRecord（视频路径）builder 链
.modelName(ctx.getModelName())
.modelId(pricing.getModelId())
.serviceType(ctx.getServiceType())
```

## 二、响应快照：最终方案

### 2.1 表规模评估：加列 vs 新表（决策：加列，不开新表）

- **现状字段数**：`BillingRecordEntity` 映射 63 列（62 + V4 新增 usageDetail JSONB）；本次再加 `model_id` 与 `response_snapshot` 共 **65 列**，仅占 PostgreSQL 单表列数硬上限 1600 的约 4%；
- **行宽视角**：现有列全是窄列（BIGINT/DECIMAL/VARCHAR 短列），静态行宽约 1~1.5KB；新增 `response_snapshot TEXT` 由 PG TOAST 自动管理（压缩后超约 2KB 阈值外置，主表只存指针），不挤占主表行宽，且对账/统计查询不 SELECT 该列，不影响扫描性能；
- **不拆新表理由**：快照与账单严格 1:1、同生命周期（append-only 事实表，一起归档/清理），拆表无独立维度价值；拆表反而引入两链路 1:1 写入一致性问题（异步 fire-and-forget 下需事务或补偿），对账从单行 SELECT 退化为 join；8KB 截断后体积可控；
- **拆表触发条件（预留，本期不做）**：需求升级为「完整原始响应存档、不截断」（几十 KB~MB 级）、一账单多快照、或快照保留周期与账单不同时，再开 `ai_billing_snapshot` 或对象存储；
- **「流式很大塞不下」澄清**：PG TEXT 上限 1GB，「塞不下」不存在；本方案**刻意不存流式全文**（几万 token 全文对账价值为零，计费依据是 usage），流式只捕获携带 usage 的尾部 chunk（body 预算 4KB、快照总预算 8KB）；完整内容存档是留档表职责（`ai_video_task` 已有先例），不是账单表职责。

### 2.2 V5 迁移脚本（model_id + response_snapshot 合并，遵循 DDL 规范：仅正向 + 回滚）

版本号排定：V1（免费额度）、V2（六维）、V3（视频生成）、V4（视频计费元数据）已占用，下一版本为 **V5**。两列同属 `ai_billing_record` 变更，合并为一个脚本（延续 V3 合并惯例）。新建 `src/main/resources/db/scripts/V5__ai_billing_record_model_id_and_response_snapshot.sql`：

```sql
-- ============================================================
-- 计费表 ai_billing_record 新增模型主键与模型返回响应快照列
-- 版本：V5
-- 背景：1) 对账/统计需要按模型主键（算力平台 ai_model.id）精确关联
--          模型表，避免用 model_name 字符串匹配；
--       2) 对账与问题排查需要从账单直接追溯上游返回内容
--          （协议/HTTP 状态/错误码/错误信息/usage/截断响应体），
--          两条计费链路（透传异步 + 视频同步）统一按
--          billing_record_id 单行追溯。
-- 说明：model_id 取自定价缓存（定价未命中/历史数据为 NULL，退化为
--       model_name 关联）；response_snapshot 为 JSON 结构，应用层
--       截断至 8KB 且 base64 素材已脱敏，旧数据与构建失败为 NULL。
-- 执行：金仓 KingbaseES（PG 兼容模式）/ PostgreSQL，幂等
--       （IF NOT EXISTS），可重复执行，手工执行。
-- ★★★ 上线顺序强约束 ★★★：本脚本必须先于网关新版本部署执行
--      （JPA 实体映射了数据库中不存在的列会导致落账 SQL 报错）。
-- ============================================================

-- ---------- 一、新增 model_id 列（模型主键快照，可空） ----------
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS model_id BIGINT;

COMMENT ON COLUMN ai_billing_record.model_id IS '模型主键（算力平台 ai_model.id 快照，落账时取自定价缓存；定价未命中或历史数据为 NULL，可退化为 model_name 关联）';

-- ---------- 二、新增 response_snapshot 列（模型返回响应快照，可空） ----------
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS response_snapshot TEXT;

COMMENT ON COLUMN ai_billing_record.response_snapshot IS '模型返回响应快照（JSON，截断至 8KB，base64 素材已脱敏；含协议/HTTP状态/错误码/错误信息/usage 与截断响应体；旧数据与构建失败为 NULL）';

-- ---------- 三、模型主键索引（按模型维度对账/统计） ----------
CREATE INDEX IF NOT EXISTS idx_billing_model_id ON ai_billing_record (model_id);

-- ============================================================
-- 回滚 SQL（默认注释；如需撤销本次变更，取消注释后按正向逆序执行）
-- ============================================================

-- 三的回滚：删除模型主键索引
-- DROP INDEX IF EXISTS idx_billing_model_id;

-- 二的回滚：删除响应快照列
-- ALTER TABLE ai_billing_record DROP COLUMN IF EXISTS response_snapshot;

-- 一的回滚：删除模型主键列
-- ALTER TABLE ai_billing_record DROP COLUMN IF EXISTS model_id;
```

设计要点：
- **列类型 TEXT、可空**：与 `ai_video_task` 快照列一致；旧行全 NULL 即向后兼容，不写回填 DML；
- **8KB 截断**：对齐 `VideoTaskArchiver.MAX_RESPONSE_SNAPSHOT = 8192` 惯例，8KB 足以容纳完整 status/usage/error 与响应体首部，且把高频大表单行体积控制住；
- **脱敏**：复用 `VideoTaskArchiver.stripBase64Data` 处理方式——`data:...;base64,...` 替换为 `data:...;<base64 truncated, original length N>`，公网 URL 原样保留（多模态图片输出同样脱敏）；
- **快照列 vs 外键**：chat 链路无留档表可关联；账单自带快照使对账「自包含」（按 billing_record_id 单行 SELECT），chat/vidGen 口径统一；视频链路已有任务侧外键 `ai_video_task.billing_record_id`，不动；
- **与 usage_detail 的关系**：V4 的 `usage_detail JSONB` 是视频模型「费用明细」（分辨率/秒数/费用），`response_snapshot` 是「上游原始返回」（状态/用量/响应体），互补不冲突；
- **不动 `ai_video_task` 表**：任务表快照服务于查询直出 + 任务追溯（上限 65535），计费表快照服务于账单对账（≤8KB），同源不同用途，经 billing_record_id 双向可查。

### 2.3 快照 JSON 结构示例

```json
{
  "protocol": "openai",
  "success": true,
  "httpStatus": 200,
  "errorCode": null,
  "errorMessage": null,
  "usage": {"normalInput": 8, "cacheHit": 2, "cacheCreateExplicit": 0,
            "cacheHitExplicit": 0, "normalOutput": 15, "thinking": 0,
            "rawPromptTokens": 10, "rawCompletionTokens": 15, "rawTotalTokens": 25},
  "body": "{\"id\":\"chatcmpl-...\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"...\"},\"finish_reason\":\"stop\"}]}",
  "bodyTruncated": false,
  "capturedAt": "2026-08-24T10:00:00.123"
}
```

### 2.4 代码实现

**新增 `billing/ResponseSnapshotBuilder.java`**（单一职责：素材 → ≤8KB JSON 或 NULL；任何异常只 WARN 返回 NULL，绝不向上抛）：

```java
@Component
public class ResponseSnapshotBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResponseSnapshotBuilder.class);
    private static final int MAX_SNAPSHOT = 8192;   // 对齐 VideoTaskArchiver 8KB 惯例
    private static final int MAX_BODY = 4096;       // body 预算，为 status/usage/error 预留
    private static final String BASE64_MARK = "<base64 truncated, original length %d>";

    private final ObjectMapper objectMapper;

    public ResponseSnapshotBuilder(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public String build(String protocol, boolean success, Integer httpStatus,
                        String errorCode, String errorMessage, JsonNode usage, String body) {
        if (usage == null && body == null && errorCode == null && errorMessage == null) {
            return null; // 全空素材不落无意义行
        }
        try {
            ObjectNode snapshot = objectMapper.createObjectNode();
            snapshot.put("protocol", protocol);
            snapshot.put("success", success);
            if (httpStatus != null) { snapshot.put("httpStatus", httpStatus); }
            if (errorCode != null) { snapshot.put("errorCode", errorCode); }
            if (errorMessage != null) { snapshot.put("errorMessage", truncate(errorMessage, 500)); }
            snapshot.set("usage", usage != null ? usage : objectMapper.nullNode());
            String sanitized = sanitizeBody(body);
            if (sanitized != null) {
                snapshot.put("bodyTruncated", sanitized.length() > MAX_BODY);
                snapshot.put("body", truncate(sanitized, MAX_BODY));
            }
            snapshot.put("capturedAt", LocalDateTime.now().toString());
            return truncate(objectMapper.writeValueAsString(snapshot), MAX_SNAPSHOT); // 最终兜底
        } catch (Exception e) {
            LOGGER.warn("计费响应快照构建失败，降级为 NULL: {}", e.getMessage());
            return null;
        }
    }

    /** 响应体脱敏：可解析 JSON 递归替换 base64 data URL，解析失败按纯文本直接截断 */
    private String sanitizeBody(String body) { /* 同 VideoTaskArchiver.stripBase64Data 处理 */ }

    private static String truncate(String text, int max) {
        return text != null && text.length() > max ? text.substring(0, max) : text;
    }
}
```

**BillingContext 扩展**（modelId 不加，仅加快照）：

```java
// 字段区（与 videoUsage 并列）
private String responseSnapshot; // 模型返回响应快照（JSON，≤8KB；构建失败为 null）

public BillingContext responseSnapshot(String v) { this.responseSnapshot = v; return this; }
public String getResponseSnapshot() { return responseSnapshot; }
```

**BillingRecordEntity 新增**：

```java
// ========== 模型返回响应快照 ==========
/** 模型返回响应快照（TEXT，JSON 结构；截断至 8KB、base64 脱敏；构建失败为 NULL） */
@Column(name = "response_snapshot", columnDefinition = "TEXT")
private String responseSnapshot;
```

**BillingService 两条 build 路径落库**（modelId 见第一节代码，快照如下）：

```java
// buildRecord（六维）builder 链
.vendor(ctx.getVendor())
.responseSnapshot(ctx.getResponseSnapshot())

// buildVideoRecord（视频）builder 链
.vendor(ctx.getVendor())
.responseSnapshot(ctx.getResponseSnapshot())
.usageDetail(buildVideoUsageDetail(...))
```

`recordBilling`（异步）与 `recordBillingSync`（同步）共享 `doRecordBilling`，两链路天然行为一致；`recordBillingSync` 返回 `record.getId()` 与 `attachBillingRecord` 回写不受影响。

**透传链路捕获点**（ProtocolPassthroughService.java，各分支响应体可用性见 1.3 节核对结论）：

1. `recordBilling` 签名扩展 `Integer httpStatus` 与 `String upstreamBody`，方法内 `responseSnapshotBuilder.build(protocol, success, httpStatus, errorCode, errorMessage, usageJson, upstreamBody)` 后 `.responseSnapshot(snapshot)`；
2. 非流式：map 内 `body` 已持有完整上游响应体，成功与失败均传 body（`httpStatus=status`）；
3. 非流式超时：`httpStatus=504`、body=null（仅存错误码/信息快照）；
4. 流式：不聚合全文，新增 `AtomicReference<String> lastUsageChunk`，在 `captureUsageFromChunk` 捕获 usage 时记录该 chunk（通常含 finish_reason 与 choices 收尾），`doOnComplete` 传该 chunk 作 body（`httpStatus=200`）；`doOnError` 传 `UpstreamHttpException.protocolBody` 作 body（建连阶段错误体）。

**视频链路**（VideoTaskSettler.java）：

```java
// 构造器注入 builder（现有 3 依赖 + 1）
public VideoTaskSettler(final VideoTaskRepository repository,
                        final BillingService billingService,
                        final ModelPricingService pricingService,
                        final ResponseSnapshotBuilder responseSnapshotBuilder) { ... }

// settleSuccess ctx 链（复用既有 snapshot 参数：改写后响应，id=网关任务号）
.videoUsage(new BillingService.VideoUsage(resolution, hasVideoInput, seconds, completionTokens))
.responseSnapshot(responseSnapshotBuilder.build("vidGen", true, 200, null, null,
        usage.isObject() ? usage : null, snapshot))
.isFreeQuota(false);
```

与任务表快照异同：同源（同一 `snapshot` 字符串），任务表保留 65535 上限、账单侧重新包装为 ≤8KB JSON 并补 usage/status 元数据；刻意用改写后快照（不用原始 `upstreamResp`），上游 `task_id` 不落入账单。

### 2.5 健壮性

- builder 全 try/catch：序列化失败、JSON 解析失败、超长均降级 NULL + WARN；`buildRecord`/`buildVideoRecord` 只写列值，**快照失败不触碰计费金额、余额扣减与 `recordBillingSync` 的 id 返回**；
- 响应体为空/全空素材返回 NULL，不落无意义 JSON；
- 超长截断：body 预算 4096 + 全量 8192 兜底，`bodyTruncated` 标记可读；
- 透传 `recordBilling` 外层已有 try/catch ERROR 留痕，快照异常被内层吞掉后不二次抛异常；
- 视频链路 `billing_record_id` 回写（`attachBillingRecord`）与快照无耦合，资损兜底口径（status=succeeded AND billing_record_id IS NULL）不变；
- modelId 兜底：定价未命中时 NULL，`model_name` 保留作降级关联口径。

## 三、测试

**BillingServiceTest**（沿用 `ArgumentCaptor<BillingRecordEntity>` + `verify(billingRepository).save` 风格）：
- `shouldStoreModelId_whenPricingHit`：pricing 含 modelId → 断言落库实体 `getModelId()` 等于 pricing 值；
- `shouldStoreNullModelId_whenPricingMiss`：pricing 为 null → `getModelId()` 为 null；
- `shouldStoreResponseSnapshot_whenContextCarriesSnapshot`：ctx 带快照 → 落库实体快照等于传入值；
- `shouldStoreNullSnapshot_whenContextHasNoSnapshot`：ctx 无快照 → 快照为 null（旧链路兼容）。

**VideoTaskSettlerTest**（构造器更新为 4 依赖，mock `ResponseSnapshotBuilder`）：
- `settle_succeeded_passesSnapshotToBillingContext`：断言捕获 ctx 的 `getResponseSnapshot()` 等于 builder 返回值。

**新增 ResponseSnapshotBuilderTest**：
- 正常构建含 usage/body/capturedAt；
- 超长 body 截断：总长 ≤8192 且 `bodyTruncated=true`；
- base64 脱敏：`data:image/png;base64,xxx` 被替换为 `<base64 truncated, original length N>`，公网 URL 原样保留；
- 全空输入返回 null；`ObjectMapper` mock 抛异常时返回 null 不抛出。

## 四、验收标准

1. `V5__ai_billing_record_model_id_and_response_snapshot.sql` 幂等、可重复执行，全量中文注释 + 回滚 SQL（默认注释），**不含 SELECT 校验语句**（遵循 DDL 规范：仅正向与回滚 SQL）；
2. 旧数据兼容：历史账单 `model_id`/`response_snapshot` 全 NULL，查询/统计不受影响；
3. 两类链路账单均落 `model_id`（视频必非空；六维定价未命中降级 NULL 且 model_name 可用），按 model_id 可精确关联模型表；
4. 透传非流式（成功与失败）、透传流式、视频 succeeded 三类账单均落快照；按 `billing_record_id` 单行 SELECT 即可追溯上游状态/用量/截断响应体；
5. 快照构建失败、响应缺失、超长时账单仍正常落库（NULL + WARN），`recordBillingSync` 的 id 返回与 `ai_video_task.billing_record_id` 回写行为不变；
6. 单测全绿（BillingServiceTest / VideoTaskSettlerTest 更新 + ResponseSnapshotBuilderTest 新增）。