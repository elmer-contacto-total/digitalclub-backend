-- Add assigned_agent_id to bulk_sends for supervisor → agent delegation
ALTER TABLE bulk_sends ADD COLUMN assigned_agent_id BIGINT;

ALTER TABLE bulk_sends ADD CONSTRAINT fk_bulk_sends_assigned_agent
    FOREIGN KEY (assigned_agent_id) REFERENCES users(id);

CREATE INDEX idx_bulk_sends_assigned_agent ON bulk_sends(assigned_agent_id);

-- Backfill: existing sends self-assign to creator
UPDATE bulk_sends SET assigned_agent_id = user_id WHERE assigned_agent_id IS NULL;
