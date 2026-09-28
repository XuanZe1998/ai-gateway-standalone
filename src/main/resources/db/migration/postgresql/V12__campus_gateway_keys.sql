-- New account-owned gateway credentials. Legacy config-store credentials remain historical only.
CREATE TABLE IF NOT EXISTS campus_gateway_key_limit (
    owner_id BIGINT PRIMARY KEY,
    max_keys INTEGER NOT NULL CHECK (max_keys BETWEEN 0 AND 10000)
);
INSERT INTO campus_gateway_key_limit (owner_id, max_keys) VALUES (0, 5)
ON CONFLICT (owner_id) DO NOTHING;
CREATE TABLE IF NOT EXISTS campus_gateway_key (
    key_id VARCHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    platform_user_id VARCHAR(64) NOT NULL,
    key_hash VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('ACTIVE','DISABLED','REVOKED')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL
);
CREATE INDEX IF NOT EXISTS idx_campus_gateway_key_owner ON campus_gateway_key(owner_id, status);
