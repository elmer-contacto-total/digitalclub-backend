-- Add message_sent_at to captured_media (when the WhatsApp message was originally sent)
ALTER TABLE captured_media ADD COLUMN IF NOT EXISTS message_sent_at TIMESTAMP;

-- Create index for querying by message sent time
CREATE INDEX IF NOT EXISTS idx_captured_media_message_sent_at ON captured_media(message_sent_at);

-- Add comment
COMMENT ON COLUMN captured_media.message_sent_at IS 'When the WhatsApp message containing this media was originally sent';

-- ============================================================
-- Add agent_id and client_user_id to media_audit_logs
-- ============================================================

-- Add agent_id column
ALTER TABLE media_audit_logs ADD COLUMN IF NOT EXISTS agent_id BIGINT;

-- Add client_user_id column
ALTER TABLE media_audit_logs ADD COLUMN IF NOT EXISTS client_user_id BIGINT;

-- Add foreign keys
ALTER TABLE media_audit_logs
    ADD CONSTRAINT fk_media_audit_agent
    FOREIGN KEY (agent_id) REFERENCES users(id)
    ON DELETE SET NULL;

ALTER TABLE media_audit_logs
    ADD CONSTRAINT fk_media_audit_client
    FOREIGN KEY (client_user_id) REFERENCES users(id)
    ON DELETE SET NULL;

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_media_audit_agent_id ON media_audit_logs(agent_id);
CREATE INDEX IF NOT EXISTS idx_media_audit_client_user_id ON media_audit_logs(client_user_id);

-- Add comments
COMMENT ON COLUMN media_audit_logs.agent_id IS 'ID of the agent who performed this action';
COMMENT ON COLUMN media_audit_logs.client_user_id IS 'ID of the client related to this audit event';
