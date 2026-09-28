-- ============================================================
-- 视频任务留档表 ai_video_task 新增 platform_user 列
-- 版本：V6
-- 背景：后台轮询结算（VideoTaskScheduler → VideoTaskSettler.
--       rebuildIdentityFromEntity）无请求上下文，原实现硬编码
--       platformUser=false，导致企业用户经轮询路径结算时丢失
--       enterprise_discount_rate（企业折扣），用户主动查询路径
--       结算则正常——同一任务两条路径折扣不一致。
-- 方案：任务创建时把 UserIdentity.platformUser 持久化到本列，
--       轮询重建身份时读回原值，使两条结算路径语义一致。
-- 取值：true=算力平台 API Key 认证（ai_api_key 同步用户，参与
--       企业折扣/余额校验）；false=本地 API Key / JWT / SYSTEM。
--       历史数据（存量任务）为 NULL，重建身份时按 false 兜底——
--       即存量任务维持原行为（轮询结算无企业折扣），仅新任务修复。
-- 执行：金仓 KingbaseES（PG 兼容模式）/ PostgreSQL，幂等
--       （IF NOT EXISTS），可重复执行，手工执行。
-- ★★★ 上线顺序强约束 ★★★：本脚本必须先于网关新版本部署执行
--      （JPA 实体映射了数据库中不存在的列会导致落档 SQL 报错）。
-- ============================================================


-- ============================================================
-- 正向 SQL
-- ============================================================

-- ---------- 一、新增 platform_user 列（可空，历史数据为 NULL） ----------
ALTER TABLE ai_video_task ADD COLUMN IF NOT EXISTS platform_user BOOLEAN;

COMMENT ON COLUMN ai_video_task.platform_user IS '平台用户标记（任务创建时 UserIdentity.platformUser 快照）：true=算力平台 API Key 用户（参与企业折扣/余额校验），false=本地 API Key/JWT/SYSTEM；历史数据为 NULL，轮询重建身份时按 false 兜底';


-- ============================================================
-- 回滚 SQL（默认注释；如需撤销本次变更，取消注释后执行）
-- ============================================================

-- 一的回滚：删除 platform_user 列
-- ALTER TABLE ai_video_task DROP COLUMN IF EXISTS platform_user;
