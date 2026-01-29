-- =====================================================
-- Foreign Keys
-- Ejecutar despues de 01_create_database.sql
-- =====================================================

-- Alerts
ALTER TABLE alerts DROP CONSTRAINT IF EXISTS fk_alerts_user;
ALTER TABLE alerts ADD CONSTRAINT fk_alerts_user
    FOREIGN KEY (user_id) REFERENCES users(id);

-- Bulk Messages
ALTER TABLE bulk_messages DROP CONSTRAINT IF EXISTS fk_bulk_messages_client;
ALTER TABLE bulk_messages DROP CONSTRAINT IF EXISTS fk_bulk_messages_user;
ALTER TABLE bulk_messages ADD CONSTRAINT fk_bulk_messages_client
    FOREIGN KEY (client_id) REFERENCES clients(id);
ALTER TABLE bulk_messages ADD CONSTRAINT fk_bulk_messages_user
    FOREIGN KEY (user_id) REFERENCES users(id);

-- Canned Messages
ALTER TABLE canned_messages DROP CONSTRAINT IF EXISTS fk_canned_messages_client;
ALTER TABLE canned_messages DROP CONSTRAINT IF EXISTS fk_canned_messages_user;
ALTER TABLE canned_messages ADD CONSTRAINT fk_canned_messages_client
    FOREIGN KEY (client_id) REFERENCES clients(id);
ALTER TABLE canned_messages ADD CONSTRAINT fk_canned_messages_user
    FOREIGN KEY (user_id) REFERENCES users(id);

-- Client Settings
ALTER TABLE client_settings DROP CONSTRAINT IF EXISTS fk_client_settings_client;
ALTER TABLE client_settings ADD CONSTRAINT fk_client_settings_client
    FOREIGN KEY (client_id) REFERENCES clients(id);

-- Client Structures
ALTER TABLE client_structures DROP CONSTRAINT IF EXISTS fk_client_structures_client;
ALTER TABLE client_structures ADD CONSTRAINT fk_client_structures_client
    FOREIGN KEY (client_id) REFERENCES clients(id);

-- CRM Info Settings
ALTER TABLE crm_info_settings DROP CONSTRAINT IF EXISTS fk_crm_info_settings_client;
ALTER TABLE crm_info_settings ADD CONSTRAINT fk_crm_info_settings_client
    FOREIGN KEY (client_id) REFERENCES clients(id);

-- CRM Infos
ALTER TABLE crm_infos DROP CONSTRAINT IF EXISTS fk_crm_infos_user;
ALTER TABLE crm_infos DROP CONSTRAINT IF EXISTS fk_crm_infos_setting;
ALTER TABLE crm_infos ADD CONSTRAINT fk_crm_infos_user
    FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE crm_infos ADD CONSTRAINT fk_crm_infos_setting
    FOREIGN KEY (crm_info_setting_id) REFERENCES crm_info_settings(id);

-- Device Infos
ALTER TABLE device_infos DROP CONSTRAINT IF EXISTS fk_device_infos_user;
ALTER TABLE device_infos ADD CONSTRAINT fk_device_infos_user
    FOREIGN KEY (user_id) REFERENCES users(id);

-- Imports
ALTER TABLE imports DROP CONSTRAINT IF EXISTS fk_imports_user;
ALTER TABLE imports DROP CONSTRAINT IF EXISTS fk_imports_client;
ALTER TABLE imports ADD CONSTRAINT fk_imports_user
    FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE imports ADD CONSTRAINT fk_imports_client
    FOREIGN KEY (client_id) REFERENCES clients(id);

-- KPI Counters
ALTER TABLE kpi_counters DROP CONSTRAINT IF EXISTS fk_kpi_counters_client;
ALTER TABLE kpi_counters DROP CONSTRAINT IF EXISTS fk_kpi_counters_user;
ALTER TABLE kpi_counters ADD CONSTRAINT fk_kpi_counters_client
    FOREIGN KEY (client_id) REFERENCES clients(id);
ALTER TABLE kpi_counters ADD CONSTRAINT fk_kpi_counters_user
    FOREIGN KEY (user_id) REFERENCES users(id);

-- KPIs
ALTER TABLE kpis DROP CONSTRAINT IF EXISTS fk_kpis_client;
ALTER TABLE kpis ADD CONSTRAINT fk_kpis_client
    FOREIGN KEY (client_id) REFERENCES clients(id);

-- Message Template Params
ALTER TABLE message_template_params DROP CONSTRAINT IF EXISTS fk_mtp_template;
ALTER TABLE message_template_params ADD CONSTRAINT fk_mtp_template
    FOREIGN KEY (message_template_id) REFERENCES message_templates(id);

-- Message Templates
ALTER TABLE message_templates DROP CONSTRAINT IF EXISTS fk_mt_user;
ALTER TABLE message_templates DROP CONSTRAINT IF EXISTS fk_mt_client;
ALTER TABLE message_templates DROP CONSTRAINT IF EXISTS fk_mt_language;
ALTER TABLE message_templates ADD CONSTRAINT fk_mt_user
    FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE message_templates ADD CONSTRAINT fk_mt_client
    FOREIGN KEY (client_id) REFERENCES clients(id);
ALTER TABLE message_templates ADD CONSTRAINT fk_mt_language
    FOREIGN KEY (language_id) REFERENCES languages(id);

-- Messages
ALTER TABLE messages DROP CONSTRAINT IF EXISTS fk_messages_sender;
ALTER TABLE messages DROP CONSTRAINT IF EXISTS fk_messages_recipient;
ALTER TABLE messages ADD CONSTRAINT fk_messages_sender
    FOREIGN KEY (sender_id) REFERENCES users(id);
ALTER TABLE messages ADD CONSTRAINT fk_messages_recipient
    FOREIGN KEY (recipient_id) REFERENCES users(id);

-- Prospects
ALTER TABLE prospects DROP CONSTRAINT IF EXISTS fk_prospects_manager;
ALTER TABLE prospects ADD CONSTRAINT fk_prospects_manager
    FOREIGN KEY (manager_id) REFERENCES users(id);

-- Pushed Messages
ALTER TABLE pushed_messages DROP CONSTRAINT IF EXISTS fk_pushed_messages_sender;
ALTER TABLE pushed_messages DROP CONSTRAINT IF EXISTS fk_pushed_messages_recipient;
ALTER TABLE pushed_messages ADD CONSTRAINT fk_pushed_messages_sender
    FOREIGN KEY (sender_id) REFERENCES users(id);
ALTER TABLE pushed_messages ADD CONSTRAINT fk_pushed_messages_recipient
    FOREIGN KEY (recipient_id) REFERENCES users(id);

-- Template Bulk Sends
ALTER TABLE template_bulk_sends DROP CONSTRAINT IF EXISTS fk_tbs_user;
ALTER TABLE template_bulk_sends DROP CONSTRAINT IF EXISTS fk_tbs_client;
ALTER TABLE template_bulk_sends ADD CONSTRAINT fk_tbs_user
    FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE template_bulk_sends ADD CONSTRAINT fk_tbs_client
    FOREIGN KEY (client_id) REFERENCES clients(id);

-- Tickets
ALTER TABLE tickets DROP CONSTRAINT IF EXISTS fk_tickets_user;
ALTER TABLE tickets DROP CONSTRAINT IF EXISTS fk_tickets_agent;
ALTER TABLE tickets ADD CONSTRAINT fk_tickets_user
    FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE tickets ADD CONSTRAINT fk_tickets_agent
    FOREIGN KEY (agent_id) REFERENCES users(id);

-- User Manager Histories
ALTER TABLE user_manager_histories DROP CONSTRAINT IF EXISTS fk_umh_user;
ALTER TABLE user_manager_histories ADD CONSTRAINT fk_umh_user
    FOREIGN KEY (user_id) REFERENCES users(id);

-- User Profiles
ALTER TABLE user_profiles DROP CONSTRAINT IF EXISTS fk_up_user;
ALTER TABLE user_profiles ADD CONSTRAINT fk_up_user
    FOREIGN KEY (user_id) REFERENCES users(id);

-- User Settings
ALTER TABLE user_settings DROP CONSTRAINT IF EXISTS fk_us_user;
ALTER TABLE user_settings ADD CONSTRAINT fk_us_user
    FOREIGN KEY (user_id) REFERENCES users(id);

-- Users (self-referencing for manager)
ALTER TABLE users DROP CONSTRAINT IF EXISTS fk_users_manager;
ALTER TABLE users ADD CONSTRAINT fk_users_manager
    FOREIGN KEY (manager_id) REFERENCES users(id);

SELECT 'Foreign keys creadas exitosamente' AS resultado;
