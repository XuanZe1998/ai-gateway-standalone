-- ============================================================
-- 视频生成 API 上线脚本（同一数据库：平台表 + 网关表变更合并）
-- 版本：V3
-- 背景：视频生成 API 一、二阶段上线。异步任务「创建 → 轮询查询
--       → 成功后按用量结算」全链路涉及本库两处表结构变更：
--       1. 平台表 ai_channel 新增 video_url 列（渠道视频服务级 URL）；
--       2. 网关表 ai_video_task 建表（留档追溯，一阶段创建链路；含终态
--          响应快照列，二阶段查询链路复用）。
-- 归属：与 ai_billing_record 同库（网关自有 schema sldd-{profile}；
--       平台表 ai_channel 如分属不同 schema，请切换 schema 后执行对应段落）。
--
-- ★★★ 上线顺序强约束 ★★★
--   1. JPA 实体映射了数据库中不存在的列会导致网关查询 SQL 报错
--      （渠道同步 SELECT 含 video_url、留档 SELECT 含 response_snapshot），
--      因此本脚本必须先于网关新版本部署执行完毕。
--   2. 渠道需为视频模型配置 video_url（纯路径如
--      /api/v3/contents/generations/tasks，或完整 URL；推荐纯路径，
--      渠道 base_url 配 host 根）。若未配置，detectPath 对视频类型
--      兜底返回 /v1/videos/generations，仅适用于 OpenAI 兼容供应商
--      （渠道 baseUrl 为 host 根）；方舟渠道不可用 base_url 配完整
--      任务地址替代 video_url（实测会拼接出
--      .../tasks/v1/videos/generations 错误地址，勿采用）。
-- 说明：快照列使用 TEXT；base64 素材在应用层脱敏截断后入库，
--       防止大素材撑爆存储。ddl-auto: none，表结构只由本脚本管理。
-- 执行：金仓 KingbaseES（PG 兼容模式）/ PostgreSQL，幂等（IF NOT EXISTS），
--       可重复执行，手工执行。
-- ============================================================


-- ============================================================
-- 正向 SQL
-- ============================================================

-- ---------- 一、平台表 ai_channel 新增 video_url 列 ----------
-- 视频生成服务级 URL：支持完整 URL（http/https 开头，直接用作 baseUrl）
-- 或纯路径（/xxx，拼接到模型/渠道 baseUrl 之后），优先于模型/渠道 baseUrl
ALTER TABLE ai_channel ADD COLUMN IF NOT EXISTS video_url VARCHAR(500);

COMMENT ON COLUMN ai_channel.video_url IS '视频生成服务级 URL（支持完整 URL 或纯路径，优先于模型/渠道 baseUrl）';


-- ---------- 二、网关表 ai_video_task 建表（留档追溯） ----------
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

-- ---------- 索引 ----------
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


-- ---------- 存量库列宽修正（幂等，可重复执行） ----------
-- 已按旧版 128 列宽建过 ai_video_task 的库，需执行本语句修正为 512；
-- 新建库无副作用（类型一致时重声明安全）。
ALTER TABLE ai_video_task ALTER COLUMN upstream_task_id TYPE VARCHAR(512);


-- ---------- 表/列注释（全量） ----------
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


-- ============================================================
-- 配套元数据提示（平台侧配置，非本脚本执行内容）
-- ============================================================
-- 1. ai_model 新增 Seedance 视频模型记录：model_type=3（字典"视频生成"）、
--    real_name 与方舟 Model ID 一致（如 doubao-seedance-2-5-*）、
--    vendor=volcengine、base_url=https://ark.cn-beijing.volces.com
--    （host 根，路径由渠道 video_url 提供）、status=1 上架
-- 2. 定价配置（二期计费预备）：方舟视频模型按 token 计费且输入 token=0，
--    input_price=0、output_price 配元/M token（由元/秒刊例价折算）
-- 3. 模型管理页：model_type 下拉增加"视频生成"（数字编码 3）；
--    二期账单展示页支持 service_type=vidGen
-- 4. ai_user_free_quota 不为 vidGen 开启（文本类专属），无需改动
-- 5. 配置完成后：网关 POST /internal/refresh 使视频模型进入实例缓存生效


-- ============================================================
-- 回滚 SQL（默认注释；如需撤销本次变更，取消语句注释后按正向逆序执行）
-- ============================================================

-- 二的回滚：删除留档表（连带 6 个索引一并删除）
-- DROP TABLE IF EXISTS ai_video_task;

-- 一的回滚：删除视频生成服务级 URL 列
-- ALTER TABLE ai_channel DROP COLUMN IF EXISTS video_url;

-- 存量库列宽回退（如需恢复 128，谨慎：超长数据会丢失）
-- ALTER TABLE ai_video_task ALTER COLUMN upstream_task_id TYPE VARCHAR(128);
