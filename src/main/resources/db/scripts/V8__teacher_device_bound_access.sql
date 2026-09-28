-- 南通理工学院：OIDC 教师身份、受信设备和专属入口
-- PostgreSQL 16；生产环境 ddl-auto=none 时手工审核执行。

CREATE TABLE IF NOT EXISTS teacher_identity_binding (
    id                  VARCHAR(36)  PRIMARY KEY,
    oidc_issuer         VARCHAR(255) NOT NULL,
    oidc_subject        VARCHAR(255) NOT NULL,
    system_user_id      BIGINT       NOT NULL,
    user_account        VARCHAR(128) NOT NULL,
    staff_no            VARCHAR(64),
    display_name        VARCHAR(128),
    roles               VARCHAR(512) NOT NULL,
    user_type           INTEGER,
    verify_status       INTEGER,
    enterprise_id       BIGINT,
    enterprise_name     VARCHAR(255),
    company_id          VARCHAR(128),
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_teacher_identity_issuer_subject UNIQUE (oidc_issuer, oidc_subject)
);

CREATE INDEX IF NOT EXISTS idx_teacher_identity_system_user
    ON teacher_identity_binding (system_user_id);
CREATE INDEX IF NOT EXISTS idx_teacher_identity_staff_no
    ON teacher_identity_binding (staff_no);

CREATE TABLE IF NOT EXISTS teacher_trusted_device (
    id                      VARCHAR(36)  PRIMARY KEY,
    identity_binding_id     VARCHAR(36)  NOT NULL,
    device_id               VARCHAR(128) NOT NULL,
    device_name             VARCHAR(128),
    certificate_fingerprint VARCHAR(64)  NOT NULL,
    certificate_expires_at  TIMESTAMP,
    status                  VARCHAR(16)  NOT NULL,
    last_seen_at            TIMESTAMP,
    revoked_at              TIMESTAMP,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_teacher_device_identity FOREIGN KEY (identity_binding_id)
        REFERENCES teacher_identity_binding (id),
    CONSTRAINT uk_teacher_device_binding_device UNIQUE (identity_binding_id, device_id),
    CONSTRAINT uk_teacher_device_cert_fingerprint UNIQUE (certificate_fingerprint)
);

CREATE INDEX IF NOT EXISTS idx_teacher_device_binding
    ON teacher_trusted_device (identity_binding_id, status);

CREATE TABLE IF NOT EXISTS teacher_endpoint (
    endpoint_id         VARCHAR(64)  PRIMARY KEY,
    identity_binding_id VARCHAR(36)  NOT NULL,
    credential_id       VARCHAR(64)  NOT NULL,
    permissions         VARCHAR(512) NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_teacher_endpoint_identity FOREIGN KEY (identity_binding_id)
        REFERENCES teacher_identity_binding (id),
    CONSTRAINT uk_teacher_endpoint_credential UNIQUE (credential_id)
);

CREATE INDEX IF NOT EXISTS idx_teacher_endpoint_binding
    ON teacher_endpoint (identity_binding_id);
