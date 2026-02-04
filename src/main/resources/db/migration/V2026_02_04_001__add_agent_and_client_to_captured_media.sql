-- Add agent_id and client_user_id columns to captured_media table
-- agent_id: The agent who captured this media (logged-in user in Electron)
-- client_user_id: The client whose media was captured (looked up by chat_phone)

-- Add agent_id column
ALTER TABLE captured_media ADD COLUMN IF NOT EXISTS agent_id BIGINT;

-- Add client_user_id column
ALTER TABLE captured_media ADD COLUMN IF NOT EXISTS client_user_id BIGINT;

-- Add foreign key for agent
ALTER TABLE captured_media
    ADD CONSTRAINT fk_captured_media_agent
    FOREIGN KEY (agent_id) REFERENCES users(id)
    ON DELETE SET NULL;

-- Add foreign key for client
ALTER TABLE captured_media
    ADD CONSTRAINT fk_captured_media_client
    FOREIGN KEY (client_user_id) REFERENCES users(id)
    ON DELETE SET NULL;

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_captured_media_agent_id ON captured_media(agent_id);
CREATE INDEX IF NOT EXISTS idx_captured_media_client_user_id ON captured_media(client_user_id);

-- Add comments
COMMENT ON COLUMN captured_media.agent_id IS 'ID of the agent who captured this media';
COMMENT ON COLUMN captured_media.client_user_id IS 'ID of the client whose media was captured (resolved from chat_phone)';
