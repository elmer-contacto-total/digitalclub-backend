-- =================================================
-- Captured Media Tables for ms-media integration
-- =================================================

-- captured_media: Stores captured media from Electron app
CREATE TABLE captured_media (
    id BIGSERIAL PRIMARY KEY,
    media_uuid VARCHAR(36) NOT NULL UNIQUE,
    user_fingerprint VARCHAR(64) NOT NULL,
    chat_phone VARCHAR(20),
    chat_name VARCHAR(100),
    media_type VARCHAR(20) NOT NULL,
    mime_type VARCHAR(100),
    file_path VARCHAR(500),
    public_url VARCHAR(1000),
    size_bytes BIGINT,
    duration_seconds INTEGER,
    sha256_hash VARCHAR(64),
    whatsapp_message_id VARCHAR(100),
    capture_source VARCHAR(20),
    captured_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for captured_media
CREATE INDEX idx_captured_media_chat_phone ON captured_media(chat_phone);
CREATE INDEX idx_captured_media_user_fingerprint ON captured_media(user_fingerprint);
CREATE INDEX idx_captured_media_captured_at ON captured_media(captured_at);
CREATE INDEX idx_captured_media_sha256 ON captured_media(sha256_hash);

-- media_audit_logs: Security audit logs for media operations
CREATE TABLE media_audit_logs (
    id BIGSERIAL PRIMARY KEY,
    user_fingerprint VARCHAR(64) NOT NULL,
    action VARCHAR(30) NOT NULL,
    description TEXT,
    chat_phone VARCHAR(20),
    file_type VARCHAR(100),
    file_name VARCHAR(255),
    original_url VARCHAR(1000),
    size_bytes BIGINT,
    client_ip VARCHAR(45),
    extra_metadata JSONB,
    event_timestamp TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for media_audit_logs
CREATE INDEX idx_media_audit_user ON media_audit_logs(user_fingerprint);
CREATE INDEX idx_media_audit_action ON media_audit_logs(action);
CREATE INDEX idx_media_audit_timestamp ON media_audit_logs(event_timestamp);
CREATE INDEX idx_media_audit_chat ON media_audit_logs(chat_phone);
