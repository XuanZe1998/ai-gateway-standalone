CREATE TABLE IF NOT EXISTS campus_portal_preferences (
    owner_id BIGINT PRIMARY KEY,
    balance_threshold NUMERIC(20,6) NOT NULL DEFAULT 10 CHECK (balance_threshold >= 0),
    quota_percent INTEGER NOT NULL DEFAULT 20 CHECK (quota_percent BETWEEN 0 AND 100)
);
CREATE TABLE IF NOT EXISTS campus_quota_request (
    id VARCHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, platform_user_id VARCHAR(255) NOT NULL,
    amount NUMERIC(20,6) NOT NULL CHECK (amount >= 0), monthly_tokens BIGINT CHECK (monthly_tokens >= 0),
    reason VARCHAR(1000) NOT NULL, status VARCHAR(12) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
    review_note VARCHAR(1000), reviewer VARCHAR(255), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_campus_quota_request_owner ON campus_quota_request(owner_id,created_at DESC);
CREATE TABLE IF NOT EXISTS campus_security_event (
    id BIGSERIAL PRIMARY KEY, owner_id BIGINT NOT NULL, event_type VARCHAR(40) NOT NULL,
    key_id VARCHAR(36), description VARCHAR(300) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_campus_security_owner ON campus_security_event(owner_id,created_at DESC);
CREATE TABLE IF NOT EXISTS campus_call_record (
    id VARCHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, platform_user_id VARCHAR(255) NOT NULL,
    key_id VARCHAR(36), model VARCHAR(255), path VARCHAR(200) NOT NULL, http_status INTEGER NOT NULL,
    response_ms BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_campus_call_owner ON campus_call_record(owner_id,created_at DESC);
CREATE TABLE IF NOT EXISTS campus_portal_session (
    id VARCHAR(36) PRIMARY KEY, session_hash VARCHAR(64) NOT NULL UNIQUE, owner_id BIGINT NOT NULL,
    device_name VARCHAR(200) NOT NULL, ip_address VARCHAR(64), last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL, revoked BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS idx_campus_session_owner ON campus_portal_session(owner_id,last_seen_at DESC);
CREATE TABLE IF NOT EXISTS campus_key_policy (
    key_id VARCHAR(36) PRIMARY KEY REFERENCES campus_gateway_key(key_id),
    monthly_cost NUMERIC(20,6) CHECK (monthly_cost >= 0), monthly_tokens BIGINT CHECK (monthly_tokens >= 0)
);
CREATE TABLE IF NOT EXISTS campus_model_favorite (
    owner_id BIGINT NOT NULL, service_type VARCHAR(30) NOT NULL, model_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(owner_id,service_type,model_id)
);
