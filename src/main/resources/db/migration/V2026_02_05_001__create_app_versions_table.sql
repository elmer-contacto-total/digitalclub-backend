-- Create app_versions table for Electron auto-update system

CREATE TABLE app_versions (
    id BIGSERIAL PRIMARY KEY,
    version VARCHAR(20) NOT NULL,
    download_url VARCHAR(500) NOT NULL,
    platform VARCHAR(20) NOT NULL DEFAULT 'windows',
    release_notes TEXT,
    file_size BIGINT,
    sha256_hash VARCHAR(64),
    mandatory BOOLEAN NOT NULL DEFAULT FALSE,
    published_at TIMESTAMP NOT NULL DEFAULT NOW(),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_app_versions_version_platform UNIQUE(version, platform)
);

CREATE INDEX idx_app_versions_platform_active ON app_versions(platform, active);

COMMENT ON TABLE app_versions IS 'Stores app version information for Electron auto-update system';
