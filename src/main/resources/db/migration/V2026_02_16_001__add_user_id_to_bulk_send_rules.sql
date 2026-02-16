-- Per-supervisor bulk send rules (previously per-client only)
ALTER TABLE bulk_send_rules ADD COLUMN user_id BIGINT REFERENCES users(id);

ALTER TABLE bulk_send_rules DROP CONSTRAINT IF EXISTS bulk_send_rules_client_id_key;
ALTER TABLE bulk_send_rules ADD CONSTRAINT uq_bulk_send_rules_client_user
    UNIQUE (client_id, user_id);
