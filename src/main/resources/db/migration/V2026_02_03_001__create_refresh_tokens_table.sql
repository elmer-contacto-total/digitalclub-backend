-- Migration: Create refresh_tokens table for JWT refresh token management
-- Date: 2026-02-03
-- Description: Stores refresh tokens for token rotation and revocation support

CREATE TABLE refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    token VARCHAR(500) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    device_info VARCHAR(255),
    ip_address VARCHAR(45)
);

-- Índices para performance
CREATE INDEX idx_refresh_token_token ON refresh_tokens(token);
CREATE INDEX idx_refresh_token_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_token_expires ON refresh_tokens(expires_at);

COMMENT ON TABLE refresh_tokens IS 'Stores JWT refresh tokens for secure token rotation';
COMMENT ON COLUMN refresh_tokens.token IS 'The refresh token string (UUID)';
COMMENT ON COLUMN refresh_tokens.expires_at IS 'Token expiration timestamp';
COMMENT ON COLUMN refresh_tokens.revoked IS 'Whether the token has been revoked';
COMMENT ON COLUMN refresh_tokens.device_info IS 'Device/browser info for auditing';
COMMENT ON COLUMN refresh_tokens.ip_address IS 'Client IP address for auditing';
