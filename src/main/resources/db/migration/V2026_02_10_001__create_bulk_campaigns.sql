-- Bulk Campaigns: tracks mass sending campaigns (Cloud API or Electron)
CREATE TABLE bulk_campaigns (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES clients(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    bulk_message_id BIGINT REFERENCES bulk_messages(id),
    message_template_id BIGINT REFERENCES message_templates(id),
    send_method VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_recipients INT NOT NULL DEFAULT 0,
    sent_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    error_summary TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_bulk_campaigns_client ON bulk_campaigns(client_id);
CREATE INDEX idx_bulk_campaigns_user ON bulk_campaigns(user_id);
CREATE INDEX idx_bulk_campaigns_status ON bulk_campaigns(status);

-- Bulk Campaign Recipients: individual recipient tracking
CREATE TABLE bulk_campaign_recipients (
    id BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES bulk_campaigns(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    phone VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    sent_at TIMESTAMP,
    error_message VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_bcr_campaign ON bulk_campaign_recipients(campaign_id);
CREATE INDEX idx_bcr_status ON bulk_campaign_recipients(campaign_id, status);

-- Bulk Send Rules: supervisor-configurable anti-ban and rate limiting rules
CREATE TABLE bulk_send_rules (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES clients(id),
    max_daily_messages INT NOT NULL DEFAULT 200,
    min_delay_seconds INT NOT NULL DEFAULT 30,
    max_delay_seconds INT NOT NULL DEFAULT 90,
    pause_after_count INT NOT NULL DEFAULT 20,
    pause_duration_minutes INT NOT NULL DEFAULT 5,
    send_hour_start INT NOT NULL DEFAULT 8,
    send_hour_end INT NOT NULL DEFAULT 20,
    cloud_api_delay_ms INT NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(client_id)
);
