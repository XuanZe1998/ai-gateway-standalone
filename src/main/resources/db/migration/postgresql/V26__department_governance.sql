-- Administrator free-use policy is an idempotent prerequisite for department governance.
CREATE TABLE IF NOT EXISTS campus_user_usage_policy (
    owner_id BIGINT PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL UNIQUE,
    free_usage BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(500) NOT NULL DEFAULT '',
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE ai_billing_record ADD COLUMN IF NOT EXISTS is_admin_free_usage BOOLEAN NOT NULL DEFAULT FALSE;

-- Current membership is a directory, never an attribution source for historical bills.
-- CAS platform IDs can exceed the legacy free-quota table's 64-character limit.
ALTER TABLE ai_user_free_quota ALTER COLUMN user_id TYPE VARCHAR(255);
ALTER TABLE ai_user_free_quota ALTER COLUMN creator TYPE VARCHAR(255);
ALTER TABLE ai_user_free_quota ALTER COLUMN updater TYPE VARCHAR(255);
CREATE VIEW campus_current_member AS
SELECT u.id AS owner_id, u.user_id, COALESCE(c.user_account,u.user_id) AS account,
       COALESCE(c.display_name,u.username,u.user_id) AS display_name, u.user_type,u.company_id,
       CASE WHEN NULLIF(BTRIM(c.department_code),'') IS NOT NULL THEN 'code:'||BTRIM(c.department_code)
            WHEN NULLIF(BTRIM(c.department_name),'') IS NOT NULL THEN 'name:'||BTRIM(c.department_name)
            ELSE 'unassigned' END AS department_id,
       COALESCE(NULLIF(BTRIM(c.department_name),''),NULLIF(BTRIM(c.department_code),''),'未分配部门') AS department_name
FROM sldd_system_users u LEFT JOIN LATERAL (
 SELECT b.* FROM campus_identity_binding b WHERE b.system_user_id=u.id
 ORDER BY b.enabled DESC NULLS LAST,b.updated_at DESC NULLS LAST,b.created_at DESC NULLS LAST,b.id LIMIT 1
) c ON TRUE;

CREATE TABLE campus_department_policy (
 department_id VARCHAR(512) PRIMARY KEY, free_usage BOOLEAN NOT NULL DEFAULT FALSE,
 funding_source VARCHAR(20) NOT NULL DEFAULT 'PERSONAL' CHECK (funding_source IN ('PERSONAL','DEPARTMENT')),
 monthly_tokens BIGINT CHECK (monthly_tokens >= 0), member_cost_limit NUMERIC(26,8) CHECK (member_cost_limit >= 0),
 member_token_limit BIGINT CHECK (member_token_limit >= 0), alert_thresholds INTEGER[] NOT NULL DEFAULT '{80,90,100}',
 version BIGINT NOT NULL DEFAULT 1, reason VARCHAR(500) NOT NULL DEFAULT '',
 updated_by VARCHAR(255) NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE campus_user_usage_policy ADD COLUMN department_id VARCHAR(512);
ALTER TABLE campus_user_usage_policy ADD COLUMN funding_source VARCHAR(20) CHECK (funding_source IN ('PERSONAL','DEPARTMENT'));
ALTER TABLE campus_user_usage_policy ADD COLUMN monthly_cost NUMERIC(26,8) CHECK (monthly_cost >= 0);
ALTER TABLE campus_user_usage_policy ADD COLUMN monthly_tokens BIGINT CHECK (monthly_tokens >= 0);
UPDATE campus_user_usage_policy p SET department_id=m.department_id FROM campus_current_member m
 WHERE p.owner_id=m.owner_id AND p.user_id=m.user_id;
CREATE TABLE campus_user_policy_history (
 id BIGSERIAL PRIMARY KEY,owner_id BIGINT NOT NULL,previous_policy JSONB NOT NULL,
 event_type VARCHAR(40) NOT NULL,created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE campus_department_account (
 department_id VARCHAR(512) PRIMARY KEY, balance NUMERIC(26,8) NOT NULL DEFAULT 0,
 allocated_total NUMERIC(26,8) NOT NULL DEFAULT 0, reserved_amount NUMERIC(26,8) NOT NULL DEFAULT 0,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE campus_usage_reservation (
 request_id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(255) NOT NULL, owner_id BIGINT,
 department_id VARCHAR(512) NOT NULL, department_name VARCHAR(255) NOT NULL,
 period_start DATE NOT NULL, funding_source VARCHAR(20) NOT NULL, free_usage BOOLEAN NOT NULL,
 policy_source VARCHAR(20) NOT NULL, policy_version BIGINT NOT NULL DEFAULT 0,
 account_id VARCHAR(255), account_type INTEGER, reserved_cost NUMERIC(26,8) NOT NULL DEFAULT 0,
 reserved_tokens BIGINT NOT NULL DEFAULT 0, actual_cost NUMERIC(26,8) NOT NULL DEFAULT 0,
 actual_tokens BIGINT NOT NULL DEFAULT 0, state VARCHAR(20) NOT NULL DEFAULT 'RESERVED'
   CHECK (state IN ('RESERVED','SETTLED','RELEASED','RECONCILE')),
 snapshot TEXT NOT NULL, billing_record_id BIGINT UNIQUE, task_no VARCHAR(255),error_message VARCHAR(1000),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_campus_usage_department_period ON campus_usage_reservation(department_id,period_start,state);
CREATE INDEX idx_campus_usage_user_period ON campus_usage_reservation(user_id,period_start,state);
CREATE UNIQUE INDEX idx_campus_usage_task ON campus_usage_reservation(task_no) WHERE task_no IS NOT NULL;

CREATE TABLE campus_admin_batch (
 id VARCHAR(64) PRIMARY KEY, operator VARCHAR(255) NOT NULL, department_id VARCHAR(512) NOT NULL,
 kind VARCHAR(30) NOT NULL, payload TEXT NOT NULL, targets TEXT NOT NULL, fingerprint VARCHAR(64) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PREVIEW', reason VARCHAR(500) NOT NULL,
 result TEXT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, completed_at TIMESTAMP
);
CREATE INDEX idx_campus_admin_batch_department ON campus_admin_batch(department_id,created_at DESC);
CREATE TABLE campus_department_alert (
 department_id VARCHAR(512) NOT NULL, period_start DATE NOT NULL, metric VARCHAR(30) NOT NULL,
 threshold INTEGER NOT NULL, message VARCHAR(500) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(department_id,period_start,metric,threshold)
);

ALTER TABLE ai_billing_record ADD COLUMN department_id VARCHAR(512);
ALTER TABLE ai_billing_record ADD COLUMN department_name VARCHAR(255);
ALTER TABLE ai_billing_record ADD COLUMN funding_source VARCHAR(20);
ALTER TABLE ai_billing_record ADD COLUMN policy_snapshot TEXT;
ALTER TABLE ai_billing_record ADD COLUMN billing_request_id VARCHAR(64);
ALTER TABLE ai_billing_record ADD COLUMN settlement_status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE ai_billing_record ADD COLUMN actual_deducted NUMERIC(26,8);
CREATE UNIQUE INDEX idx_billing_request_id ON ai_billing_record(billing_request_id) WHERE billing_request_id IS NOT NULL;
CREATE INDEX idx_billing_department_started ON ai_billing_record(department_id,started_at) WHERE is_deleted=FALSE;
ALTER TABLE ai_video_task ADD COLUMN usage_snapshot TEXT;
