-- ============================================================
-- 视频任务留档表 ai_video_task 新增 billing_rule_snapshot 列
-- 版本：V7
-- 背景：视频生成任务采用「提交时不计费、成功后按实际用量结算」
--       的异步计费模式，结算链路按实时定价缓存匹配分辨率价格
--       规则。若平台侧在任务提交后调整/删除价格规则（如删除
--       480P 规则行），结算时规则匹配失败即拒计费（billing_
--       record_id 留 NULL 人工对账），造成已提交任务漏计费。
-- 方案：任务创建留档时，把提交时刻生效的视频计费规则快照到
--       本列（价格模式 priceMode + 计费单位 billingUnit + 模型
--       主键 modelId + 启用规则行 rules），结算时优先用快照
--       计价——提交时刻锁价（业界异步任务惯例，price at
--       request time），平台侧后续改价/删规则不影响已提交
--       任务的账单生成。
-- 取值：JSONB，形如
--       {"priceMode":2,"billingUnit":"second","modelId":37,
--        "rules":[{"outputResolution":"720P","hasVideoInput":
--        false,"price":50.0}, ...]}
--       price 为已转换单价（second=元/秒、token=元/token）；
--       创建时定价缓存未命中/非视频模型为 NULL——结算回退
--       实时查询定价（兼容历史数据与存量任务）。
-- 执行：金仓 KingbaseES（PG 兼容模式）/ PostgreSQL，幂等
--       （IF NOT EXISTS），可重复执行，手工执行。
-- ★★★ 上线顺序强约束 ★★★：本脚本必须先于网关新版本部署执行
--      （JPA 实体映射了数据库中不存在的列会导致落档 SQL 报错）。
-- ============================================================


-- ============================================================
-- 正向 SQL
-- ============================================================

-- ---------- 一、新增 billing_rule_snapshot 列（可空，历史数据为 NULL） ----------
ALTER TABLE ai_video_task ADD COLUMN IF NOT EXISTS billing_rule_snapshot JSONB;

COMMENT ON COLUMN ai_video_task.billing_rule_snapshot IS '视频计费规则快照（任务创建时锁定提交时刻的计费规则：priceMode+billingUnit+modelId+rules 启用规则行，price 为已转换单价；结算时优先用快照计价，平台侧后续改价/删规则不影响已提交任务账单；创建时定价未命中为 NULL，结算回退实时定价）';


-- ============================================================
-- 回滚 SQL（默认注释；如需撤销本次变更，取消注释后执行）
-- ============================================================

-- 一的回滚：删除 billing_rule_snapshot 列
-- ALTER TABLE ai_video_task DROP COLUMN IF EXISTS billing_rule_snapshot;
