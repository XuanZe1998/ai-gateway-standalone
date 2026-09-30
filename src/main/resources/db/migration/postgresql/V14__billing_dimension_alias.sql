-- 计费维度显示别名（仅展示层，覆盖默认名称；底层六维计费字段不变）。
-- 按计费组全局生效：text（chat/embedding/rerank）、voice（tts/stt）、image（imgGen/imgEdit）；
-- 视频组（vidGen）一期接入定价后再补。
-- dimension_key 与前端策略键一致：normalPrice/output/cacheHit/cacheCreate/cacheHitExplicit/thinking/discount
CREATE TABLE IF NOT EXISTS billing_dimension_alias (
    id BIGSERIAL PRIMARY KEY,
    group_key VARCHAR(16) NOT NULL,
    dimension_key VARCHAR(32) NOT NULL,
    display_name VARCHAR(60) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_billing_dimension_alias UNIQUE (group_key, dimension_key)
);
