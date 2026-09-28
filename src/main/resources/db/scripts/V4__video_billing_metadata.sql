-- ============================================================
-- 视频模型计费元数据升级配套脚本（网关表变更）
-- 版本：V4
-- 背景：算力平台元数据源已升级视频模型计费方式——ai_model 新增
--       price_mode（1=统一价格/2=按条件定价）与 billing_unit
--       （second=元/秒/token=元/M token），新增子表
--       ai_model_video_price（输出分辨率 × 有无视频输入的价格规则，
--       含 enabled 启停标志）；视频模型已废弃 token 计费字段。
--       网关侧配套两处表结构变更：
--       1. ai_video_task 新增 has_video_input 列（创建链路从请求
--          content 解析「本次调用是否有视频输入」，供按条件定价
--          price_mode=2 匹配规则使用，上游响应不含该信息）；
--       2. ai_billing_record 新增 usage_detail JSONB 列（视频模型
--          账单回写实际消耗明细：计费单位 + 分辨率分项用量/费用）。
-- 归属：ai_video_task / ai_billing_record 同库（网关自有 schema
--       sldd-{profile}）。
--
-- ★★★ 上线顺序强约束 ★★★
--   1. 本脚本必须先于网关新版本部署执行完毕：JPA 实体映射了数据库
--      中不存在的列（has_video_input / usage_detail）会导致网关
--      SQL 报错、服务启动/运行时崩溃。
--   2. ★ 平台侧 ai_model 新列（price_mode / billing_unit）与
--      ai_model_video_price 表必须先于网关新版本发布 ★：
--      PlatformModelEntity 映射了这两列，若网关先发布而平台表未
--      加列，定价同步 SELECT 将报列不存在 → 定价缓存为空 →
--      所有计费链路（含六维 token）按 0 元落账。
--   3. 平台侧视频模型 price_mode/billing_unit 未配置或
--      ai_model_video_price 无启用规则行（含价格为空/非正数的行）时，
--      计费侧按「拒计费留人工对账」兜底（billing_record_id 置 NULL，
--      口径：status=succeeded AND billing_record_id IS NULL）。
-- 说明：ddl-auto: none，表结构只由本脚本管理；金仓 KingbaseES
--       （PG 兼容模式）/ PostgreSQL，幂等（IF NOT EXISTS），
--       可重复执行，手工执行。
-- ============================================================


-- ============================================================
-- 正向 SQL
-- ============================================================

-- ---------- 一、ai_video_task 新增 has_video_input 列 ----------
-- 本次调用是否有视频输入（请求 content 含 type=video_url 项）：
-- true=有 / false=无 / null=无法判定（content 缺失或非数组）。
-- 视频模型按条件定价（price_mode=2）时按「输出分辨率 × 有无视频
-- 输入」匹配 ai_model_video_price 规则行。
ALTER TABLE ai_video_task ADD COLUMN IF NOT EXISTS has_video_input BOOLEAN;

COMMENT ON COLUMN ai_video_task.has_video_input IS '本次调用是否有视频输入（请求 content 含 type=video_url 项）：true=有/false=无/null=无法判定；视频模型按条件定价（price_mode=2）匹配价格规则用';


-- ---------- 二、ai_billing_record 新增 usage_detail JSONB 列 ----------
-- 视频模型账单实际消耗明细快照：
--   {"billingUnit":"second|token","items":[
--     {"resolution":"720P","hasVideoInput":false,"seconds":5,"tokens":null,
--      "cost":0.10,"discountAmount":0.05,"amount":0.05}]}
-- second 单位时 seconds 为计量秒数（frames 折算可为小数）、tokens 为 null；
-- token 单位时 seconds 为 null、tokens 为 token 数；
-- cost/discountAmount/amount 与 original_cost/discount_amount/total_cost 一致。
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS usage_detail JSONB;

COMMENT ON COLUMN ai_billing_record.usage_detail IS '视频模型账单实际消耗明细快照（JSONB）：billingUnit + items 分项（分辨率/有无视频输入/秒数/token/分项费用），仅视频模型账单有值';


-- ============================================================
-- 校验 DML（执行后自查）
-- ============================================================

-- 1. 确认新列已存在（各应返回 2 行）
SELECT table_name, column_name
FROM information_schema.columns
WHERE table_name IN ('ai_video_task', 'ai_billing_record')
  AND column_name IN ('has_video_input', 'usage_detail')
ORDER BY table_name, column_name;

-- 2. 确认存量数据无异常（应为 0 行）
SELECT COUNT(*) AS abnormal_rows
FROM ai_billing_record
WHERE service_type = 'vidGen' AND usage_detail IS NULL AND total_cost > 0;


-- ============================================================
-- 配套元数据提示（平台侧配置，非本脚本执行内容）
-- ============================================================
-- 1. ai_model 视频模型（model_type=3）配置 price_mode（1=统一价格，
--    2=按条件定价）与 billing_unit（second/token）；
--    token 计费字段（input_price/output_price/billing_mode 等）
--    保持置空、启用标志置 false，网关已不再按 token 字段为视频模型计价；
-- 2. ai_model_video_price 为视频模型配置分辨率价格规则行：
--    output_resolution 取 480P/720P/1080P/4K，统一价格模式
--    has_video_input 为 NULL，按条件定价模式填 true/false；
--    enabled 默认 true，停用规则不参与计费；
-- 3. 平台侧发布完成后，网关 POST /internal/refresh 使新定价进入缓存生效。


-- ============================================================
-- 回滚 SQL（默认注释；如需撤销本次变更，取消语句注释后按正向逆序执行）
-- ============================================================

-- 二的回滚：删除账单用量明细快照列
-- ALTER TABLE ai_billing_record DROP COLUMN IF EXISTS usage_detail;

-- 一的回滚：删除视频输入标记列
-- ALTER TABLE ai_video_task DROP COLUMN IF EXISTS has_video_input;
