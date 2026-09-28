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


-- ============================================================
-- 正向 SQL
-- ============================================================

-- ---------- 一、新增 model_id 列（模型主键快照，可空） ----------
-- 落账时取自定价缓存（ai_model.id），六维链路定价未命中时为 NULL，
-- 可退化为 model_name 关联模型表
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS model_id BIGINT;

COMMENT ON COLUMN ai_billing_record.model_id IS '模型主键（算力平台 ai_model.id 快照，落账时取自定价缓存；定价未命中或历史数据为 NULL，可退化为 model_name 关联）';

-- ---------- 二、新增 response_snapshot 列（模型返回响应快照，可空） ----------
-- JSON 结构：{"protocol","success","httpStatus","errorCode","errorMessage",
--            "usage","body","bodyTruncated","capturedAt"}
-- 应用层截断至 8KB 且 base64 素材已脱敏；构建失败与旧数据为 NULL
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
