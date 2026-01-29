-- =====================================================
-- Script para crear la base de datos de desarrollo
-- Proyecto: Holape - Migracion Rails a Spring Boot
-- Generado desde: schema.rb (Rails 7.1.2)
-- =====================================================

-- Crear base de datos (ejecutar como superusuario postgres)
-- DROP DATABASE IF EXISTS digitalgroup_development;
-- CREATE DATABASE digitalgroup_development WITH ENCODING 'UTF8';

-- Conectar a la base de datos digitalgroup_development antes de ejecutar el resto

-- Extensiones requeridas
CREATE EXTENSION IF NOT EXISTS plpgsql;

-- =====================================================
-- TABLAS SIN DEPENDENCIAS (orden de creacion)
-- =====================================================

-- Countries (sin FK)
CREATE TABLE IF NOT EXISTS countries (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    iso_code VARCHAR(255) NOT NULL,
    flag_url VARCHAR(255),
    default_locale VARCHAR(255) NOT NULL,
    default_phone_country_code VARCHAR(255) NOT NULL,
    default_currency VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Languages (sin FK)
CREATE TABLE IF NOT EXISTS languages (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    language_code VARCHAR(255) NOT NULL,
    status INTEGER DEFAULT 0,
    display_order INTEGER DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Settings (sin FK) - Configuraciones globales
CREATE TABLE IF NOT EXISTS settings (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    localized_name VARCHAR(255) NOT NULL,
    internal BOOLEAN DEFAULT FALSE,
    data_type INTEGER NOT NULL,
    string_value VARCHAR(255),
    integer_value INTEGER,
    float_value FLOAT,
    datetime_value TIMESTAMP,
    hash_value JSONB DEFAULT '{}',
    boolean_value BOOLEAN,
    status INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Clients
CREATE TABLE IF NOT EXISTS clients (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    company_name VARCHAR(255) NOT NULL,
    doc_type INTEGER NOT NULL,
    doc_number VARCHAR(255) NOT NULL,
    country_id INTEGER NOT NULL,
    client_type INTEGER DEFAULT 0,
    whatsapp_access_token VARCHAR(255),
    whatsapp_business_id VARCHAR(255),
    whatsapp_number VARCHAR(255),
    whatsapp_account_review_status VARCHAR(255),
    whatsapp_timezone_id VARCHAR(255),
    whatsapp_verified_name VARCHAR(255),
    status INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    domain_url VARCHAR(255),
    logo_url VARCHAR(255)
);

-- =====================================================
-- TABLAS CON DEPENDENCIAS DE CLIENTS
-- =====================================================

-- Client Settings
CREATE TABLE IF NOT EXISTS client_settings (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    localized_name VARCHAR(255) NOT NULL,
    internal BOOLEAN DEFAULT FALSE,
    data_type INTEGER NOT NULL,
    string_value VARCHAR(255),
    integer_value INTEGER,
    float_value FLOAT,
    datetime_value TIMESTAMP,
    hash_value JSONB DEFAULT '{}',
    boolean_value BOOLEAN,
    status INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS index_client_settings_on_client_id ON client_settings(client_id);

-- Client Structures
CREATE TABLE IF NOT EXISTS client_structures (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL,
    manager_level_1 VARCHAR(255),
    exists_manager_level_1 BOOLEAN DEFAULT FALSE,
    manager_level_2 VARCHAR(255),
    exists_manager_level_2 BOOLEAN DEFAULT FALSE,
    manager_level_3 VARCHAR(255),
    exists_manager_level_3 BOOLEAN DEFAULT FALSE,
    manager_level_4 VARCHAR(255) DEFAULT 'Supervisor',
    exists_manager_level_4 BOOLEAN DEFAULT TRUE,
    agent VARCHAR(255) DEFAULT 'Sectorista',
    exists_agent BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS index_client_structures_on_client_id ON client_structures(client_id);

-- CRM Info Settings
CREATE TABLE IF NOT EXISTS crm_info_settings (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL,
    column_position INTEGER NOT NULL,
    column_type INTEGER DEFAULT 0 NOT NULL,
    column_label VARCHAR(255) NOT NULL,
    column_visible BOOLEAN DEFAULT FALSE NOT NULL,
    status INTEGER DEFAULT 0 NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS index_crm_info_settings_on_client_id ON crm_info_settings(client_id);

-- =====================================================
-- USERS (tabla central)
-- =====================================================

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    manager_id BIGINT,
    email VARCHAR(255) DEFAULT '' NOT NULL,
    encrypted_password VARCHAR(255) DEFAULT '' NOT NULL,
    reset_password_token VARCHAR(255),
    reset_password_sent_at TIMESTAMP,
    remember_created_at TIMESTAMP,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255) NOT NULL,
    username VARCHAR(255),
    phone VARCHAR(255) NOT NULL,
    avatar_data TEXT,
    otp VARCHAR(255),
    fcm_push_token VARCHAR(255),
    uuid_token VARCHAR(255),
    last_heartbeat_at TIMESTAMP,
    country_id INTEGER,
    time_zone VARCHAR(255),
    client_id INTEGER,
    locale VARCHAR(255) DEFAULT 'es',
    can_create_users BOOLEAN DEFAULT FALSE,
    role INTEGER DEFAULT 0,
    temp_password VARCHAR(255),
    initial_password_changed BOOLEAN DEFAULT FALSE,
    status INTEGER DEFAULT 0,
    import_id INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    codigo VARCHAR(255),
    custom_fields JSONB DEFAULT '{}',
    require_response BOOLEAN DEFAULT FALSE,
    require_close_ticket BOOLEAN DEFAULT FALSE,
    import_string VARCHAR(255),
    last_message_at TIMESTAMP
);

-- Indices para users
CREATE UNIQUE INDEX IF NOT EXISTS index_users_on_email ON users(email);
CREATE UNIQUE INDEX IF NOT EXISTS index_users_on_uuid_token ON users(uuid_token);
CREATE UNIQUE INDEX IF NOT EXISTS index_users_on_reset_password_token ON users(reset_password_token);
CREATE INDEX IF NOT EXISTS index_users_on_manager_id ON users(manager_id);
CREATE INDEX IF NOT EXISTS index_users_on_client_id ON users(client_id);
CREATE INDEX IF NOT EXISTS index_users_on_phone ON users(phone);
CREATE INDEX IF NOT EXISTS index_users_on_role ON users(role);
CREATE INDEX IF NOT EXISTS index_users_on_status ON users(status);
CREATE INDEX IF NOT EXISTS index_users_on_codigo ON users(codigo);
CREATE INDEX IF NOT EXISTS index_users_on_import_string ON users(import_string);
CREATE INDEX IF NOT EXISTS index_users_on_require_response ON users(require_response);
CREATE INDEX IF NOT EXISTS index_users_on_require_close_ticket ON users(require_close_ticket);
CREATE INDEX IF NOT EXISTS index_users_on_last_message_at ON users(last_message_at);
CREATE INDEX IF NOT EXISTS index_users_on_client_id_and_role ON users(client_id, role);
CREATE INDEX IF NOT EXISTS index_users_on_client_id_and_created_at ON users(client_id, created_at);
CREATE INDEX IF NOT EXISTS index_users_on_manager_id_and_role ON users(manager_id, role);
CREATE INDEX IF NOT EXISTS index_users_on_role_and_manager_id ON users(role, manager_id);
CREATE INDEX IF NOT EXISTS index_users_on_first_name_and_last_name ON users(first_name, last_name);
CREATE INDEX IF NOT EXISTS index_users_on_role_manager_id_last_message_at ON users(role, manager_id, last_message_at);

-- =====================================================
-- TABLAS QUE DEPENDEN DE USERS
-- =====================================================

-- Alerts
CREATE TABLE IF NOT EXISTS alerts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    alert_type INTEGER DEFAULT 0,
    severity INTEGER DEFAULT 0,
    title VARCHAR(255) NOT NULL,
    body VARCHAR(255) NOT NULL,
    read BOOLEAN DEFAULT FALSE,
    url VARCHAR(255),
    message_id VARCHAR(255),
    sender_id VARCHAR(255),
    recipient_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS index_alerts_on_user_id ON alerts(user_id);

-- User Profiles
CREATE TABLE IF NOT EXISTS user_profiles (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    birthdate DATE,
    gender INTEGER,
    doc_type INTEGER,
    doc_number VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS index_user_profiles_on_user_id ON user_profiles(user_id);

-- User Settings
CREATE TABLE IF NOT EXISTS user_settings (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    localized_name VARCHAR(255) NOT NULL,
    internal BOOLEAN DEFAULT FALSE,
    data_type INTEGER NOT NULL,
    string_value VARCHAR(255),
    integer_value INTEGER,
    float_value FLOAT,
    datetime_value TIMESTAMP,
    boolean_value BOOLEAN,
    status INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS index_user_settings_on_user_id ON user_settings(user_id);

-- User Manager Histories
CREATE TABLE IF NOT EXISTS user_manager_histories (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    old_manager_id INTEGER,
    new_manager_id INTEGER,
    comment TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS index_user_manager_histories_on_user_id ON user_manager_histories(user_id);

-- Device Infos
CREATE TABLE IF NOT EXISTS device_infos (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    device_id VARCHAR(255) NOT NULL,
    token TEXT,
    device_type INTEGER DEFAULT 0,
    status INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS index_device_infos_on_user_id ON device_infos(user_id);

-- CRM Infos
CREATE TABLE IF NOT EXISTS crm_infos (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    crm_info_setting_id BIGINT NOT NULL,
    column_position INTEGER NOT NULL,
    column_label VARCHAR(255) NOT NULL,
    column_visible BOOLEAN DEFAULT FALSE NOT NULL,
    column_value VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS index_crm_infos_on_user_id ON crm_infos(user_id);
CREATE INDEX IF NOT EXISTS index_crm_infos_on_crm_info_setting_id ON crm_infos(crm_info_setting_id);

-- Bulk Messages
CREATE TABLE IF NOT EXISTS bulk_messages (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    message VARCHAR(255) NOT NULL,
    client_global BOOLEAN DEFAULT FALSE,
    status INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS index_bulk_messages_on_client_id ON bulk_messages(client_id);
CREATE INDEX IF NOT EXISTS index_bulk_messages_on_user_id ON bulk_messages(user_id);

-- Canned Messages
CREATE TABLE IF NOT EXISTS canned_messages (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    message VARCHAR(255) NOT NULL,
    client_global BOOLEAN DEFAULT FALSE,
    status INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS index_canned_messages_on_client_id ON canned_messages(client_id);
CREATE INDEX IF NOT EXISTS index_canned_messages_on_user_id ON canned_messages(user_id);

-- Prospects
CREATE TABLE IF NOT EXISTS prospects (
    id BIGSERIAL PRIMARY KEY,
    manager_id BIGINT,
    name VARCHAR(255),
    phone VARCHAR(255) NOT NULL,
    client_id INTEGER,
    status INTEGER DEFAULT 0,
    upgraded_to_user BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS index_prospects_on_manager_id ON prospects(manager_id);

-- Imports
CREATE TABLE IF NOT EXISTS imports (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    client_id BIGINT NOT NULL,
    import_type INTEGER DEFAULT 0,
    import_file_data TEXT,
    tot_records INTEGER,
    status INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    progress INTEGER DEFAULT 0,
    errors_text TEXT
);
CREATE INDEX IF NOT EXISTS index_imports_on_user_id ON imports(user_id);
CREATE INDEX IF NOT EXISTS index_imports_on_client_id ON imports(client_id);

-- Temp Import Users
CREATE TABLE IF NOT EXISTS temp_import_users (
    id BIGSERIAL PRIMARY KEY,
    user_import_id INTEGER,
    codigo VARCHAR(255),
    email VARCHAR(255),
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    phone_code VARCHAR(255),
    phone VARCHAR(255),
    role VARCHAR(255),
    manager_email VARCHAR(255),
    error_message VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    custom_fields JSONB DEFAULT '{}',
    processed BOOLEAN DEFAULT FALSE,
    phone_order INTEGER,
    crm_fields JSONB DEFAULT '{}'
);

-- =====================================================
-- TICKETS
-- =====================================================

CREATE TABLE IF NOT EXISTS tickets (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    closed_at TIMESTAMP,
    subject VARCHAR(255),
    notes TEXT,
    status INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    close_type VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS index_tickets_on_user_id ON tickets(user_id);
CREATE INDEX IF NOT EXISTS index_tickets_on_agent_id ON tickets(agent_id);
CREATE INDEX IF NOT EXISTS index_tickets_on_agent_id_and_status_and_created_at ON tickets(agent_id, status, created_at);
CREATE INDEX IF NOT EXISTS index_tickets_on_user_id_and_status_and_created_at ON tickets(user_id, status, created_at);
CREATE INDEX IF NOT EXISTS index_tickets_on_agent_id_and_close_type_and_created_at ON tickets(agent_id, close_type, created_at);

-- =====================================================
-- MESSAGES
-- =====================================================

CREATE TABLE IF NOT EXISTS messages (
    id BIGSERIAL PRIMARY KEY,
    sender_id BIGINT NOT NULL,
    new_sender_phone TEXT,
    recipient_id BIGINT NOT NULL,
    is_prospect BOOLEAN DEFAULT FALSE,
    prospect_sender_id INTEGER,
    prospect_recipient_id INTEGER,
    ticket_id INTEGER,
    device_id INTEGER,
    direction INTEGER DEFAULT 0,
    status INTEGER DEFAULT 0,
    content TEXT NOT NULL,
    binary_content_data TEXT,
    whatsapp_business_routed BOOLEAN DEFAULT FALSE,
    original_whatsapp_business_recipient_id INTEGER,
    is_event BOOLEAN DEFAULT FALSE,
    processed BOOLEAN DEFAULT FALSE,
    sent_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_template BOOLEAN DEFAULT FALSE,
    template_name VARCHAR(255),
    historic_sender_name VARCHAR(255) DEFAULT '',
    worker_processed_at TIMESTAMP,
    message_order INTEGER
);

-- Indices para messages
CREATE INDEX IF NOT EXISTS index_messages_on_sender_id ON messages(sender_id);
CREATE INDEX IF NOT EXISTS index_messages_on_recipient_id ON messages(recipient_id);
CREATE INDEX IF NOT EXISTS index_messages_on_ticket_id ON messages(ticket_id);
CREATE INDEX IF NOT EXISTS index_messages_on_device_id ON messages(device_id);
CREATE INDEX IF NOT EXISTS index_messages_on_created_at ON messages(created_at);
CREATE INDEX IF NOT EXISTS index_messages_on_processed ON messages(processed);
CREATE INDEX IF NOT EXISTS index_messages_on_is_prospect ON messages(is_prospect);
CREATE INDEX IF NOT EXISTS index_messages_on_prospect_sender_id ON messages(prospect_sender_id);
CREATE INDEX IF NOT EXISTS index_messages_on_prospect_recipient_id ON messages(prospect_recipient_id);
CREATE INDEX IF NOT EXISTS index_messages_on_direction_and_status ON messages(direction, status);
CREATE INDEX IF NOT EXISTS index_messages_on_sender_id_and_recipient_id ON messages(sender_id, recipient_id);
CREATE INDEX IF NOT EXISTS index_messages_on_recipient_and_sender_for_conversations ON messages(recipient_id, sender_id);
CREATE INDEX IF NOT EXISTS index_messages_on_sender_and_recipient_for_conversations ON messages(sender_id, recipient_id);
CREATE INDEX IF NOT EXISTS index_messages_on_sender_id_and_recipient_id_and_created_at ON messages(sender_id, recipient_id, created_at);
CREATE INDEX IF NOT EXISTS index_messages_conversation_with_date ON messages(sender_id, recipient_id, created_at);
CREATE INDEX IF NOT EXISTS index_messages_on_ticket_id_and_direction_and_created_at ON messages(ticket_id, direction, created_at);
CREATE INDEX IF NOT EXISTS idx_messages_on_fields ON messages(new_sender_phone, direction, content, sent_at);

-- Pushed Messages (mensajes en cola)
CREATE TABLE IF NOT EXISTS pushed_messages (
    id BIGSERIAL PRIMARY KEY,
    sender_id BIGINT NOT NULL,
    new_sender_phone TEXT,
    recipient_id BIGINT NOT NULL,
    device_id INTEGER,
    direction INTEGER DEFAULT 0,
    status INTEGER DEFAULT 0,
    content TEXT NOT NULL,
    binary_content_data TEXT,
    whatsapp_business_routed BOOLEAN DEFAULT FALSE,
    original_whatsapp_business_recipient_id INTEGER,
    is_event BOOLEAN DEFAULT FALSE,
    processed BOOLEAN DEFAULT FALSE,
    already_ignored BOOLEAN DEFAULT FALSE,
    sent_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS index_pushed_messages_on_sender_id ON pushed_messages(sender_id);
CREATE INDEX IF NOT EXISTS index_pushed_messages_on_recipient_id ON pushed_messages(recipient_id);
CREATE INDEX IF NOT EXISTS index_pushed_messages_on_created_at ON pushed_messages(created_at);
CREATE INDEX IF NOT EXISTS index_pushed_messages_on_processed ON pushed_messages(processed);
CREATE INDEX IF NOT EXISTS idx_pushed_messages_on_fields ON pushed_messages(new_sender_phone, direction, content, sent_at);

-- =====================================================
-- MESSAGE TEMPLATES
-- =====================================================

CREATE TABLE IF NOT EXISTS message_templates (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    client_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    category INTEGER DEFAULT 0,
    template_whatsapp_type INTEGER DEFAULT 0,
    language_id BIGINT NOT NULL,
    header_media_type INTEGER DEFAULT 0,
    header_content VARCHAR(255),
    header_binary_data VARCHAR(255),
    body_content VARCHAR(255),
    footer_content VARCHAR(255),
    tot_buttons INTEGER DEFAULT 0,
    closes_ticket BOOLEAN DEFAULT FALSE,
    template_whatsapp_status INTEGER DEFAULT 0,
    visibility INTEGER DEFAULT 0,
    status INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS index_message_templates_on_user_id ON message_templates(user_id);
CREATE INDEX IF NOT EXISTS index_message_templates_on_client_id ON message_templates(client_id);
CREATE INDEX IF NOT EXISTS index_message_templates_on_language_id ON message_templates(language_id);

-- Message Template Params
CREATE TABLE IF NOT EXISTS message_template_params (
    id BIGSERIAL PRIMARY KEY,
    message_template_id BIGINT NOT NULL,
    component INTEGER DEFAULT 0,
    position INTEGER NOT NULL,
    data_field VARCHAR(255),
    default_value VARCHAR(255),
    status INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS index_message_template_params_on_message_template_id ON message_template_params(message_template_id);

-- Template Bulk Sends
CREATE TABLE IF NOT EXISTS template_bulk_sends (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    client_id BIGINT NOT NULL,
    message_template_id INTEGER NOT NULL,
    message_template_name VARCHAR(255) NOT NULL,
    planned_count INTEGER DEFAULT 0,
    sent_count INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS index_template_bulk_sends_on_user_id ON template_bulk_sends(user_id);
CREATE INDEX IF NOT EXISTS index_template_bulk_sends_on_client_id ON template_bulk_sends(client_id);

-- =====================================================
-- KPIS
-- =====================================================

CREATE TABLE IF NOT EXISTS kpis (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL,
    user_id INTEGER,
    kpi_type INTEGER DEFAULT 0,
    value INTEGER DEFAULT 1,
    data_hash JSONB DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ticket_id INTEGER
);
CREATE INDEX IF NOT EXISTS index_kpis_on_client_id ON kpis(client_id);
CREATE INDEX IF NOT EXISTS index_kpis_on_user_id ON kpis(user_id);
CREATE INDEX IF NOT EXISTS index_kpis_on_kpi_type ON kpis(kpi_type);
CREATE INDEX IF NOT EXISTS index_kpis_on_ticket_id ON kpis(ticket_id);
CREATE INDEX IF NOT EXISTS index_kpis_on_created_at ON kpis(created_at);
CREATE INDEX IF NOT EXISTS index_kpis_on_client_id_and_kpi_type_and_created_at ON kpis(client_id, kpi_type, created_at);
CREATE INDEX IF NOT EXISTS index_kpis_on_user_id_and_kpi_type_and_created_at ON kpis(user_id, kpi_type, created_at);
CREATE INDEX IF NOT EXISTS index_kpis_on_data_hash ON kpis USING gin(data_hash);

-- KPI Counters
CREATE TABLE IF NOT EXISTS kpi_counters (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    kpi_type INTEGER NOT NULL,
    count INTEGER DEFAULT 0 NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS index_kpi_counters_on_client_id ON kpi_counters(client_id);
CREATE INDEX IF NOT EXISTS index_kpi_counters_on_user_id ON kpi_counters(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS index_kpi_counters_on_user_id_and_kpi_type ON kpi_counters(user_id, kpi_type);

-- =====================================================
-- AUDITS (para audited gem compatibility)
-- =====================================================

CREATE TABLE IF NOT EXISTS audits (
    id BIGSERIAL PRIMARY KEY,
    auditable_id INTEGER,
    auditable_type VARCHAR(255),
    associated_id INTEGER,
    associated_type VARCHAR(255),
    user_id INTEGER,
    user_type VARCHAR(255),
    username VARCHAR(255),
    action VARCHAR(255),
    audited_changes JSONB,
    version INTEGER DEFAULT 0,
    comment VARCHAR(255),
    remote_address VARCHAR(255),
    request_uuid VARCHAR(255),
    created_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS auditable_index ON audits(auditable_type, auditable_id, version);
CREATE INDEX IF NOT EXISTS associated_index ON audits(associated_type, associated_id);
CREATE INDEX IF NOT EXISTS user_index ON audits(user_id, user_type);
CREATE INDEX IF NOT EXISTS index_audits_on_created_at ON audits(created_at);
CREATE INDEX IF NOT EXISTS index_audits_on_request_uuid ON audits(request_uuid);

-- =====================================================
-- SCHEDULED JOBS (para Spring Boot - tabla adicional)
-- =====================================================

CREATE TABLE IF NOT EXISTS scheduled_jobs (
    id BIGSERIAL PRIMARY KEY,
    job_name VARCHAR(255) NOT NULL,
    job_type VARCHAR(255) NOT NULL,
    job_data TEXT,
    execute_at TIMESTAMP NOT NULL,
    status VARCHAR(50) DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    executed_at TIMESTAMP,
    error_message TEXT
);
CREATE INDEX IF NOT EXISTS index_scheduled_jobs_on_status ON scheduled_jobs(status);
CREATE INDEX IF NOT EXISTS index_scheduled_jobs_on_execute_at ON scheduled_jobs(execute_at);
CREATE INDEX IF NOT EXISTS index_scheduled_jobs_on_job_type ON scheduled_jobs(job_type);

-- =====================================================
-- FIN DE CREACION DE TABLAS
-- =====================================================

SELECT 'Tablas creadas exitosamente' AS resultado;
