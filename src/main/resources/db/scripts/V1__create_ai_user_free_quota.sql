-- ============================================================
-- 免费额度表 ai_user_free_quota
-- 由 JPA 自动建表；此脚本作为手工执行/运维参考
-- ============================================================

CREATE TABLE IF NOT EXISTS ai_user_free_quota (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(64) NOT NULL,
    total_quota     BIGINT NOT NULL DEFAULT 1000000,
    used_quota      BIGINT NOT NULL DEFAULT 0,
    remaining_quota BIGINT NOT NULL DEFAULT 1000000,
    trial_exhausted BOOLEAN NOT NULL DEFAULT false,
    exhausted_at    TIMESTAMP,
    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    creator         VARCHAR(64),
    updater         VARCHAR(64),
    deleted         BOOLEAN NOT NULL DEFAULT false,
    version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_ai_user_free_quota_user_id UNIQUE (user_id)
);

CREATE INDEX IF NOT EXISTS idx_ai_user_free_quota_user_id ON ai_user_free_quota (user_id);

COMMENT ON TABLE ai_user_free_quota IS '用户免费 token 额度表';
COMMENT ON COLUMN ai_user_free_quota.id IS '自增主键';
COMMENT ON COLUMN ai_user_free_quota.user_id IS '用户ID，对应 sldd_system_users.user_id';
COMMENT ON COLUMN ai_user_free_quota.total_quota IS '免费额度总量(tokens)';
COMMENT ON COLUMN ai_user_free_quota.used_quota IS '已用免费额度(tokens)';
COMMENT ON COLUMN ai_user_free_quota.remaining_quota IS '剩余免费额度(tokens)';
COMMENT ON COLUMN ai_user_free_quota.trial_exhausted IS '是否已触发超支失败：true=已锁定，后续请求走余额逻辑';
COMMENT ON COLUMN ai_user_free_quota.exhausted_at IS '触发超支失败的时间';
COMMENT ON COLUMN ai_user_free_quota.create_time IS '创建时间';
COMMENT ON COLUMN ai_user_free_quota.update_time IS '最后更新时间';
COMMENT ON COLUMN ai_user_free_quota.creator IS '创建者';
COMMENT ON COLUMN ai_user_free_quota.updater IS '最后更新者';
COMMENT ON COLUMN ai_user_free_quota.deleted IS '是否删除：false=未删除，true=已逻辑删除';
COMMENT ON COLUMN ai_user_free_quota.version IS '乐观锁版本号';

-- ai_billing_record 新增免费额度字段
ALTER TABLE ai_billing_record
    ADD COLUMN IF NOT EXISTS is_free_quota BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE ai_billing_record
    ADD COLUMN IF NOT EXISTS free_quota_consumed BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN ai_billing_record.is_free_quota IS '是否命中免费额度：true=本次请求走免费额度，金额字段为0';
COMMENT ON COLUMN ai_billing_record.free_quota_consumed IS '本次实际消耗的免费 token 数';

-- ============================================================
-- 常用 DML 示例
-- ============================================================

-- 1. 初始化用户免费额度（算力平台执行）
INSERT INTO ai_user_free_quota (
    user_id, total_quota, used_quota, remaining_quota,
    trial_exhausted, create_time, update_time, creator, updater, deleted, version
) VALUES (
    'u123456', 1000000, 0, 1000000,
    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system', false, 0
);

-- 2. 查询用户剩余额度
SELECT id, user_id, total_quota, used_quota, remaining_quota,
       trial_exhausted, version
FROM ai_user_free_quota
WHERE user_id = 'u123456' AND deleted = false;

-- 3. 查询试用账单（后台映射金额字段为 '-'）
SELECT id, user_id, api_key_id, api_key_name, model_name, service_type,
       total_tokens, is_free_quota, free_quota_consumed,
       original_cost, discount_amount, total_cost
FROM ai_billing_record
WHERE is_free_quota = true AND is_deleted = false
ORDER BY started_at DESC;

-- 4. 查询额度使用统计
SELECT user_id, total_quota, used_quota, remaining_quota,
       ROUND(used_quota * 100.0 / total_quota, 2) AS usage_rate,
       trial_exhausted, exhausted_at
FROM ai_user_free_quota
WHERE user_id = 'u123456' AND deleted = false;
