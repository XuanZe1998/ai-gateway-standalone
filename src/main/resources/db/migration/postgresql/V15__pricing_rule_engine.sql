-- 定价规则引擎：维度定义表 + 计费规则表（数据驱动，替代六维硬编码与 token 阶梯表）。
-- 规则计费（billing_mode=3）专用；整体/阶梯老逻辑仍走 ai_model / ai_model_price_tier。

-- 计费维度定义（替代六维硬编码 + 合并 billing_dimension_alias 功能）
CREATE TABLE IF NOT EXISTS ai_billing_dimension (
    id            BIGSERIAL PRIMARY KEY,
    group_key     VARCHAR(16)  NOT NULL,
    dimension_key VARCHAR(32)  NOT NULL,
    display_name  VARCHAR(60)  NOT NULL,
    unit          VARCHAR(20)  NOT NULL,
    value_type    VARCHAR(10)  NOT NULL,
    sort_order    INT          NOT NULL DEFAULT 0,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_billing_dimension UNIQUE (group_key, dimension_key)
);

-- 计费规则（条件树 + 动态维度价格；替代 ai_model_price_tier）
CREATE TABLE IF NOT EXISTS ai_model_price_rule (
    id         BIGSERIAL PRIMARY KEY,
    model_id   BIGINT       NOT NULL,
    rule_name  VARCHAR(100) NOT NULL,
    match_json JSONB        NOT NULL,
    price_json JSONB        NOT NULL,
    priority   INT          NOT NULL DEFAULT 100,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_price_rule_model ON ai_model_price_rule (model_id, priority);
CREATE UNIQUE INDEX IF NOT EXISTS uq_price_rule_priority ON ai_model_price_rule (model_id, priority);