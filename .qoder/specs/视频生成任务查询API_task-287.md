# 视频生成 API 第二阶段：查询任务 + 成功结算

## 业界方案与总体链路

沿用一阶段既定决策（计费方案调研记忆）：异步提交不计费 → 客户端轮询查询 → 首次观测到 `succeeded` 时按上游 `usage.completion_tokens` 复用现有六维计费结算。链路：

```
GET /api/v1/videos/{id}
 → 留档表按 task_no 反查（归属校验）
 → 本地已终态？直接回快照；否则 GET 上游方舟 /api/v3/contents/generations/tasks/{upstream_task_id}
 → 响应改写（id→task_no，注入 object）透传客户端
 → 终态落库 + succeeded 首次结算（幂等）+ billing_record_id 回写
```

## 对外协议设计

- 端点：`GET /api/v1/videos/{id}`（id 为网关任务号 `vidtask_*`；经 [SpaWebFluxConfig.apiPathForwardFilter](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/config/core/SpaWebFluxConfig.java) 同时支持 `/v1/videos/{id}`，路径与一阶段两处注释承诺一致）。鉴权走既有 `"/api/**" authenticated()`，无需改 SecurityConfiguration
- 成功响应：上游响应原样透传（status/content.video_url/content.last_frame_url/usage/error/created_at 等全字段），仅改写 `id` 为网关任务号、注入 `"object":"video.generation.task"`，不泄露上游任务 ID
- 错误统一 OpenAI 格式（复用 `ProtocolErrorHandler.openAiError`）：
  - 任务不存在 / 不属于当前用户 → 404 `task_not_found`（归属不符也报 404，防止任务 ID 探测）
  - 留档缺上游任务 ID（提交时解析失败的遗留数据）→ 502 `upstream_task_missing`
  - 上游渠道不可用 → 503 `upstream_unavailable`；上游 4xx/5xx 透传状态码；超时 504
  - 上游 404（超 7 天记录清除）→ 对客户端 404，本地状态置 `expired` 止损
- 查询免费，不做余额预检

## 网关侧代码改动

### 1. Controller — [VideoGenerationController.java](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/router/controller/VideoGenerationController.java)
新增 `@GetMapping("/videos/{id}")` + Swagger 注解（与创建端点同风格），委托 `VideoTaskQueryService.queryTask(taskNo, exchange)`；类 Javadoc 中"查询接口属第二阶段"表述更新为已实现

### 2. 查询服务 — 新增 `router/video/VideoTaskQueryService.java`
阻塞操作全部 `Mono.fromCallable(...).subscribeOn(boundedElastic())`，流程：
1. `VideoTaskRepository.findByTaskNoAndDeletedFalse`；缺失或 `userId` 与当前 `UserIdentity` 不符 → 404
2. 本地状态已终态（succeeded/failed/expired/cancelled）→ 直接返回 `response_snapshot`（缺失时回退 `first_response_snapshot` + 留档 status），不再打上游（防 7 天过期 404、防重复结算）
3. 解析上游实例：**优先按留档 `instance_id` 在 `registry.getAllInstances().get(vidGen)` 中精确匹配**（保证 API Key 与任务归属渠道一致），找不到再回退 `selectInstance(vidGen, entity.modelName, clientIp)`；均失败 → 503
4. 查询路径：`registry.getModelPath(vidGen, modelName)` 非空则 `creationPath + "/" + upstreamTaskId`；为空串（video_url 配完整 URL 形态，创建时直接 POST baseUrl 本身）则仅拼 `"/" + upstreamTaskId`（拼到 baseUrl 后即「创建地址/任务 ID」，复用方舟默认常量会与含路径的 baseUrl 双重拼接）；路径获取异常（null，渠道配置变更等）回退方舟默认常量 `/api/v3/contents/generations/tasks/{upstreamTaskId}`。baseUrl 优先取命中实例当前值，回退留档 `base_url`
5. WebClient GET 复用一阶段 `buildWebClient/buildRequestSpec` 手法（TracingWebClientFactory + instance headers），查询超时默认 30s
6. 2xx → 响应改写 + 结算（见下）；4xx/5xx → `upstreamErrorEntity` 透传（同一阶段手法）

### 3. 终态落库与幂等结算 — 新增 `router/video/VideoTaskSettler.java`
- `succeeded`：先 `claimTerminal(id, "succeeded")`（UPDATE WHERE status NOT IN 终态集合，DB 串行化保证并发轮询仅一方抢到）→ 抢到者组装 `BillingContext` 调同步计费（见下）→ `attachBillingRecord(id, billingRecordId)`（WHERE billing_record_id IS NULL 双保险）。计费上下文：`serviceType="vidGen"`、`promptTokens=0`、`completionTokens=usage.completion_tokens`、`totalTokens=usage.total_tokens`、model/channel/vendor/baseUrl 取留档、用户与账户维度取当前查询 identity（归属已校验为同一人），`accountId/accountType` 复用 [ProtocolPassthroughService](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/router/protocol/ProtocolPassthroughService.java) `resolveAccountId/resolveAccountType` 逻辑（私有则抽取复用）、`startedAt=entity.submittedAt`、`isFreeQuota=false`
- `failed/expired/cancelled`：`claimTerminal` + 回写 `error_code/error_message`（上游 `error.code/message`）、`completed_at`、`response_snapshot`
- `queued/running`：fire-and-forget 更新留档 status（中间态供内部追溯）
- 结算在响应前于 boundedElastic 同步完成（毫秒级，保证首次查询即触发；计费异常仅 ERROR 日志含 taskNo 便于对账，与既有 @Async 可靠性策略一致）

### 4. BillingService — [BillingService.java](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/billing/BillingService.java)
新增 `public Long recordBillingSync(BillingContext ctx)`：抽取现有 `recordBilling` 的 buildRecord+save+余额扣减主体为私有共享方法，同步执行并返回 `record.getId()`（失败返回 null）。原 `@Async recordBilling` 行为不变，其他链路零影响

### 5. Repository — [VideoTaskRepository.java](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/persistence/jpa/repository/VideoTaskRepository.java)
新增三个 `@Modifying` 方法：
- `claimTerminal(id, status, errorCode, errorMessage, completedAt)`：`UPDATE ... WHERE id=:id AND status NOT IN ('succeeded','failed','expired','cancelled')`，返回受影响行数
- `attachBillingRecord(id, billingRecordId)`：`WHERE billing_record_id IS NULL`
- `updateRunningStatus(id, status)`：中间态刷新（同样限非终态条件）

### 6. 实体与 DDL
- [VideoTaskEntity.java](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/persistence/jpa/entity/VideoTaskEntity.java) 新增 `responseSnapshot`（`response_snapshot` TEXT，上游终态完整响应快照，终态后查询直出的数据源），字段中文 Javadoc 齐全
- 新增 `db/scripts/V4__alter_ai_video_task_add_response_snapshot.sql`：遵循全量中文注释规范（脚本头部版本/背景/执行方式、逐列说明、COMMENT ON、末尾校验 DML），手工执行（`ddl-auto: none`）

### 7. 收尾
- [InternalVideoTaskController.java](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/router/controller/InternalVideoTaskController.java) Javadoc "对外客户端查询 API 属第二阶段" 改为已实现；其单条查询返回实体全字段，新列自动可见，无需改代码

## 算力平台配合项（无代码改动，配置生效）

- 方舟视频模型定价本期开始真实产生账单：确认 `input_price=0`、`output_price`（元/M token，由元/秒刊例价折算）已配置
- 账单展示页确认支持 `service_type=vidGen`（一阶段已列入清单）

## 测试计划

- CLAUDE.md classworlds launcher 方式跑 `test-compile`（`-Dcheckstyle.skip=true -Dspotbugs.skip=true`）
- 新增 `VideoTaskQueryServiceTest`：404 任务不存在 / 非归属用户同样 404 / 本地终态直出快照不打上游 / 非终态透传上游并刷新状态 / succeeded 触发计费且 billing_record_id 回写 / 重复查询不重复计费（claimTerminal 返回 0）/ 上游 404 置 expired / 上游 4xx 透传 / 渠道不可用 503
- `VideoTaskSettler` 单测：failed 终态回写 error 信息；queued/running 仅刷状态
- 手工集成：dev 环境创建任务 → 轮询查询至 succeeded → 核对 `ai_video_task`（status/completed_at/billing_record_id/response_snapshot）与 `ai_billing_record`（service_type=vidGen、completion_tokens、total_cost）及余额扣减

## 关键假设

- 上游仅火山方舟；查询路径默认常量按方舟协议，后续新厂商接入时在路径推导处按 vendor 分支
- 归属校验基于 `user_id` 精确匹配（不按企业共享）；不符报 404 防探测
- 终态后客户端查询走快照直出，`video_url` 24h 有效期语义与上游一致，网关不做转存
- 中间态轮询不打上游之外的任何计费/扣费动作；一阶段遗留的 `billing_record_id` 预留列本期启用