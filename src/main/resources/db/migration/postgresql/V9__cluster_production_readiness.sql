-- First Flyway-managed release. Databases created before 2.6.11 are baselined at V8.
-- This migration is intentionally idempotent enough for reviewed recovery, while Flyway
-- remains the sole normal executor in production.

ALTER TABLE config_data
    ADD COLUMN IF NOT EXISTS lock_version BIGINT NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX IF NOT EXISTS uk_config_data_key_version
    ON config_data (config_key, version);

ALTER TABLE ai_user_free_quota
    ADD COLUMN IF NOT EXISTS quota_tier VARCHAR(32) NOT NULL DEFAULT 'STUDENT';

ALTER TABLE ai_user_free_quota
    ADD COLUMN IF NOT EXISTS period_start TIMESTAMP;

ALTER TABLE ai_user_free_quota
    ADD COLUMN IF NOT EXISTS period_end TIMESTAMP;

UPDATE ai_user_free_quota
SET period_start = date_trunc('month', CURRENT_TIMESTAMP),
    period_end = date_trunc('month', CURRENT_TIMESTAMP) + INTERVAL '1 month'
WHERE period_start IS NULL OR period_end IS NULL;

ALTER TABLE ai_user_free_quota
    ALTER COLUMN period_start SET NOT NULL;

ALTER TABLE ai_user_free_quota
    ALTER COLUMN period_end SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ai_user_free_quota_period_end
    ON ai_user_free_quota (period_end)
    WHERE deleted = FALSE;

COMMENT ON COLUMN ai_user_free_quota.quota_tier IS
    'Campus quota tier selected from the authenticated role: STUDENT, STAFF or TEACHER';
COMMENT ON COLUMN ai_user_free_quota.period_start IS 'Inclusive start of the monthly quota period';
COMMENT ON COLUMN ai_user_free_quota.period_end IS 'Exclusive end of the monthly quota period';
