# 视频模型计费元数据改造实施计划

## 已确认的关键决策
- **用量来源**：上游 succeeded 响应实测样例（顶层 `resolution`="720p"、`duration`=5、`framespersecond`=24；`usage` 内含 `SR`/`duration`/`completion_tokens`）。取值顺序：顶层 `resolution` → `usage.SR` → 留档 `ai_video_task.resolution`；token 取 `usage.completion_tokens`。**时长口径（duration/frames 二选一，查询响应只回传其一）**：响应含 `duration`（整数秒约数 = 实际总帧数/24 向下取整，方舟计费口径）时直接按秒计量；响应无 `duration` 而含 `frames` 时按 `frames ÷ framespersecond`（响应缺帧率字段时默认 24）折算为小数秒；再回退 `usage.duration` → 留档 `duration`/`frames`；无有效值（null 或 <=0）时拒计费。
- **hasVideoInput 来源**：上游响应不含该信息，创建链路解析请求 `content` 数组中 `type=video_url` 的项，落留档表新列 `has_video_input`（BOOLEAN 三态：content 缺失/非数组时为 NULL）。
- **兜底**：price_mode=2 无匹配启用规则行、或计量用量缺失（seconds 为 null 或 <=0 / tokens<=0）→ 拒计费：不落账单、不扣余额，ERROR 日志含 taskNo/resolution/hasVideoInput，billing_record_id 留 NULL，沿用「status=succeeded AND billing_record_id IS NULL」人工对账口径。
- **展示**：仅落 usage_detail JSONB 供算力平台账单页展示，网关侧不改展示页面。

## A. 平台元数据读取

### A1. [PlatformModelEntity.java](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/persistence/jpa/entity/platform/PlatformModelEntity.java)
新增两个只读映射字段（含中文注释，与现有一致）：
- `priceMode`（`price_mode`，Integer）：1=统一价格，2=按条件定价
- `billingUnit`（`billing_unit`，String）：second=元/秒，token=元/M token

### A2. 新增 [PlatformVideoPriceEntity.java](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/persistence/jpa/entity/platform/PlatformVideoPriceEntity.java)（映射 ai_model_video_price，只读）
字段：`id`、`modelId`、`outputResolution`（480P/720P/1080P/4K）、`hasVideoInput`（Boolean，统一价格为 NULL）、`enabled`（Boolean，默认 true）、`price`（BigDecimal，单位由主表 billing_unit 决定）。字段命名遵循 POJO 布尔字段规范（`enabled` 不用 is 前缀）。

### A3. 新增 [PlatformVideoPriceRepository.java](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/persistence/jpa/repository/platform/PlatformVideoPriceRepository.java)
`List<PlatformVideoPriceEntity> findByModelId(Long modelId)`。

### A4. [PlatformDataSyncService.java](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/platform/sync/PlatformDataSyncService.java)
- 注入 `PlatformVideoPriceRepository`。
- `hasUsablePricing`：新增视频分支——`model_type="3"` 一律放行（价格在子表，同阶梯计费理由）；非视频模型保持现有 token 价非空判断。
- `buildPricing`：视频模型加载规则 `videoPriceRules`，过滤 `!Boolean.FALSE.equals(enabled)`（enabled 默认 true）；`output_resolution` 归一化为大写（"4k"→"4K"、"720p"→"720P"，与元数据口径一致，消除大小写差异）；price 单位转换：billing_unit=second 保持元/秒原值，billing_unit=token 用现有 divisor 1e6 转元/token；`priceMode` 默认 1、`billingUnit` 默认 "second"。

### A5. [ModelPricingService.java](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/billing/ModelPricingService.java)
- 新增 record `VideoPriceRule(String outputResolution, Boolean hasVideoInput, BigDecimal price)`（与 PriceTier 同风格）。
- `ModelPricing` 新增字段 `priceMode`、`billingUnit`、`videoPriceRules`（默认 List.of()）+ getter；全参构造器追加这三个参数并更新 A4 唯一调用点；旧兼容构造器补默认值。

## B. 计费匹配（[BillingService.java](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/billing/BillingService.java)）

### B1. BillingContext 新增视频用量
新增 `record VideoUsage(String resolution, Boolean hasVideoInput, BigDecimal seconds, Long tokens)`（resolution 已大写归一化）；BillingContext 增字段 `videoUsage` + builder/getter（仅视频结算链路设置，其余链路为 null 走原六维逻辑零影响）。

### B2. buildRecord 视频分支
开头判断 `ctx.getVideoUsage() != null && pricing != null && pricing.getPriceMode() != null` → 走新私有方法 `buildVideoRecord(ctx, pricing)`，否则原六维逻辑不变：
1. 规则匹配：按 `resolution` 精确匹配启用规则行；price_mode=2 时追加 `hasVideoInput` 相等条件（统一价格不区分视频输入）。
2. **无匹配启用行 → 抛 IllegalStateException**（日志含 model/resolution/hasVideoInput/billingUnit）：recordBillingSync 捕获返回 null，recordBilling @Async 捕获 ERROR——不落账不扣余额。
3. 计量：billing_unit=second → `cost = seconds × price`（seconds 为 null 或 <=0 拒计费；frames 折算场景 seconds 为小数，BigDecimal 精确计算）；billing_unit=token → `cost = tokens × price`（元/token 口径，tokens<=0 拒计费）。
4. 折扣：复用 `DiscountCalculationService.calculate`（模型×用户×企业补贴），`discountAmount = cost × (1 − finalDiscountRate)`（scale 6 HALF_UP），`amount = cost − discountAmount`。
5. usage_detail JSON（严格按约定示例字段，cost/discountAmount/amount 与账单 original_cost/discount_amount/total_cost 一致）：
```json
{"billingUnit":"second","items":[{"resolution":"720P","hasVideoInput":false,"seconds":5,"tokens":null,"cost":0.10,"discountAmount":0.05,"amount":0.05}]}
```
second 单位时 `tokens` 为 null，`seconds` 为实际计量秒数（frames 折算场景可为小数，如 2.375）；token 单位时 `seconds` 为 null、`tokens` 为 token 数；`hasVideoInput` 写本次调用实际值。
6. 落库：`promptTokens=0`、`completionTokens/totalTokens`=上游原始 token（对账）；六维分项 token/单价列置 null；`outputCost=originalCost=cost`、`discountAmount`、`totalCost=amount`；`billingMode` 置 null（不混用 token 阶梯口径）；`usageDetail`=JSON 字符串。

## C. 结算链路（[VideoTaskSettler.java](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/router/video/VideoTaskSettler.java)）
`settleSuccess` 组装 VideoUsage：
- resolution：顶层 `resolution`（缺失回退 `usage.SR`、再回退 `entity.resolution`），统一 `toUpperCase(Locale.ROOT)`；仍缺失传 null 由计费侧拒计费。
- seconds（duration/frames 二选一，查询响应只回传其一）：顶层 `duration`（Number，>0）直接取用（整数秒约数，方舟计费口径）→ 缺失时顶层 `frames ÷ framespersecond`（帧率缺失默认 24，BigDecimal 除法 scale 6 HALF_UP）折算为小数秒 → 再回退 `usage.duration` → 留档 `entity.duration`（>0）→ 留档 `entity.frames`÷24；仍无有效值传 null 由计费侧拒计费。
- hasVideoInput：`entity.getHasVideoInput()`。
- tokens：`usage.completion_tokens`（保留现有缺失 WARN）。
`ctx.videoUsage(vu)` 设置；计费失败 ERROR 日志增强含 resolution/hasVideoInput。定价预检 WARN 保留。

## D. 留档扩展（has_video_input）
- [VideoTaskEntity.java](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/persistence/jpa/entity/VideoTaskEntity.java)：新增 `hasVideoInput`（`has_video_input`，Boolean，中文注释说明三态语义）。
- [VideoTaskArchiver.java](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/router/video/VideoTaskArchiver.java)：`buildBase` 解析 `requestNode.path("content")` 数组，任一项 `type=video_url` 即 true、数组内无此项为 false、content 缺失/非数组为 null，写入实体。

## E. 账单实体与 DDL
- [BillingRecordEntity.java](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/persistence/jpa/entity/BillingRecordEntity.java)：新增 `usageDetail`（String），`@JdbcTypeCode(SqlTypes.JSON)` + `@Column(name = "usage_detail", columnDefinition = "JSONB")`（参照 ServiceInstanceEntity.headers 既有 JSON 映射手法）。
- 新增 [V4__video_billing_metadata.sql](file:///d:/workspace/lm_aggregation_platform_server/src/main/resources/db/scripts/V4__video_billing_metadata.sql)（遵循 DDL 全量中文注释 + 正向/回滚规范，金仓 PG 兼容幂等）：
  1. `ALTER TABLE ai_video_task ADD COLUMN IF NOT EXISTS has_video_input BOOLEAN` + COMMENT；
  2. `ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS usage_detail JSONB` + COMMENT；
  3. 头部注明平台侧配合项（ai_model.price_mode/billing_unit 与 ai_model_video_price 由平台侧发布）与部署顺序强约束（DDL 先于代码）；
  4. 末尾校验 DML + 回滚注释段（DROP COLUMN IF EXISTS）。

## F. 测试与验证
- [BillingServiceTest.java](file:///d:/workspace/lm_aggregation_platform_server/src/test/java/org/unreal/modelrouter/billing/BillingServiceTest.java)：新增用例——统一价格按分辨率匹配（second）、条件定价按 resolution×hasVideoInput 匹配、无匹配启用行拒计费（recordBillingSync 返回 null）、token 单位计量、usage_detail JSON 字段断言、折扣金额计算。
- [VideoTaskSettlerTest.java](file:///d:/workspace/lm_aggregation_platform_server/src/test/java/org/unreal/modelrouter/router/video/VideoTaskSettlerTest.java)：更新为断言 videoUsage 组装（resolution 大写归一化、duration 秒数、frames÷framespersecond 小数秒折算、帧率缺失默认 24、hasVideoInput 透传、tokens）。
- [VideoTaskArchiverTest.java](file:///d:/workspace/lm_aggregation_platform_server/src/test/java/org/unreal/modelrouter/router/video/VideoTaskArchiverTest.java)：新增 has_video_input 解析用例（含 video_url 项 true / 无视频项 false / content 缺失 null）。
- 编译验证：`mvnw test-compile -Dcheckstyle.skip=true -Dspotbugs.skip=true` + 跑上述三个测试类（沿用 CLAUDE.md 的 classworlds launcher 方式）。

## 不改动项
- 对外 API（POST /v1/videos/generations、GET /v1/videos/{id}）协议不变。
- 六维 token 计费链路（对话/图片等）不受影响（videoUsage 为 null 时原逻辑原样执行）。
- 网关侧无账单展示页面改动（仅 usage_detail 落库）。
- `ai_user_free_quota` 不涉及 vidGen，不改动。