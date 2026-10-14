-- Null selects the shared library's automatic model/brand/service icon.
ALTER TABLE model_square_content ADD COLUMN IF NOT EXISTS icon_id VARCHAR(64);
