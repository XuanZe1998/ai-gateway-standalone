-- ============================================================
-- 视频生成 API 上线合并脚本（V3 + V4 + V5 + V6 + V7 合并版）
-- 数据库：金仓 KingbaseES（PG 兼容模式）/ PostgreSQL
-- 合并范围：
--   V3  视频生成 API 上线（ai_channel 加 video_url 列 + ai_video_task 建表）
--   V4  视频模型计费元数据升级（ai_video_task 加 has_video_input 列
--       + ai_billing_record 加 usage_detail JSONB 列）
--   V5  计费表加模型主键与响应快照（ai_billing_record 加 model_id /
--       response_snapshot 列 + 模型主键索引）
--   V6  视频任务留档表加 platform_user 列（轮询结算身份重建修复）
--   V7  视频任务留档表加 billing_rule_snapshot 列（提交时刻锁价快照）
--
-- ★★★ 上线顺序强约束 ★★★
--   1. 本脚本必须先于网关新版本部署执行完毕：JPA 实体映射了数据库
--      中不存在的列（video_url / response_snapshot / has_video_input /
--      usage_detail / model_id / platform_user / billing_rule_snapshot）会导致网关 SQL 报错、
--      服务启动/运行时崩溃。
--   2. ★ 平台侧 ai_model 新列（price_mode / billing_unit）与
--      ai_model_video_price 表必须先于网关新版本发布 ★：
--      PlatformModelEntity 映射了这两列，若网关先发布而平台表未
--      加列，定价同步 SELECT 将报列不存在 → 定价缓存为空 →
--      所有计费链路（含六维 token）按 0 元落账。
--   3. 渠道需为视频模型配置 video_url（纯路径如
--      /api/v3/contents/generations/tasks，或完整 URL；推荐纯路径，
--      渠道 base_url 配 host 根）。若未配置，detectPath 对视频类型
--      兜底返回 /v1/videos/generations，仅适用于 OpenAI 兼容供应商
--      （渠道 baseUrl 为 host 根）；方舟渠道不可用 base_url 配完整
--      任务地址替代 video_url（实测会拼接出
--      .../tasks/v1/videos/generations 错误地址，勿采用）。
--   4. 平台侧视频模型 price_mode/billing_unit 未配置或
--      ai_model_video_price 无启用规则行（含价格为空/非正数的行）时，
--      计费侧按「拒计费留人工对账」兜底（billing_record_id 置 NULL，
--      口径：status=succeeded AND billing_record_id IS NULL）。
--
-- 归属：ai_video_task / ai_billing_record 同库（网关自有 schema
--       sldd-{profile}）；平台表 ai_channel 如分属不同 schema，
--       请切换 schema 后执行对应段落。
-- 说明：快照列使用 TEXT/JSONB；base64 素材在应用层脱敏截断后入库，
--       防止大素材撑爆存储。ddl-auto: none，表结构只由本脚本管理。
-- 执行：金仓 KingbaseES（PG 兼容模式）/ PostgreSQL，幂等（IF NOT EXISTS），
--       可重复执行，手工执行。
-- ============================================================


-- ============================================================
-- 正向 SQL
-- ============================================================

-- ############################################################
-- 第 1 段（原 V3）：视频生成 API 上线
--   背景：视频生成 API 一、二阶段上线。异步任务「创建 → 轮询查询
--         → 成功后按用量结算」全链路涉及本库两处表结构变更：
--         1. 平台表 ai_channel 新增 video_url 列（渠道视频服务级 URL）；
--         2. 网关表 ai_video_task 建表（留档追溯，一阶段创建链路；含终态
--            响应快照列，二阶段查询链路复用）。
-- ############################################################

-- ---------- 1.1 平台表 ai_channel 新增 video_url 列 ----------
-- 视频生成服务级 URL：支持完整 URL（http/https 开头，直接用作 baseUrl）
-- 或纯路径（/xxx，拼接到模型/渠道 baseUrl 之后），优先于模型/渠道 baseUrl
ALTER TABLE ai_channel ADD COLUMN IF NOT EXISTS video_url VARCHAR(500);

COMMENT ON COLUMN ai_channel.video_url IS '视频生成服务级 URL（支持完整 URL 或纯路径，优先于模型/渠道 baseUrl）';


-- ---------- 1.2 网关表 ai_video_task 建表（留档追溯） ----------
CREATE TABLE IF NOT EXISTS ai_video_task (
    -- 主键 ID，自增
    id                       BIGSERIAL PRIMARY KEY,

    -- ========== 任务标识 ==========
    -- 网关任务号（对外暴露的任务 ID），格式 vidtask_<uuid>，唯一索引
    task_no                  VARCHAR(64)  NOT NULL,
    -- 上游（如火山方舟）任务 ID，仅内部留档，不对外暴露（防渠道信息泄露）；
    -- 实测方舟 ID 超 128 字符，列宽放宽至 512（严禁应用层截断，截断会导致
    -- 查询链路用错误 ID 打上游、任务永不结算）
    upstream_task_id         VARCHAR(512),
    -- 任务状态：submitted/queued/running/succeeded/failed/expired/cancelled
    status                   VARCHAR(20)  NOT NULL,

    -- ========== 路由维度 ==========
    -- 模型名称（客户端请求的 model 字段，如 doubao-seedance-2-5-*）
    model_name               VARCHAR(255) NOT NULL,
    -- 上游厂商标识（如 volcengine），取自路由实例
    vendor                   VARCHAR(50),
    -- 关联算力平台渠道 ID
    channel_id               VARCHAR(255),
    -- 渠道/实例名称（留档展示用）
    channel_name             VARCHAR(255),
    -- 路由实例唯一标识
    instance_id              VARCHAR(255),
    -- 上游服务 baseUrl（追溯上游环境用）
    base_url                 VARCHAR(500),

    -- ========== 关键参数提取（供二期计费/对账） ==========
    -- 视频分辨率：480p/720p/1080p/4k
    resolution               VARCHAR(20),
    -- 视频宽高比：16:9/4:3/1:1/3:4/9:16/21:9/adaptive
    ratio                    VARCHAR(20),
    -- 视频时长（秒），与 frames 二选一
    duration                 INTEGER,
    -- 视频帧数（优先级高于 duration）
    frames                   INTEGER,
    -- 是否生成有声视频
    generate_audio           BOOLEAN,
    -- 是否样片模式（Draft，低成本 480p 预览）
    draft                    BOOLEAN,

    -- ========== 快照 ==========
    -- 请求快照（base64 素材已脱敏截断；公网 URL/提示词原样保留可追溯）
    request_snapshot         TEXT,
    -- 上游创建接口首次响应快照（截断至 8KB）
    first_response_snapshot  TEXT,
    -- 上游终态响应快照（TEXT）：可为空，仅终态（succeeded/failed/
    -- expired/cancelled）首次结算时回写；应用层截断后入库
    response_snapshot        TEXT,

    -- ========== 用户维度 ==========
    -- 调用方用户 ID
    user_id                  VARCHAR(255),
    -- 调用方用户账号
    user_account             VARCHAR(255),
    -- 调用所用 API Key 的 ID
    api_key_id               VARCHAR(255),
    -- 调用所用 API Key 的名称
    api_key_name             VARCHAR(255),
    -- 用户类型：1 企业用户 / 2 个人用户
    user_type                INTEGER,
    -- 所属企业 ID（企业用户时有值）
    enterprise_id            BIGINT,
    -- 所属公司 ID
    company_id               VARCHAR(255),

    -- ========== 追溯 ==========
    -- 请求链路追踪 ID
    trace_id                 VARCHAR(100),
    -- 客户端 IP
    client_ip                VARCHAR(50),
    -- 失败时的错误码（上游 HTTP 状态码或网关 504 等）
    error_code               VARCHAR(100),
    -- 失败时的错误信息（截断至 1000 字符）
    error_message            VARCHAR(1000),

    -- ========== 时间 ==========
    -- 提交上游时间
    submitted_at             TIMESTAMP    NOT NULL,
    -- 任务终态时间（第二阶段查询链路回写）
    completed_at             TIMESTAMP,

    -- ========== 计费关联（第二阶段计费落账后回写） ==========
    -- 第二阶段任务成功计费落账的 ai_billing_record.id（对账用）
    billing_record_id        BIGINT,

    -- ========== 审计字段 ==========
    -- 创建人（取调用方用户账号）
    created_by               VARCHAR(255),
    -- 创建时间
    created_at               TIMESTAMP,
    -- 最后更新人
    updated_by               VARCHAR(255),
    -- 最后更新时间
    updated_at               TIMESTAMP,
    -- 逻辑删除标记：false 有效 / true 已删除
    is_deleted               BOOLEAN      DEFAULT FALSE
);

-- ---------- 1.3 索引 ----------
-- 网关任务号唯一索引：对外任务 ID 精确查询入口
CREATE UNIQUE INDEX IF NOT EXISTS uk_video_task_no        ON ai_video_task (task_no);
-- 上游任务 ID 普通索引：上游回调/客诉时按上游 ID 反查网关留档
CREATE INDEX IF NOT EXISTS idx_video_task_upstream        ON ai_video_task (upstream_task_id);
-- 用户维度索引：按用户追溯其视频任务
CREATE INDEX IF NOT EXISTS idx_video_task_user            ON ai_video_task (user_id);
-- API Key 维度索引：按 Key 维度统计/追溯
CREATE INDEX IF NOT EXISTS idx_video_task_api_key         ON ai_video_task (api_key_id);
-- 模型维度索引：按模型统计任务量/失败率
CREATE INDEX IF NOT EXISTS idx_video_task_model           ON ai_video_task (model_name);
-- 提交时间索引：时间范围分页查询（内部追溯端点默认排序字段）
CREATE INDEX IF NOT EXISTS idx_video_task_submitted       ON ai_video_task (submitted_at);


-- ---------- 1.4 存量库列宽修正（幂等，可重复执行） ----------
-- 已按旧版 128 列宽建过 ai_video_task 的库，需执行本语句修正为 512；
-- 新建库无副作用（类型一致时重声明安全）。
ALTER TABLE ai_video_task ALTER COLUMN upstream_task_id TYPE VARCHAR(512);


-- ---------- 1.5 表/列注释（全量） ----------
COMMENT ON TABLE  ai_video_task IS '视频生成任务留档表：网关任务号与上游任务 ID 映射、路由维度、请求快照与状态流转，支撑追溯查询与二期查询计费';

-- 任务标识
COMMENT ON COLUMN ai_video_task.task_no                 IS '网关任务号（对外暴露的任务 ID），格式 vidtask_<uuid>';
COMMENT ON COLUMN ai_video_task.upstream_task_id        IS '上游（如火山方舟）任务 ID，仅内部留档，不对外暴露';
COMMENT ON COLUMN ai_video_task.status                  IS '任务状态：submitted/queued/running/succeeded/failed/expired/cancelled';

-- 路由维度
COMMENT ON COLUMN ai_video_task.model_name              IS '模型名称（客户端请求的 model 字段，如 doubao-seedance-2-5-*）';
COMMENT ON COLUMN ai_video_task.vendor                  IS '上游厂商标识（如 volcengine），取自路由实例';
COMMENT ON COLUMN ai_video_task.channel_id              IS '关联算力平台渠道 ID';
COMMENT ON COLUMN ai_video_task.channel_name            IS '渠道/实例名称（留档展示用）';
COMMENT ON COLUMN ai_video_task.instance_id             IS '路由实例唯一标识';
COMMENT ON COLUMN ai_video_task.base_url                IS '上游服务 baseUrl（追溯上游环境用）';

-- 关键参数
COMMENT ON COLUMN ai_video_task.resolution              IS '视频分辨率：480p/720p/1080p/4k';
COMMENT ON COLUMN ai_video_task.ratio                   IS '视频宽高比：16:9/4:3/1:1/3:4/9:16/21:9/adaptive';
COMMENT ON COLUMN ai_video_task.duration                IS '视频时长（秒），与 frames 二选一';
COMMENT ON COLUMN ai_video_task.frames                  IS '视频帧数（优先级高于 duration）';
COMMENT ON COLUMN ai_video_task.generate_audio          IS '是否生成有声视频';
COMMENT ON COLUMN ai_video_task.draft                   IS '是否样片模式（Draft，低成本 480p 预览）';

-- 快照
COMMENT ON COLUMN ai_video_task.request_snapshot        IS '请求快照（TEXT，base64 素材已脱敏截断；公网 URL/提示词原样保留可追溯）';
COMMENT ON COLUMN ai_video_task.first_response_snapshot IS '上游创建接口首次响应快照（TEXT，截断至 8KB）';
COMMENT ON COLUMN ai_video_task.response_snapshot       IS '上游终态响应快照（TEXT，第二阶段查询链路回写；id 已替换为网关任务号，终态后查询直出，不再打上游）';

-- 用户维度
COMMENT ON COLUMN ai_video_task.user_id                 IS '调用方用户 ID';
COMMENT ON COLUMN ai_video_task.user_account            IS '调用方用户账号';
COMMENT ON COLUMN ai_video_task.api_key_id              IS '调用所用 API Key 的 ID';
COMMENT ON COLUMN ai_video_task.api_key_name            IS '调用所用 API Key 的名称';
COMMENT ON COLUMN ai_video_task.user_type               IS '用户类型：1 企业用户 / 2 个人用户';
COMMENT ON COLUMN ai_video_task.enterprise_id           IS '所属企业 ID（企业用户时有值）';
COMMENT ON COLUMN ai_video_task.company_id              IS '所属公司 ID';

-- 追溯
COMMENT ON COLUMN ai_video_task.trace_id                IS '请求链路追踪 ID';
COMMENT ON COLUMN ai_video_task.client_ip               IS '客户端 IP';
COMMENT ON COLUMN ai_video_task.error_code              IS '失败时的错误码（上游 HTTP 状态码或网关 504 等）';
COMMENT ON COLUMN ai_video_task.error_message           IS '失败时的错误信息（截断至 1000 字符）';

-- 时间
COMMENT ON COLUMN ai_video_task.submitted_at            IS '提交上游时间';
COMMENT ON COLUMN ai_video_task.completed_at            IS '任务终态时间（第二阶段查询链路回写）';

-- 计费关联
COMMENT ON COLUMN ai_video_task.billing_record_id       IS '第二阶段任务成功计费落账的 ai_billing_record.id（对账用）';

-- 审计字段
COMMENT ON COLUMN ai_video_task.created_by              IS '创建人（取调用方用户账号）';
COMMENT ON COLUMN ai_video_task.created_at              IS '创建时间';
COMMENT ON COLUMN ai_video_task.updated_by              IS '最后更新人';
COMMENT ON COLUMN ai_video_task.updated_at              IS '最后更新时间';
COMMENT ON COLUMN ai_video_task.is_deleted              IS '逻辑删除标记：false 有效 / true 已删除';


-- ############################################################
-- 第 2 段（原 V4）：视频模型计费元数据升级配套（网关表变更）
--   背景：算力平台元数据源已升级视频模型计费方式——ai_model 新增
--         price_mode（1=统一价格/2=按条件定价）与 billing_unit
--         （second=元/秒/token=元/M token），新增子表
--         ai_model_video_price（输出分辨率 × 有无视频输入的价格规则，
--         含 enabled 启停标志）；视频模型已废弃 token 计费字段。
--         网关侧配套两处表结构变更：
--         1. ai_video_task 新增 has_video_input 列（创建链路从请求
--            content 解析「本次调用是否有视频输入」，供按条件定价
--            price_mode=2 匹配规则使用，上游响应不含该信息）；
--         2. ai_billing_record 新增 usage_detail JSONB 列（视频模型
--            账单回写实际消耗明细：计费单位 + 分辨率分项用量/费用）。
-- ############################################################

-- ---------- 2.1 ai_video_task 新增 has_video_input 列 ----------
-- 本次调用是否有视频输入（请求 content 含 type=video_url 项）：
-- true=有 / false=无 / null=无法判定（content 缺失或非数组）。
-- 视频模型按条件定价（price_mode=2）时按「输出分辨率 × 有无视频
-- 输入」匹配 ai_model_video_price 规则行。
ALTER TABLE ai_video_task ADD COLUMN IF NOT EXISTS has_video_input BOOLEAN;

COMMENT ON COLUMN ai_video_task.has_video_input IS '本次调用是否有视频输入（请求 content 含 type=video_url 项）：true=有/false=无/null=无法判定；视频模型按条件定价（price_mode=2）匹配价格规则用';


-- ---------- 2.2 ai_billing_record 新增 usage_detail JSONB 列 ----------
-- 视频模型账单实际消耗明细快照：
--   {"billingUnit":"second|token","items":[
--     {"resolution":"720P","hasVideoInput":false,"seconds":5,"tokens":null,
--      "cost":0.10,"discountAmount":0.05,"amount":0.05}]}
-- second 单位时 seconds 为计量秒数（frames 折算可为小数）、tokens 为 null；
-- token 单位时 seconds 为 null、tokens 为 token 数；
-- cost/discountAmount/amount 与 original_cost/discount_amount/total_cost 一致。
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS usage_detail JSONB;

COMMENT ON COLUMN ai_billing_record.usage_detail IS '视频模型账单实际消耗明细快照（JSONB）：billingUnit + items 分项（分辨率/有无视频输入/秒数/token/分项费用），仅视频模型账单有值';


-- ############################################################
-- 第 3 段（原 V5）：计费表新增模型主键与模型返回响应快照列
--   背景：1) 对账/统计需要按模型主键（算力平台 ai_model.id）精确关联
--            模型表，避免用 model_name 字符串匹配；
--         2) 对账与问题排查需要从账单直接追溯上游返回内容
--            （协议/HTTP 状态/错误码/错误信息/usage/截断响应体），
--            两条计费链路（透传异步 + 视频同步）统一按
--            billing_record_id 单行追溯。
--   说明：model_id 取自定价缓存（定价未命中/历史数据为 NULL，退化为
--         model_name 关联）；response_snapshot 为 JSON 结构，应用层
--         截断至 8KB 且 base64 素材已脱敏，旧数据与构建失败为 NULL。
-- ############################################################

-- ---------- 3.1 新增 model_id 列（模型主键快照，可空） ----------
-- 落账时取自定价缓存（ai_model.id），六维链路定价未命中时为 NULL，
-- 可退化为 model_name 关联模型表
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS model_id BIGINT;

COMMENT ON COLUMN ai_billing_record.model_id IS '模型主键（算力平台 ai_model.id 快照，落账时取自定价缓存；定价未命中或历史数据为 NULL，可退化为 model_name 关联）';

-- ---------- 3.2 新增 response_snapshot 列（模型返回响应快照，可空） ----------
-- JSON 结构：{"protocol","success","httpStatus","errorCode","errorMessage",
--            "usage","body","bodyTruncated","capturedAt"}
-- 应用层截断至 8KB 且 base64 素材已脱敏；构建失败与旧数据为 NULL
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS response_snapshot TEXT;

COMMENT ON COLUMN ai_billing_record.response_snapshot IS '模型返回响应快照（JSON，截断至 8KB，base64 素材已脱敏；含协议/HTTP状态/错误码/错误信息/usage 与截断响应体；旧数据与构建失败为 NULL）';

-- ---------- 3.3 模型主键索引（按模型维度对账/统计） ----------
CREATE INDEX IF NOT EXISTS idx_billing_model_id ON ai_billing_record (model_id);


-- ############################################################
-- 第 4 段（原 V6）：视频任务留档表新增 platform_user 列
--   背景：后台轮询结算（VideoTaskScheduler → VideoTaskSettler.
--         rebuildIdentityFromEntity）无请求上下文，原实现硬编码
--         platformUser=false，导致企业用户经轮询路径结算时丢失
--         enterprise_discount_rate（企业折扣），用户主动查询路径
--         结算则正常——同一任务两条路径折扣不一致。
--   方案：任务创建时把 UserIdentity.platformUser 持久化到本列，
--         轮询重建身份时读回原值，使两条结算路径语义一致。
--   取值：true=算力平台 API Key 认证（ai_api_key 同步用户，参与
--         企业折扣/余额校验）；false=本地 API Key / JWT / SYSTEM。
--         历史数据（存量任务）为 NULL，重建身份时按 false 兜底——
--         即存量任务维持原行为（轮询结算无企业折扣），仅新任务修复。
-- ############################################################

-- ---------- 4.1 新增 platform_user 列（可空，历史数据为 NULL） ----------
ALTER TABLE ai_video_task ADD COLUMN IF NOT EXISTS platform_user BOOLEAN;

COMMENT ON COLUMN ai_video_task.platform_user IS '平台用户标记（任务创建时 UserIdentity.platformUser 快照）：true=算力平台 API Key 用户（参与企业折扣/余额校验），false=本地 API Key/JWT/SYSTEM；历史数据为 NULL，轮询重建身份时按 false 兜底';


-- ############################################################
-- 第 5 段（原 V7）：视频任务留档表新增 billing_rule_snapshot 列
--   背景：视频生成任务按「提交时不计费、成功后按实际用量结算」的
--         异步计费模式，结算链路按实时定价缓存匹配分辨率价格规则；
--         若平台侧在任务提交后调整/删除价格规则（如删除 480P 规则
--         行），结算时规则匹配失败即拒计费（billing_record_id 留
--         NULL 人工对账），造成已提交任务漏计费。
--   方案：任务创建留档时把提交时刻生效的视频计费规则快照到本列
--         （priceMode + billingUnit + modelId + 启用规则行 rules，
--         price 为已转换单价），结算时优先用快照计价——提交时刻
--         锁价（业界异步任务惯例 price at request time），平台侧
--         后续改价/删规则不影响已提交任务的账单生成；快照缺失
--         （创建时定价未命中/非视频模型）时结算回退实时查询定价，
--         兼容历史数据与存量任务。
-- ############################################################

-- ---------- 5.1 新增 billing_rule_snapshot 列（可空，历史数据为 NULL） ----------
ALTER TABLE ai_video_task ADD COLUMN IF NOT EXISTS billing_rule_snapshot JSONB;

COMMENT ON COLUMN ai_video_task.billing_rule_snapshot IS '视频计费规则快照（任务创建时锁定提交时刻的计费规则：priceMode+billingUnit+modelId+rules 启用规则行，price 为已转换单价；结算时优先用快照计价，平台侧后续改价/删规则不影响已提交任务账单；创建时定价未命中为 NULL，结算回退实时定价）';


-- ============================================================
-- 校验 DML（执行后自查）
-- ============================================================

-- 1. 确认新列已存在（应返回 6 行）
SELECT table_name, column_name
FROM information_schema.columns
WHERE (table_name = 'ai_channel'       AND column_name = 'video_url')
   OR (table_name = 'ai_video_task'    AND column_name IN ('has_video_input', 'platform_user', 'billing_rule_snapshot'))
   OR (table_name = 'ai_billing_record' AND column_name IN ('usage_detail', 'model_id', 'response_snapshot'))
ORDER BY table_name, column_name;

-- 2. 确认 ai_video_task 表已建（应返回 1 行）
SELECT table_name
FROM information_schema.tables
WHERE table_name = 'ai_video_task';

-- 3. 确认索引已建（应返回 7 行：ai_video_task 6 个 + ai_billing_record 1 个）
SELECT tablename, indexname
FROM pg_indexes
WHERE indexname IN ('uk_video_task_no', 'idx_video_task_upstream', 'idx_video_task_user',
                    'idx_video_task_api_key', 'idx_video_task_model', 'idx_video_task_submitted',
                    'idx_billing_model_id')
ORDER BY tablename, indexname;

-- 4. 确认存量数据无异常（应为 0 行）
SELECT COUNT(*) AS abnormal_rows
FROM ai_billing_record
WHERE service_type = 'vidGen' AND usage_detail IS NULL AND total_cost > 0;


-- ============================================================
-- 配套元数据提示（平台侧配置，非本脚本执行内容）
-- ============================================================
-- 1. ai_model 新增 Seedance 视频模型记录：model_type=3（字典"视频生成"）、
--    real_name 与方舟 Model ID 一致（如 doubao-seedance-2-5-*）、
--    vendor=volcengine、base_url=https://ark.cn-beijing.volces.com
--    （host 根，路径由渠道 video_url 提供）、status=1 上架
-- 2. ai_model 视频模型（model_type=3）配置 price_mode（1=统一价格，
--    2=按条件定价）与 billing_unit（second/token）；
--    token 计费字段（input_price/output_price/billing_mode 等）
--    保持置空、启用标志置 false，网关已不再按 token 字段为视频模型计价；
-- 3. ai_model_video_price 为视频模型配置分辨率价格规则行：
--    output_resolution 取 480P/720P/1080P/4K，统一价格模式
--    has_video_input 为 NULL，按条件定价模式填 true/false；
--    enabled 默认 true，停用规则不参与计费；
-- 4. 模型管理页：model_type 下拉增加"视频生成"（数字编码 3）；
--    二期账单展示页支持 service_type=vidGen
-- 5. ai_user_free_quota 不为 vidGen 开启（文本类专属），无需改动
-- 6. 配置完成后：网关 POST /internal/refresh 使视频模型进入实例缓存生效


-- ============================================================
-- 回滚 SQL（默认注释；如需撤销本次变更，取消语句注释后按正向逆序执行）
-- ============================================================

-- ---------- 第 5 段回滚（原 V7） ----------
-- 删除 billing_rule_snapshot 列
-- ALTER TABLE ai_video_task DROP COLUMN IF EXISTS billing_rule_snapshot;

-- ---------- 第 4 段回滚（原 V6） ----------
-- 删除 platform_user 列
-- ALTER TABLE ai_video_task DROP COLUMN IF EXISTS platform_user;

-- ---------- 第 3 段回滚（原 V5） ----------
-- 删除模型主键索引
-- DROP INDEX IF EXISTS idx_billing_model_id;
-- 删除响应快照列
-- ALTER TABLE ai_billing_record DROP COLUMN IF EXISTS response_snapshot;
-- 删除模型主键列
-- ALTER TABLE ai_billing_record DROP COLUMN IF EXISTS model_id;

-- ---------- 第 2 段回滚（原 V4） ----------
-- 删除账单用量明细快照列
-- ALTER TABLE ai_billing_record DROP COLUMN IF EXISTS usage_detail;
-- 删除视频输入标记列
-- ALTER TABLE ai_video_task DROP COLUMN IF EXISTS has_video_input;

-- ---------- 第 1 段回滚（原 V3） ----------
-- 删除留档表（连带 6 个索引一并删除）
-- DROP TABLE IF EXISTS ai_video_task;
-- 删除视频生成服务级 URL 列
-- ALTER TABLE ai_channel DROP COLUMN IF EXISTS video_url;
-- 存量库列宽回退（如需恢复 128，谨慎：超长数据会丢失）
-- ALTER TABLE ai_video_task ALTER COLUMN upstream_task_id TYPE VARCHAR(128);
