-- ============================================================
-- 计费表 ai_billing_record 六维分项计费升级
-- 版本：V2
-- 背景：JAiRouter 计费链路从「二维朴素计费（prompt/completion）」升级为
--       「六维分项计费（普通输入/缓存命中/显式缓存创建/显式缓存命中/普通输出/思考）
--         + 阶梯计费 + 思考 token 差异化定价」。
-- 说明：本脚本只含 JAiRouter 侧 ai_billing_record 的 DDL + 存量数据 DML。
--       算力平台侧 ai_model / ai_model_price_tier 变更已另行整理，不在本册。
-- 执行：PostgreSQL，幂等（IF NOT EXISTS），可重复执行。
-- ============================================================


-- ============================================================
-- 一、DDL：ai_billing_record 新增 19 列（全部可空，向后兼容旧数据）
-- ============================================================

-- ---------- 6 维 Token 用量（归一化后） ----------
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS normal_input_tokens          BIGINT;
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS cache_hit_tokens             BIGINT;
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS cache_create_explicit_tokens BIGINT;
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS cache_hit_explicit_tokens    BIGINT;
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS normal_output_tokens         BIGINT;
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS thinking_tokens              BIGINT;

-- ---------- 6 维价格快照（新增 4 个；input/output_unit_price 已存在） ----------
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS cache_hit_input_unit_price      DECIMAL(20,10);
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS cache_create_input_unit_price   DECIMAL(20,10);
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS cache_hit_explicit_input_unit_price  DECIMAL(20,10);
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS thinking_unit_price             DECIMAL(20,10);

-- ---------- 计费元数据快照 ----------
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS billing_mode           SMALLINT;
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS thinking_billing_mode  SMALLINT;
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS vendor                 VARCHAR(50);

-- ---------- 分项费用（折扣前，便于对账） ----------
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS input_cost             DECIMAL(20,6);
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS cache_hit_cost         DECIMAL(20,6);
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS cache_create_cost      DECIMAL(20,6);
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS cache_hit_explicit_cost DECIMAL(20,6);
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS output_cost            DECIMAL(20,6);
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS thinking_cost          DECIMAL(20,6);


-- ---------- 列注释 ----------
COMMENT ON COLUMN ai_billing_record.normal_input_tokens           IS '普通输入token（缓存未命中净量）= 厂商输入总量 − 各类缓存';
COMMENT ON COLUMN ai_billing_record.cache_hit_tokens              IS '缓存命中token（DeepSeek/OpenAI 隐式缓存命中）';
COMMENT ON COLUMN ai_billing_record.cache_create_explicit_tokens  IS '显式缓存创建token（Anthropic cache_creation_input_tokens）';
COMMENT ON COLUMN ai_billing_record.cache_hit_explicit_tokens     IS '显式缓存命中token（Anthropic cache_read_input_tokens）';
COMMENT ON COLUMN ai_billing_record.normal_output_tokens          IS '普通输出token = 输出总量 − 思考token';
COMMENT ON COLUMN ai_billing_record.thinking_tokens               IS '思考token（reasoning_tokens）';

COMMENT ON COLUMN ai_billing_record.cache_hit_input_unit_price      IS '缓存命中单价（元/token，快照，阶梯计费时为命中档位价）';
COMMENT ON COLUMN ai_billing_record.cache_create_input_unit_price   IS '显式缓存创建单价（元/token，快照）';
COMMENT ON COLUMN ai_billing_record.cache_hit_explicit_input_unit_price  IS '显式缓存命中单价（元/token，快照）';
COMMENT ON COLUMN ai_billing_record.thinking_unit_price             IS '思考token单价（元/token，快照，仅 thinking_billing_mode=2 有效）';

COMMENT ON COLUMN ai_billing_record.billing_mode          IS '计费模式快照：1=整体计费，2=阶梯计费';
COMMENT ON COLUMN ai_billing_record.thinking_billing_mode IS '思考token计费模式快照：1=并入输出，2=单独计费，3=不计费';
COMMENT ON COLUMN ai_billing_record.vendor                IS '厂商标识快照（deepseek/openai/glm/kimi/anthropic等）';

COMMENT ON COLUMN ai_billing_record.input_cost              IS '普通输入分项费用（元，折扣前）= normal_input_tokens × input_unit_price';
COMMENT ON COLUMN ai_billing_record.cache_hit_cost          IS '缓存命中分项费用（元，折扣前）';
COMMENT ON COLUMN ai_billing_record.cache_create_cost       IS '显式缓存创建分项费用（元，折扣前）';
COMMENT ON COLUMN ai_billing_record.cache_hit_explicit_cost IS '显式缓存命中分项费用（元，折扣前）';
COMMENT ON COLUMN ai_billing_record.output_cost             IS '普通输出分项费用（元，折扣前）';
COMMENT ON COLUMN ai_billing_record.thinking_cost           IS '思考token分项费用（元，折扣前）';


-- ============================================================
-- 二、DML：存量历史数据回填（让旧记录的 6 维口径可追溯，可选但建议执行）
-- ============================================================
-- 旧记录只有 prompt_tokens/completion_tokens/total_tokens 三维，无缓存/思考细分。
-- 回填策略（与代码中 usage==null 的兜底口径一致）：
--   normal_input  = prompt_tokens   （旧口径把全部输入当普通输入）
--   normal_output = completion_tokens（旧口径把全部输出当普通输出）
--   缓存/思考维度 = 0
--   分项费用按旧二维公式回填（input_cost = prompt×单价, output_cost = completion×单价）
-- 说明：此回填仅为「对账口径统一」，不改历史 original_cost/total_cost（已发生费用不动）。

UPDATE ai_billing_record
SET normal_input_tokens           = COALESCE(prompt_tokens, 0),
    cache_hit_tokens              = 0,
    cache_create_explicit_tokens  = 0,
    cache_hit_explicit_tokens     = 0,
    normal_output_tokens          = COALESCE(completion_tokens, 0),
    thinking_tokens               = 0,
    input_cost                    = ROUND(COALESCE(prompt_tokens, 0)::DECIMAL * COALESCE(input_unit_price, 0), 6),
    output_cost                   = ROUND(COALESCE(completion_tokens, 0)::DECIMAL * COALESCE(output_unit_price, 0), 6),
    cache_hit_cost                = 0,
    cache_create_cost             = 0,
    cache_hit_explicit_cost       = 0,
    thinking_cost                 = 0,
    billing_mode                  = 1,   -- 旧记录均为整体计费
    thinking_billing_mode         = 1    -- 旧记录思考并入输出
WHERE normal_input_tokens IS NULL;       -- 只回填未回填过的（幂等）


-- ============================================================
-- 三、校验 DML（执行后自查）
-- ============================================================

-- 1. 确认 19 列已存在
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'ai_billing_record'
  AND column_name IN (
      'normal_input_tokens','cache_hit_tokens','cache_create_explicit_tokens',
      'cache_hit_explicit_tokens','normal_output_tokens','thinking_tokens',
      'cache_hit_input_unit_price','cache_create_input_unit_price',
      'cache_hit_explicit_input_unit_price','thinking_unit_price',
      'billing_mode','thinking_billing_mode','vendor',
      'input_cost','cache_hit_cost','cache_create_cost',
      'cache_hit_explicit_cost','output_cost','thinking_cost'
  )
ORDER BY column_name;

-- 2. 确认历史回填行数（应等于升级前的总记录数）
SELECT COUNT(*) AS backfilled
FROM ai_billing_record
WHERE billing_mode = 1 AND normal_input_tokens IS NOT NULL;

-- 3. 抽样核对：6 维之和应 ≈ total_tokens（旧记录回填后：normal_input+normal_output = prompt+completion）
SELECT id, prompt_tokens, completion_tokens, total_tokens,
       normal_input_tokens, normal_output_tokens,
       (normal_input_tokens + normal_output_tokens) AS six_dim_sum
FROM ai_billing_record
WHERE is_deleted = false
ORDER BY id DESC
LIMIT 10;
