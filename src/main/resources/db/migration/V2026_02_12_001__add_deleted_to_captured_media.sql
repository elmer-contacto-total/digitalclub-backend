ALTER TABLE captured_media ADD COLUMN IF NOT EXISTS deleted BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE captured_media ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_captured_media_deleted ON captured_media(deleted);
