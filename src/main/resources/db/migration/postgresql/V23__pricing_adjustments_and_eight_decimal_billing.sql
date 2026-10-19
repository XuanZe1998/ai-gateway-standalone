-- Quote adjustment scopes: GLOBAL or MODEL:<normalized model name>.
CREATE TABLE IF NOT EXISTS ai_pricing_adjustment (
    scope_key VARCHAR(300) PRIMARY KEY, enabled BOOLEAN NOT NULL, mode VARCHAR(20) NOT NULL,
    adjustment_value NUMERIC(20,8) NOT NULL, version BIGINT,
    CONSTRAINT ck_pricing_adjustment_mode CHECK (mode IN ('PERCENTAGE','FIXED')),
    CONSTRAINT ck_pricing_adjustment_percentage CHECK (mode <> 'PERCENTAGE' OR adjustment_value BETWEEN 0 AND 100)
);
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS fixed_adjustment NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN input_cost TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN cache_hit_cost TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN cache_create_cost TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN cache_hit_explicit_cost TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN output_cost TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN thinking_cost TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN audio_input_cost TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN audio_cache_hit_cost TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN input_bill TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN cache_hit_bill TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN cache_create_bill TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN cache_hit_explicit_bill TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN output_bill TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN thinking_bill TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN audio_input_bill TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN audio_cache_hit_bill TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN original_cost TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN discount_amount TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN total_cost TYPE NUMERIC(20,8);
ALTER TABLE ai_billing_record ALTER COLUMN discount_rate TYPE NUMERIC(12,8);
ALTER TABLE ai_billing_record ALTER COLUMN user_discount_rate TYPE NUMERIC(12,8);
ALTER TABLE ai_billing_record ALTER COLUMN enterprise_discount_rate TYPE NUMERIC(12,8);
ALTER TABLE ai_billing_record ALTER COLUMN final_discount_rate TYPE NUMERIC(12,8);
ALTER TABLE ai_billing_record ALTER COLUMN input_unit_price TYPE NUMERIC(26,14);
ALTER TABLE ai_billing_record ALTER COLUMN cache_hit_input_unit_price TYPE NUMERIC(26,14);
ALTER TABLE ai_billing_record ALTER COLUMN output_unit_price TYPE NUMERIC(26,14);
ALTER TABLE ai_billing_record ALTER COLUMN cache_create_input_unit_price TYPE NUMERIC(26,14);
ALTER TABLE ai_billing_record ALTER COLUMN cache_hit_explicit_input_unit_price TYPE NUMERIC(26,14);
ALTER TABLE ai_billing_record ALTER COLUMN thinking_unit_price TYPE NUMERIC(26,14);
ALTER TABLE ai_billing_record ALTER COLUMN audio_input_unit_price TYPE NUMERIC(26,14);
ALTER TABLE ai_billing_record ALTER COLUMN audio_cache_hit_unit_price TYPE NUMERIC(26,14);
ALTER TABLE ai_model ALTER COLUMN input_price TYPE NUMERIC(20,8);
ALTER TABLE ai_model ALTER COLUMN cache_hit_input_price TYPE NUMERIC(20,8);
ALTER TABLE ai_model ALTER COLUMN output_price TYPE NUMERIC(20,8);
ALTER TABLE ai_model ALTER COLUMN cache_create_input_price TYPE NUMERIC(20,8);
ALTER TABLE ai_model ALTER COLUMN cache_hit_explicit_input_price TYPE NUMERIC(20,8);
ALTER TABLE ai_model ALTER COLUMN thinking_price TYPE NUMERIC(20,8);
ALTER TABLE ai_model_price_tier ALTER COLUMN input_price TYPE NUMERIC(20,8);
ALTER TABLE ai_model_price_tier ALTER COLUMN cache_hit_input_price TYPE NUMERIC(20,8);
ALTER TABLE ai_model_price_tier ALTER COLUMN output_price TYPE NUMERIC(20,8);
ALTER TABLE ai_model_price_tier ALTER COLUMN cache_create_input_price TYPE NUMERIC(20,8);
ALTER TABLE ai_model_price_tier ALTER COLUMN cache_hit_explicit_input_price TYPE NUMERIC(20,8);
ALTER TABLE ai_model_price_tier ALTER COLUMN thinking_price TYPE NUMERIC(20,8);
ALTER TABLE ai_account_balance ALTER COLUMN balance TYPE NUMERIC(20,8);
ALTER TABLE ai_model_video_price ALTER COLUMN price TYPE NUMERIC(20,8);
ALTER TABLE ai_model_video_price ALTER COLUMN input_price TYPE NUMERIC(20,8);
