-- =====================================================================
-- Phase A2: Create import_mapping_templates table
-- Phase B3: Migrate crm_infos data into users.custom_fields
-- =====================================================================

-- 1. Create import_mapping_templates table for saved column mappings
CREATE TABLE IF NOT EXISTS import_mapping_templates (
    id              BIGSERIAL PRIMARY KEY,
    client_id       BIGINT NOT NULL REFERENCES clients(id),
    name            VARCHAR(255) NOT NULL,
    is_foh          BOOLEAN DEFAULT false,
    column_mapping  JSONB NOT NULL,
    headers         JSONB NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_import_mapping_templates_client_name UNIQUE (client_id, name)
);

CREATE INDEX IF NOT EXISTS idx_import_mapping_templates_client
    ON import_mapping_templates (client_id);

-- 2. Migrate existing crm_infos data into users.custom_fields (JSONB)
-- This merges CRM field values into the existing custom_fields column.
-- Each crm_info row becomes a key-value pair in the JSONB object.
UPDATE users u
SET custom_fields = COALESCE(u.custom_fields, '{}'::jsonb) || sub.crm_data
FROM (
    SELECT ci.user_id,
           jsonb_object_agg(ci.column_label, ci.column_value) AS crm_data
    FROM crm_infos ci
    WHERE ci.column_label IS NOT NULL
      AND ci.column_value IS NOT NULL
    GROUP BY ci.user_id
) sub
WHERE u.id = sub.user_id;

-- Note: crm_infos table is NOT dropped — kept for safety during transition.
-- After verification, it can be truncated or dropped in a future migration.
