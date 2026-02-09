-- Renombrar tablas
ALTER TABLE bulk_campaigns RENAME TO bulk_sends;
ALTER TABLE bulk_campaign_recipients RENAME TO bulk_send_recipients;

-- Renombrar índices
ALTER INDEX idx_bulk_campaigns_client RENAME TO idx_bulk_sends_client;
ALTER INDEX idx_bulk_campaigns_user RENAME TO idx_bulk_sends_user;
ALTER INDEX idx_bulk_campaigns_status RENAME TO idx_bulk_sends_status;
ALTER INDEX idx_bcr_campaign RENAME TO idx_bsr_bulk_send;
ALTER INDEX idx_bcr_status RENAME TO idx_bsr_status;

-- Renombrar FK column en recipients
ALTER TABLE bulk_send_recipients RENAME COLUMN campaign_id TO bulk_send_id;

-- Agregar columnas nuevas para CSV + adjuntos
ALTER TABLE bulk_sends ADD COLUMN message_content TEXT;
ALTER TABLE bulk_sends ADD COLUMN attachment_path VARCHAR(500);
ALTER TABLE bulk_sends ADD COLUMN attachment_type VARCHAR(50);
ALTER TABLE bulk_sends ADD COLUMN attachment_size BIGINT;
ALTER TABLE bulk_sends ADD COLUMN attachment_original_name VARCHAR(255);

-- Recipients: user_id ahora opcional (CSV no requiere usuario del sistema)
ALTER TABLE bulk_send_recipients ALTER COLUMN user_id DROP NOT NULL;

-- Nombre y variables del CSV por recipient
ALTER TABLE bulk_send_recipients ADD COLUMN recipient_name VARCHAR(255);
ALTER TABLE bulk_send_recipients ADD COLUMN custom_variables JSONB;
