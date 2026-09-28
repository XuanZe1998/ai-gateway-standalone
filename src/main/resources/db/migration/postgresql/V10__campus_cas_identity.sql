-- Generalize legacy OIDC teacher identities into protocol-neutral campus CAS identities.
-- Supports databases with no teacher tables, legacy V8 tables, and fresh installations.

DO $$
BEGIN
    IF to_regclass(format('%I.%I', current_schema(), 'campus_identity_binding')) IS NULL
       AND to_regclass(format('%I.%I', current_schema(), 'teacher_identity_binding')) IS NOT NULL THEN
        ALTER TABLE teacher_identity_binding RENAME TO campus_identity_binding;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS campus_identity_binding (
    id                  VARCHAR(36) PRIMARY KEY,
    identity_provider   VARCHAR(255) NOT NULL,
    identity_protocol   VARCHAR(16) NOT NULL DEFAULT 'CAS2',
    external_subject    VARCHAR(255) NOT NULL,
    system_user_id      BIGINT NOT NULL,
    user_account        VARCHAR(128) NOT NULL,
    account             VARCHAR(128),
    local_account       VARCHAR(128),
    staff_no            VARCHAR(64),
    display_name        VARCHAR(128),
    type_code           VARCHAR(64) NOT NULL,
    type_name           VARCHAR(128),
    department_code     VARCHAR(128),
    department_name     VARCHAR(255),
    roles               VARCHAR(512) NOT NULL,
    user_type           INTEGER,
    verify_status       INTEGER,
    enterprise_id       BIGINT,
    enterprise_name     VARCHAR(255),
    company_id          VARCHAR(128),
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema=current_schema() AND table_name='campus_identity_binding'
                 AND column_name='oidc_issuer')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema=current_schema() AND table_name='campus_identity_binding'
                         AND column_name='identity_provider') THEN
        ALTER TABLE campus_identity_binding RENAME COLUMN oidc_issuer TO identity_provider;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema=current_schema() AND table_name='campus_identity_binding'
                 AND column_name='oidc_subject')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema=current_schema() AND table_name='campus_identity_binding'
                         AND column_name='external_subject') THEN
        ALTER TABLE campus_identity_binding RENAME COLUMN oidc_subject TO external_subject;
    END IF;
END $$;

ALTER TABLE campus_identity_binding ADD COLUMN IF NOT EXISTS identity_provider VARCHAR(255);
ALTER TABLE campus_identity_binding ADD COLUMN IF NOT EXISTS identity_protocol VARCHAR(16);
ALTER TABLE campus_identity_binding ADD COLUMN IF NOT EXISTS external_subject VARCHAR(255);
ALTER TABLE campus_identity_binding ADD COLUMN IF NOT EXISTS account VARCHAR(128);
ALTER TABLE campus_identity_binding ADD COLUMN IF NOT EXISTS local_account VARCHAR(128);
ALTER TABLE campus_identity_binding ADD COLUMN IF NOT EXISTS type_code VARCHAR(64);
ALTER TABLE campus_identity_binding ADD COLUMN IF NOT EXISTS type_name VARCHAR(128);
ALTER TABLE campus_identity_binding ADD COLUMN IF NOT EXISTS department_code VARCHAR(128);
ALTER TABLE campus_identity_binding ADD COLUMN IF NOT EXISTS department_name VARCHAR(255);

UPDATE campus_identity_binding SET identity_protocol = 'OIDC'
WHERE identity_protocol IS NULL;
UPDATE campus_identity_binding SET account = user_account
WHERE account IS NULL;
UPDATE campus_identity_binding SET type_code = 'LEGACY_OIDC'
WHERE type_code IS NULL;

ALTER TABLE campus_identity_binding ALTER COLUMN identity_provider SET NOT NULL;
ALTER TABLE campus_identity_binding ALTER COLUMN identity_protocol SET NOT NULL;
ALTER TABLE campus_identity_binding ALTER COLUMN external_subject SET NOT NULL;
ALTER TABLE campus_identity_binding ALTER COLUMN type_code SET NOT NULL;

ALTER TABLE campus_identity_binding DROP CONSTRAINT IF EXISTS uk_teacher_identity_issuer_subject;
ALTER TABLE campus_identity_binding DROP CONSTRAINT IF EXISTS uk_campus_identity_provider_subject;
ALTER TABLE campus_identity_binding ADD CONSTRAINT uk_campus_identity_provider_subject
    UNIQUE (identity_provider, external_subject);

DROP INDEX IF EXISTS idx_teacher_identity_system_user;
DROP INDEX IF EXISTS idx_teacher_identity_staff_no;
CREATE INDEX IF NOT EXISTS idx_campus_identity_system_user
    ON campus_identity_binding (system_user_id);
CREATE INDEX IF NOT EXISTS idx_campus_identity_account
    ON campus_identity_binding (local_account, account);

CREATE TABLE IF NOT EXISTS teacher_trusted_device (
    id                      VARCHAR(36) PRIMARY KEY,
    identity_binding_id     VARCHAR(36) NOT NULL,
    device_id               VARCHAR(128) NOT NULL,
    device_name             VARCHAR(128),
    certificate_fingerprint VARCHAR(64),
    certificate_expires_at  TIMESTAMP,
    status                  VARCHAR(16) NOT NULL,
    last_seen_at            TIMESTAMP,
    revoked_at              TIMESTAMP,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_teacher_device_binding_device UNIQUE (identity_binding_id, device_id)
);

CREATE TABLE IF NOT EXISTS teacher_endpoint (
    endpoint_id         VARCHAR(64) PRIMARY KEY,
    identity_binding_id VARCHAR(36) NOT NULL,
    credential_id       VARCHAR(64) NOT NULL,
    permissions         VARCHAR(512) NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_teacher_endpoint_credential UNIQUE (credential_id)
);

ALTER TABLE teacher_trusted_device ALTER COLUMN certificate_fingerprint DROP NOT NULL;
ALTER TABLE teacher_trusted_device DROP CONSTRAINT IF EXISTS uk_teacher_device_cert_fingerprint;
DROP INDEX IF EXISTS uk_teacher_device_cert_fingerprint;
CREATE UNIQUE INDEX IF NOT EXISTS uk_teacher_device_cert_fingerprint_not_null
    ON teacher_trusted_device (certificate_fingerprint)
    WHERE certificate_fingerprint IS NOT NULL;

ALTER TABLE teacher_trusted_device DROP CONSTRAINT IF EXISTS fk_teacher_device_identity;
ALTER TABLE teacher_endpoint DROP CONSTRAINT IF EXISTS fk_teacher_endpoint_identity;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname='fk_teacher_device_campus_identity'
                     AND conrelid='teacher_trusted_device'::regclass) THEN
        ALTER TABLE teacher_trusted_device ADD CONSTRAINT fk_teacher_device_campus_identity
            FOREIGN KEY (identity_binding_id) REFERENCES campus_identity_binding(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname='fk_teacher_endpoint_campus_identity'
                     AND conrelid='teacher_endpoint'::regclass) THEN
        ALTER TABLE teacher_endpoint ADD CONSTRAINT fk_teacher_endpoint_campus_identity
            FOREIGN KEY (identity_binding_id) REFERENCES campus_identity_binding(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_teacher_device_binding
    ON teacher_trusted_device (identity_binding_id, status);
CREATE INDEX IF NOT EXISTS idx_teacher_endpoint_binding
    ON teacher_endpoint (identity_binding_id);

