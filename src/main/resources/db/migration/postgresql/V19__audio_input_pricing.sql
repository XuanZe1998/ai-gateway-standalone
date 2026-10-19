-- Audio input/cache are disjoint from text input/cache. Historical records remain NULL.
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS audio_input_tokens BIGINT;
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS audio_cache_hit_tokens BIGINT;
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS audio_input_unit_price NUMERIC(20,10);
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS audio_cache_hit_unit_price NUMERIC(20,10);
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS audio_input_cost NUMERIC(20,6);
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS audio_cache_hit_cost NUMERIC(20,6);
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS audio_input_bill NUMERIC(20,2);
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS audio_cache_hit_bill NUMERIC(20,2);
-- Multimodal chat shares the text rule editor; these are prices per million tokens.
INSERT INTO ai_billing_dimension(group_key,dimension_key,display_name,unit,value_type,sort_order,enabled,created_at,updated_at)
VALUES ('text','audioInput','音频输入（未命中缓存）','元 / 百万 Token','price',20,true,now(),now()),
       ('text','audioCacheHit','音频缓存命中输入','元 / 百万 Token','price',21,true,now(),now())
ON CONFLICT (group_key,dimension_key) DO NOTHING;
