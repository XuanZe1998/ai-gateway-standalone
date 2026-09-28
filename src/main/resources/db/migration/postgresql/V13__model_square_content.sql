-- Gateway-owned presentation overrides only; pricing stays in platform tables.
CREATE TABLE IF NOT EXISTS model_square_content (
    content_key VARCHAR(300) PRIMARY KEY,
    service_type VARCHAR(24) NOT NULL,
    model_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(100),
    description VARCHAR(2000),
    tags TEXT NOT NULL DEFAULT '[]',
    updated_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    CONSTRAINT uq_model_square_content UNIQUE (service_type, model_id)
);
