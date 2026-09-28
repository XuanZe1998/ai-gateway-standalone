# 视频生成 API 第一阶段:创建任务 + 留档追溯

## 业界方案结论(已调研)

- **计费时机**:OpenAI Sora 2 / 火山方舟 Seedance / 可灵 / 头部中转商(302.AI、硅基流动)均为"异步提交不计费 → 轮询查询 → 任务成功后按实际用量计费"。方舟查询接口返回 `usage.completion_tokens` 作为计费对账依据(视频模型输入 token=0,total=completion)。故**第一阶段只做余额预检,不动计费链路;第二阶段查询链路 succeeded 时复用现有六维计费(serviceType=vidGen)结算**
- **上游范围**:头部中转商均为"统一异步任务协议 + 厂商适配、渐进接入"。第一阶段仅火山方舟透传,留档表预留 vendor 维度

## 对外协议设计(OpenAI 风格靠拢)

- 端点:`POST /api/v1/videos/generations`(经 `SpaWebFluxConfig.apiPathForwardFilter` 同时支持 `/v1/...`),与 `/v1/images/generations` 命名一致
- 请求体:参照附件字段全量支持(`model`、`content[]`(text/image_url/video_url/audio_url/draft_task 及 role)、`resolution`、`ratio`、`duration`、`frames`、`generate_audio`、`watermark`、`seed`、`camera_fixed`、`return_last_frame`、`draft`、`service_tier`、`callback_url`、`execution_expires_after`、`priority`、`safety_identifier`、`omni_reference_task_type`、`output_format`、`tools`),类型化 DTO + `@JsonAnySetter` 兜底未知字段后原样透传(同 `OpenAiChatRequest` 手法)
- 成功响应(OpenAI 资源对象风格):`{"id":"vidtask_xxx","object":"video.generation.task","created_at":<unix秒>,"model":"...","status":"queued"}`;对外只暴露网关任务 ID,不泄露上游任务 ID(防渠道信息泄露,映射留档)
- 错误统一 OpenAI 格式 `{"error":{"message","type","code"}}`(复用 `ProtocolErrorHandler.openAiError`):无实例 404 / 未实名 403 / 余额不足 402 / 上游错误透传状态码 / 超时 504

## 网关侧代码改动

### 1. DTO
- 新增 [common/dto/VideoGenerationRequest.java](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/common/dto):关键字段类型化 + `@JsonAnySetter`/`@JsonAnyGetter` 保留未知字段,`valueToTree` 后全量透传

### 2. Controller
- 新增 `router/controller/VideoGenerationController.java`:`@RequestMapping("/api/v1")` + `@PostMapping("/videos/generations")`,Swagger 注解(与 [ProtocolGatewayController](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/router/controller/ProtocolGatewayController.java) 同风格),委托 `VideoTaskService`

### 3. 核心服务(新增包 router/video/)
- `VideoTaskService.java`,链路参照 [ProtocolPassthroughService](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/router/protocol/ProtocolPassthroughService.java) 非流式段:
  1. Reactor Context 取 `UserIdentity` → `balanceCheckService.checkBalance(identity, "vidGen")`(vidGen 无免费额度配置自然走余额分支;实名 403 / 余额≤0 返回 402)
  2. `registry.selectInstance(ServiceType.vidGen, model, clientIp)` + `getModelPath(vidGen, model)`
  3. `TracingWebClientFactory` 建 WebClient,带 `instance.getHeaders()` 的 Authorization,`bodyValue(requestNode)` 原样 POST 上游
  4. 上游 2xx:解析响应 `id` 为上游任务 ID → 生成网关任务号 `vidtask_<uuid>` → **boundedElastic 异步落档**(落档失败仅告警日志,不影响客户端响应) → 返回 OpenAI 风格响应
  5. 上游 4xx/5xx:`exchangeToMono` 取错误体转 OpenAI error 格式透传状态码;超时 504 并记录失败留档
- `VideoTaskArchiver.java`(或合并入 service):留档落库 + 请求快照脱敏(base64 data URL 截断为 `data:image/png;base64,<truncated:1024KB>` 前缀标记,URL 类原样保留)

### 4. 留档持久层(追溯核心)
- 新增 `persistence/jpa/entity/VideoTaskEntity.java`(表 `ai_video_task`,风格参照 [BillingRecordEntity](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/persistence/jpa/entity/BillingRecordEntity.java)):
  - 任务标识:`task_no`(唯一索引,对外 ID)、`upstream_task_id`(普通索引)、`status`(submitted/queued/running/succeeded/failed/expired/cancelled)
  - 路由维度:`model_name`、`vendor`、`channel_id`、`channel_name`、`instance_id`、`base_url`
  - 关键参数提取(供二期计费/对账):`resolution`、`ratio`、`duration`、`frames`、`generate_audio`、`draft`
  - 快照:`request_snapshot`(TEXT,脱敏后)、`first_response_snapshot`(上游创建响应)
  - 用户维度:`user_id`、`user_account`、`api_key_id`、`api_key_name`、`user_type`、`enterprise_id`、`company_id`(索引:user_id、api_key_id、submitted 时间)
  - 追溯:`trace_id`、`client_ip`、`error_code`、`error_message`
  - 时间:`submitted_at`、`completed_at`、审计四字段、`is_deleted`
  - 预留:`billing_record_id`(二期计费落账后回写对账)
- 新增 `persistence/jpa/repository/VideoTaskRepository.java`:`findByTaskNo`、`findByUpstreamTaskId`、按用户/模型/状态/时间分页查询
- 新增 `db/scripts/V3__create_ai_video_task.sql`(部署手工执行,遵循 V1/V2 脚本纪律;`ddl-auto: none`)

### 5. 内部追溯查询端点(第一阶段即提供,运维对账用)
- `InternalRefreshController` 同级或新 controller:`GET /internal/videos/tasks/{taskNo}`、`GET /internal/videos/tasks`(分页:用户/模型/状态/时间范围),鉴权与 `/internal/refresh` 一致(HMAC 签名)
- 对外客户端查询 API(`GET /api/v1/videos/{id}`)属第二阶段

### 6. 渠道视频 URL 支持(与平台 DDL 解耦上线)
- [PlatformChannelEntity](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/persistence/jpa/entity/platform/PlatformChannelEntity.java) 增加 `video_url` 映射 + [PlatformDataSyncService](file:///d:/workspace/lm_aggregation_platform_server/src/main/java/org/unreal/modelrouter/platform/sync/PlatformDataSyncService.java) `resolveChannelServiceUrl` 的 `case "3","video"` 由 null 改为 `channel.getVideoUrl()`
- **上线顺序强约束**:JPA 实体加了数据库中不存在的列会导致所有渠道查询 SQL 报错,此改动必须在算力平台 `ai_channel` 加列完成后再合并。平台 DDL 执行后,方舟渠道需配置 `video_url`(支持纯路径如 `/api/v3/contents/generations/tasks` 或完整 URL,两种形态创建/查询链路均支持)。若渠道未配 `video_url`,`detectPath` 对视频类型兜底返回 `/v1/videos/generations`,仅适用于 OpenAI 兼容视频供应商(渠道 baseUrl 为 host 根);方舟渠道不可用 `base_url` 配完整任务地址替代 `video_url`(实测会拼接出 `.../tasks/v1/videos/generations` 错误地址,该"过渡方案"不可用,勿采用)

## 算力平台元数据侧调整清单(当前项目先行,平台配合)

1. **ai_channel**:新增 `video_url` 列(视频生成服务级 URL,支持完整 URL 或纯路径);渠道管理页表单增加该输入项
2. **ai_model**:新增 Seedance 视频模型记录 — `model_type=3`(字典"视频生成")、`real_name` 与方舟 Model ID 一致(如 `doubao-seedance-2-5-*`)、`vendor=volcengine`、`base_url=https://ark.cn-beijing.volces.com`(host 根,路径由渠道 `video_url` 提供)、API Key 配模型级或渠道级方舟 Key、`status=1` 上架
3. **定价配置(二期计费预备)**:方舟视频模型按 token 计费且输入 token=0 — `input_price=0`、`output_price` 配元/M token(由元/秒刊例价折算),`discount` 沿用现有逻辑;第一阶段不产生账单但建议同步配好
4. **模型管理页**:`model_type` 下拉增加"视频生成"(数字编码 3);二期账单展示页支持 `service_type=vidGen`
5. **免费额度**:`ai_user_free_quota` 不为 vidGen 开启(文本类专属),无需改动
6. **上线流程**:平台配置完成 → 网关 `POST /internal/refresh` → 视频模型进入实例缓存生效(遵循既有缓存纪律)

## 测试计划

- 用 CLAUDE.md 的 classworlds launcher 方式跑 `test-compile`(含 `-Dcheckstyle.skip=true -Dspotbugs.skip=true`)验证编译
- 单测:`VideoTaskService` 成功落档 / 无可用实例 / 余额不足 402 / 上游 4xx 透传 / 落档失败不影响响应 / 请求快照 base64 脱敏
- 手工集成:配置 dev 环境方舟模型后 `curl POST /api/v1/videos/generations`,核对留档表记录与内部查询端点

## 关键假设

- 第一阶段上游仅火山方舟;对外请求字段以附件为准,端点命名/响应/错误格式向 OpenAI 靠拢
- 创建不计费(业界主流),第二阶段查询成功时按 `usage.completion_tokens` 复用六维计费结算
- 留档表建在网关自有 schema(sldd-{profile}),V3 脚本手工执行
- 对外不暴露上游任务 ID;`callback_url` 原样透传给上游(上游直接回调客户端,网关不中转,二期可评估网关侧回调)