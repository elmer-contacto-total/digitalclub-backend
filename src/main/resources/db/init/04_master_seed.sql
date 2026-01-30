-- =====================================================
-- MASTER SEED - Basado en Rails db/seeds.rb
-- Ejecutar: psql -d digitalgroup_development -f 04_master_seed.sql
-- =====================================================
-- Password por defecto: "12345678" (igual que Rails)
-- BCrypt hash (strength 12): $2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu
--
-- DESARROLLO: Usar password universal "test123" y OTP "123456"
-- =====================================================

BEGIN;

-- =====================================================
-- PASO 1: LIMPIAR TODAS LAS TABLAS (orden inverso por FK)
-- =====================================================

TRUNCATE TABLE audits CASCADE;
TRUNCATE TABLE user_manager_histories CASCADE;
TRUNCATE TABLE kpi_counters CASCADE;
TRUNCATE TABLE kpis CASCADE;
TRUNCATE TABLE template_bulk_sends CASCADE;
TRUNCATE TABLE canned_messages CASCADE;
TRUNCATE TABLE bulk_messages CASCADE;
TRUNCATE TABLE pushed_messages CASCADE;
TRUNCATE TABLE messages CASCADE;
TRUNCATE TABLE tickets CASCADE;
TRUNCATE TABLE message_template_params CASCADE;
TRUNCATE TABLE message_templates CASCADE;
TRUNCATE TABLE temp_import_users CASCADE;
TRUNCATE TABLE imports CASCADE;
TRUNCATE TABLE prospects CASCADE;
TRUNCATE TABLE alerts CASCADE;
TRUNCATE TABLE device_infos CASCADE;
TRUNCATE TABLE crm_infos CASCADE;
TRUNCATE TABLE user_settings CASCADE;
TRUNCATE TABLE user_profiles CASCADE;
TRUNCATE TABLE users CASCADE;
TRUNCATE TABLE crm_info_settings CASCADE;
TRUNCATE TABLE client_settings CASCADE;
TRUNCATE TABLE client_structures CASCADE;
TRUNCATE TABLE clients CASCADE;
TRUNCATE TABLE settings CASCADE;
TRUNCATE TABLE languages CASCADE;
TRUNCATE TABLE countries CASCADE;

-- =====================================================
-- PASO 2: SETTINGS GLOBALES (igual que Rails)
-- =====================================================

INSERT INTO settings (id, name, localized_name, internal, data_type, string_value, integer_value, boolean_value, hash_value, status, created_at, updated_at)
VALUES
    (1, 'clients_have_structure', 'Los clientes tienen estructura', true, 4, NULL, NULL, true, '{}', 0, NOW(), NOW()),
    (2, 'whatsapp_business_blocked_hours', 'Horas luego de las cuales se bloquean las respuestas de Whatsapp Business', true, 1, NULL, 24, NULL, '{}', 0, NOW(), NOW());

SELECT setval('settings_id_seq', 2);

-- =====================================================
-- PASO 3: IDIOMAS (igual que Rails)
-- =====================================================

INSERT INTO languages (id, name, language_code, status, display_order, created_at, updated_at)
VALUES
    (1, 'Español', 'es', 0, 1, NOW(), NOW()),
    (2, 'English', 'en', 0, 2, NOW(), NOW()),
    (3, 'English', 'en_US', 0, 3, NOW(), NOW());

SELECT setval('languages_id_seq', 3);

-- =====================================================
-- PASO 4: PAISES
-- =====================================================

INSERT INTO countries (id, name, iso_code, flag_url, default_locale, default_phone_country_code, default_currency, created_at, updated_at)
VALUES
    (1, 'Perú', 'PE', 'https://flagcdn.com/pe.svg', 'es', '51', 'PEN', NOW(), NOW());

SELECT setval('countries_id_seq', 1);

-- =====================================================
-- PASO 5: CLIENTES (igual que Rails)
-- WizApp (id=1), Devtech Perú (id=2), Financiera Oh (id=3)
-- =====================================================

INSERT INTO clients (id, name, company_name, doc_type, doc_number, country_id, client_type, status, created_at, updated_at)
VALUES
    (1, 'WizApp', 'WizApp SAC', 1, '20507134157', 1, 0, 0, NOW(), NOW()),
    (2, 'Devtech Perú', 'DevTech Perú', 1, '20507134158', 1, 0, 0, NOW(), NOW()),
    (3, 'Financiera Oh', 'Financiera Oh SAC', 1, '20507134159', 1, 0, 0, NOW(), NOW());

SELECT setval('clients_id_seq', 3);

-- =====================================================
-- PASO 6: CLIENT STRUCTURES
-- =====================================================

INSERT INTO client_structures (id, client_id, manager_level_1, exists_manager_level_1, manager_level_2, exists_manager_level_2, manager_level_3, exists_manager_level_3, manager_level_4, exists_manager_level_4, agent, exists_agent, created_at, updated_at)
VALUES
    (1, 1, '', false, '', false, '', false, 'Supervisor', true, 'Sectorista', true, NOW(), NOW()),
    (2, 2, '', false, '', false, '', false, 'Supervisor', true, 'Sectorista', true, NOW(), NOW()),
    (3, 3, '', false, '', false, '', false, 'Supervisor', true, 'Agente', true, NOW(), NOW());

SELECT setval('client_structures_id_seq', 3);

-- =====================================================
-- PASO 7: CLIENT SETTINGS (completos, igual que Rails)
-- Solo para clientes 2 y 3 (no WizApp)
-- =====================================================

-- Devtech Perú (client_id = 2)
INSERT INTO client_settings (id, client_id, name, localized_name, internal, data_type, string_value, integer_value, boolean_value, hash_value, status, created_at, updated_at)
VALUES
    (1, 2, 'agents_go_offline', '1. Los Agentes tienen horarios fuera de línea', false, 4, NULL, NULL, true, '{}', 0, NOW(), NOW()),
    (2, 2, 'online_monday', '2. Horario en línea lunes (ej. 9am - 5pm)', false, 0, '9am - 6pm', NULL, NULL, '{}', 0, NOW(), NOW()),
    (3, 2, 'online_tuesday', '3. Horario en línea martes (ej. 9am - 5pm)', false, 0, '9am - 6pm', NULL, NULL, '{}', 0, NOW(), NOW()),
    (4, 2, 'online_wednesday', '4. Horario en línea miércoles (ej. 9am - 5pm)', false, 0, '9am - 6pm', NULL, NULL, '{}', 0, NOW(), NOW()),
    (5, 2, 'online_thursday', '5. Horario en línea jueves (ej. 9am - 5pm)', false, 0, '9am - 6pm', NULL, NULL, '{}', 0, NOW(), NOW()),
    (6, 2, 'online_friday', '6. Horario en línea viernes (ej. 9am - 5pm)', false, 0, '9am - 6pm', NULL, NULL, '{}', 0, NOW(), NOW()),
    (7, 2, 'online_saturday', '7. Horario en línea sábado (ej. en blanco)', false, 0, '', NULL, NULL, '{}', 0, NOW(), NOW()),
    (8, 2, 'online_sunday', '8. Horario en línea domingo (ej. en blanco)', false, 0, '', NULL, NULL, '{}', 0, NOW(), NOW()),
    (9, 2, 'ticket_close_types', 'Tipos de cierre de casos', false, 5, NULL, NULL, NULL, '[{"name": "Con Acuerdo", "kpi_name": "closed_con_acuerdo"}, {"name": "Sin Acuerdo", "kpi_name": "closed_sin_acuerdo"}]', 0, NOW(), NOW()),
    (10, 2, 'alert_time_not_responded_conversation', 'Tiempo en min para Alerta de Mensaje no Respondido', false, 1, NULL, 15, NULL, '{}', 0, NOW(), NOW()),
    (11, 2, 'whatsapp_api_token', 'Token Whatsapp API', false, 0, '', NULL, NULL, '{}', 0, NOW(), NOW()),
    (12, 2, 'whatsapp_account_id', 'Id de la Cuenta Whatsapp', false, 0, '', NULL, NULL, '{}', 0, NOW(), NOW()),
    (13, 2, 'whatsapp_phone_number_id', 'Id del número móvil Whatsapp', false, 0, '', NULL, NULL, '{}', 0, NOW(), NOW()),
    (14, 2, 'create_user_from_prospect', 'Una comunicación de un prospecto crea a un usuario', true, 4, NULL, NULL, false, '{}', 0, NOW(), NOW()),

    -- Financiera Oh (client_id = 3)
    (15, 3, 'agents_go_offline', '1. Los Agentes tienen horarios fuera de línea', false, 4, NULL, NULL, true, '{}', 0, NOW(), NOW()),
    (16, 3, 'online_monday', '2. Horario en línea lunes (ej. 9am - 5pm)', false, 0, '9am - 6pm', NULL, NULL, '{}', 0, NOW(), NOW()),
    (17, 3, 'online_tuesday', '3. Horario en línea martes (ej. 9am - 5pm)', false, 0, '9am - 6pm', NULL, NULL, '{}', 0, NOW(), NOW()),
    (18, 3, 'online_wednesday', '4. Horario en línea miércoles (ej. 9am - 5pm)', false, 0, '9am - 6pm', NULL, NULL, '{}', 0, NOW(), NOW()),
    (19, 3, 'online_thursday', '5. Horario en línea jueves (ej. 9am - 5pm)', false, 0, '9am - 6pm', NULL, NULL, '{}', 0, NOW(), NOW()),
    (20, 3, 'online_friday', '6. Horario en línea viernes (ej. 9am - 5pm)', false, 0, '9am - 6pm', NULL, NULL, '{}', 0, NOW(), NOW()),
    (21, 3, 'online_saturday', '7. Horario en línea sábado (ej. en blanco)', false, 0, '', NULL, NULL, '{}', 0, NOW(), NOW()),
    (22, 3, 'online_sunday', '8. Horario en línea domingo (ej. en blanco)', false, 0, '', NULL, NULL, '{}', 0, NOW(), NOW()),
    (23, 3, 'ticket_close_types', 'Tipos de cierre de casos', false, 5, NULL, NULL, NULL, '[{"name": "Con Acuerdo", "kpi_name": "closed_con_acuerdo"}, {"name": "Sin Acuerdo", "kpi_name": "closed_sin_acuerdo"}]', 0, NOW(), NOW()),
    (24, 3, 'alert_time_not_responded_conversation', 'Tiempo en min para Alerta de Mensaje no Respondido', false, 1, NULL, 15, NULL, '{}', 0, NOW(), NOW()),
    (25, 3, 'whatsapp_api_token', 'Token Whatsapp API', false, 0, '', NULL, NULL, '{}', 0, NOW(), NOW()),
    (26, 3, 'whatsapp_account_id', 'Id de la Cuenta Whatsapp', false, 0, '', NULL, NULL, '{}', 0, NOW(), NOW()),
    (27, 3, 'whatsapp_phone_number_id', 'Id del número móvil Whatsapp', false, 0, '', NULL, NULL, '{}', 0, NOW(), NOW()),
    (28, 3, 'create_user_from_prospect', 'Una comunicación de un prospecto crea a un usuario', true, 4, NULL, NULL, false, '{}', 0, NOW(), NOW());

SELECT setval('client_settings_id_seq', 28);

-- =====================================================
-- PASO 8: USUARIOS
-- Password: "12345678" -> BCrypt(12)
-- Codigo formato: D0000<DNI>
-- =====================================================

-- BCrypt hash para "12345678" con strength 12
-- $2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu

INSERT INTO users (id, manager_id, email, encrypted_password, first_name, last_name, username, phone, client_id, country_id, role, status, time_zone, uuid_token, locale, can_create_users, initial_password_changed, codigo, custom_fields, require_response, require_close_ticket, created_at, updated_at)
VALUES
    -- ========== WIZAPP (client_id = 1) ==========

    -- Usuario placeholder para prospectos
    (1, NULL, 'prospect_user@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Prospecto', ' | ', 'prospect_user', '986976001', 1, 1, 0, 0, 'America/Lima', 'uuid-prospect-001', 'es', false, true, NULL, '{}', false, false, NOW(), NOW()),

    -- Super Admins WizApp
    (2, NULL, 'augusto@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Augusto', 'Samamé', 'augusto', '986976300', 1, 1, 1, 0, 'America/Lima', 'uuid-augusto-002', 'es', true, true, NULL, '{}', false, false, NOW(), NOW()),
    (3, NULL, 'sistemas@digitalclub.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Sistemas', 'WizApp', 'sistemas', '986976371', 1, 1, 1, 0, 'America/Lima', 'uuid-sistemas-003', 'es', true, true, NULL, '{}', false, false, NOW(), NOW()),

    -- ========== DEVTECH PERÚ (client_id = 2) ==========

    -- Admin
    (4, NULL, 'augustosamame@gmail.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Admin', 'Devtech Perú', 'admin_devtech', '986976372', 2, 1, 2, 0, 'America/Lima', 'uuid-admin-devtech-004', 'es', true, true, NULL, '{}', false, false, NOW(), NOW()),

    -- WhatsApp Business Devtech
    (5, 4, 'whatsappbusiness1@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'WhatsApp Business', 'DevTech Perú', 'wabot_devtech', '951396491', 2, 1, 9, 0, 'America/Lima', 'uuid-wabot-devtech-005', 'es', false, true, NULL, '{}', false, false, NOW(), NOW()),

    -- Supervisor Devtech
    (6, 4, 'supervisor1@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Augusto', 'Supervisor1', 'supervisor1_devtech', '986976301', 2, 1, 6, 0, 'America/Lima', 'uuid-supervisor1-devtech-006', 'es', false, true, NULL, '{}', false, false, NOW(), NOW()),

    -- Sectoristas (Agentes) Devtech
    (7, 6, 'sectorista1@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Augusto', 'Sectorista1', 'sectorista1', '971724019', 2, 1, 7, 0, 'America/Lima', 'uuid-sectorista1-007', 'es', false, true, NULL, '{}', false, false, NOW(), NOW()),
    (8, 6, 'sectorista2@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Augusto', 'Sectorista2', 'sectorista2', '915359895', 2, 1, 7, 0, 'America/Lima', 'uuid-sectorista2-008', 'es', false, true, NULL, '{}', false, false, NOW(), NOW()),
    (9, 6, 'sectorista3@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Hugo', 'Sectorista3', 'sectorista3', '997675624', 2, 1, 7, 0, 'America/Lima', 'uuid-sectorista3-009', 'es', false, true, NULL, '{}', false, false, NOW(), NOW()),

    -- Clientes (standard) Devtech - Formato codigo: D0000<DNI>
    (10, 7, '980726728@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Frank', 'Giraldo', 'frank_giraldo', '980726728', 2, 1, 0, 0, 'America/Lima', 'uuid-cliente-010', 'es', false, true, 'D000012345678', '{}', false, false, NOW(), NOW()),
    (11, 7, '983560253@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Mateo', 'Romay', 'mateo_romay', '983560253', 2, 1, 0, 0, 'America/Lima', 'uuid-cliente-011', 'es', false, true, 'D000023456789', '{}', false, false, NOW(), NOW()),
    (12, 7, '992465296@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Jorge', 'Dávila', 'jorge_davila', '992465296', 2, 1, 0, 0, 'America/Lima', 'uuid-cliente-012', 'es', false, true, 'D000034567890', '{}', false, false, NOW(), NOW()),
    (13, 7, '999987946@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Priscilla', 'Gallo', 'priscilla_gallo', '999987946', 2, 1, 0, 0, 'America/Lima', 'uuid-cliente-013', 'es', false, true, 'D000045678901', '{}', false, false, NOW(), NOW()),
    (14, 7, '994036686@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Diego', 'Mesones', 'diego_mesones', '994036686', 2, 1, 0, 0, 'America/Lima', 'uuid-cliente-014', 'es', false, true, 'D000056789012', '{}', false, false, NOW(), NOW()),
    (15, 8, '936585777@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Leonardo', 'Molina', 'leonardo_molina', '936585777', 2, 1, 0, 0, 'America/Lima', 'uuid-cliente-015', 'es', false, true, 'D000067890123', '{}', false, false, NOW(), NOW()),
    (16, 8, '979758330@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Marcelo', 'Villanueva', 'marcelo_villanueva', '979758330', 2, 1, 0, 0, 'America/Lima', 'uuid-cliente-016', 'es', false, true, 'D000078901234', '{}', false, false, NOW(), NOW()),
    (17, 8, '994607114@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Carlos', 'Mesones', 'carlos_mesones', '994607114', 2, 1, 0, 0, 'America/Lima', 'uuid-cliente-017', 'es', false, true, 'D000089012345', '{}', false, false, NOW(), NOW()),
    (18, 9, '991752239@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Gonzalo', 'Tejada', 'gonzalo_tejada', '991752239', 2, 1, 0, 0, 'America/Lima', 'uuid-cliente-018', 'es', false, true, 'D000090123456', '{}', false, false, NOW(), NOW()),
    (19, 9, '973999252@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Rodrigo', 'Matos', 'rodrigo_matos', '973999252', 2, 1, 0, 0, 'America/Lima', 'uuid-cliente-019', 'es', false, true, 'D000001234567', '{}', false, false, NOW(), NOW()),

    -- ========== FINANCIERA OH (client_id = 3) ==========

    -- Admin FOH
    (20, NULL, 'admin_foh@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Admin', 'FOH', 'admin_foh', '986976373', 3, 1, 2, 0, 'America/Lima', 'uuid-admin-foh-020', 'es', true, true, NULL, '{}', false, false, NOW(), NOW()),

    -- Staff FOH
    (21, 20, 'staff_foh@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Staff', 'FOH', 'staff_foh', '986976374', 3, 1, 2, 0, 'America/Lima', 'uuid-staff-foh-021', 'es', true, true, NULL, '{}', false, false, NOW(), NOW()),

    -- Supervisor FOH
    (22, 20, 'supervisor_foh@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Supervisor', 'FOH', 'supervisor_foh', '986976375', 3, 1, 6, 0, 'America/Lima', 'uuid-supervisor-foh-022', 'es', false, true, NULL, '{}', false, false, NOW(), NOW()),

    -- Agentes FOH
    (23, 22, 'agente1_foh@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Agente1', 'FOH', 'agente1_foh', '971724020', 3, 1, 7, 0, 'America/Lima', 'uuid-agente1-foh-023', 'es', false, true, NULL, '{}', false, false, NOW(), NOW()),
    (24, 22, 'agente2_foh@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Agente2', 'FOH', 'agente2_foh', '915359825', 3, 1, 7, 0, 'America/Lima', 'uuid-agente2-foh-024', 'es', false, true, NULL, '{}', false, false, NOW(), NOW()),
    (25, 22, 'agente3_foh@devtechperu.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Agente3', 'FOH', 'agente3_foh', '997675625', 3, 1, 7, 0, 'America/Lima', 'uuid-agente3-foh-025', 'es', false, true, NULL, '{}', false, false, NOW(), NOW()),

    -- Clientes (standard) FOH - Formato codigo: D0000<DNI>
    (26, 23, 'cliente1_foh@demo.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'María', 'García López', 'maria_garcia', '987654321', 3, 1, 0, 0, 'America/Lima', 'uuid-cliente1-foh-026', 'es', false, true, 'D000070123456', '{}', true, false, NOW(), NOW()),
    (27, 23, 'cliente2_foh@demo.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'José', 'Pérez Ruiz', 'jose_perez', '987654322', 3, 1, 0, 0, 'America/Lima', 'uuid-cliente2-foh-027', 'es', false, true, 'D000070234567', '{}', false, true, NOW(), NOW()),
    (28, 24, 'cliente3_foh@demo.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Ana', 'Torres Vega', 'ana_torres', '987654323', 3, 1, 0, 0, 'America/Lima', 'uuid-cliente3-foh-028', 'es', false, true, 'D000070345678', '{}', true, true, NOW(), NOW()),
    (29, 24, 'cliente4_foh@demo.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Luis', 'Mendoza Quispe', 'luis_mendoza', '987654324', 3, 1, 0, 0, 'America/Lima', 'uuid-cliente4-foh-029', 'es', false, true, 'D000070456789', '{}', false, false, NOW(), NOW()),
    (30, 25, 'cliente5_foh@demo.com', '$2a$12$8kT5V5p4xqQxnIQFZ1rZxOzGVJl5H1DBL3LpFN8M3VHmVk4Lm0xKu',
     'Carmen', 'Sánchez Díaz', 'carmen_sanchez', '987654325', 3, 1, 0, 0, 'America/Lima', 'uuid-cliente5-foh-030', 'es', false, true, 'D000070567890', '{}', false, false, NOW(), NOW());

SELECT setval('users_id_seq', 30);

-- Actualizar last_message_at para algunos usuarios
UPDATE users SET last_message_at = NOW() - INTERVAL '1 hour' WHERE id IN (26, 27);
UPDATE users SET last_message_at = NOW() - INTERVAL '1 day' WHERE id IN (28, 29);
UPDATE users SET last_message_at = NOW() - INTERVAL '1 week' WHERE id IN (10, 11, 12);

-- =====================================================
-- PASO 9: BULK MESSAGES (igual que Rails)
-- =====================================================

INSERT INTO bulk_messages (id, client_id, user_id, message, client_global, status, created_at, updated_at)
VALUES
    -- Devtech Perú
    (1, 2, 4, 'Estaré ausente el día de mañana. Cualquier consulta comunicarse con xxxxxx', true, 0, NOW(), NOW()),
    (2, 2, 4, 'El día de mañana la atención será solo hasta la 1pm. Favor tomar en cuenta esto para cualquier consulta', true, 0, NOW(), NOW()),
    (3, 2, 4, 'Solo por hoy, solicite un crédito de consumo a una tasa 2% menor a la tasa preferencial', true, 0, NOW(), NOW()),
    (4, 2, 4, 'Obtenga su seguro contra robos para su negocio desde 29 soles al mes. Consúlteme aquí', true, 0, NOW(), NOW()),
    (5, 2, 4, 'Estaré de vacaciones hasta el 15 de marzo. Cualquier consulta communicarse con xxxxxx', true, 0, NOW(), NOW()),
    -- Financiera Oh
    (6, 3, 20, 'Estaré ausente el día de mañana. Cualquier consulta comunicarse con xxxxxx', true, 0, NOW(), NOW()),
    (7, 3, 20, 'El día de mañana la atención será solo hasta la 1pm. Favor tomar en cuenta esto para cualquier consulta', true, 0, NOW(), NOW()),
    (8, 3, 20, 'Solo por hoy, solicite un crédito de consumo a una tasa 2% menor a la tasa preferencial', true, 0, NOW(), NOW()),
    (9, 3, 20, 'Obtenga su seguro contra robos para su negocio desde 29 soles al mes. Consúlteme aquí', true, 0, NOW(), NOW()),
    (10, 3, 20, 'Estaré de vacaciones hasta el 15 de marzo. Cualquier consulta communicarse con xxxxxx', true, 0, NOW(), NOW());

SELECT setval('bulk_messages_id_seq', 10);

-- =====================================================
-- PASO 10: CANNED MESSAGES (igual que Rails)
-- =====================================================

INSERT INTO canned_messages (id, client_id, user_id, message, client_global, status, created_at, updated_at)
VALUES
    -- Devtech Perú
    (1, 2, 4, 'Ha sido un gusto atenderlo. No dude en comunicarse conmigo cualquier consulta adicional', true, 0, NOW(), NOW()),
    (2, 2, 4, 'Estuve esperando su respuesta. Estamos aquí para servirlo', true, 0, NOW(), NOW()),
    (3, 2, 4, 'Hay algo más en que lo podamos ayudar?', true, 0, NOW(), NOW()),
    -- Financiera Oh
    (4, 3, 20, 'Ha sido un gusto atenderlo. No dude en comunicarse conmigo cualquier consulta adicional', true, 0, NOW(), NOW()),
    (5, 3, 20, 'Estuve esperando su respuesta. Estamos aquí para servirlo', true, 0, NOW(), NOW()),
    (6, 3, 20, 'Hay algo más en que lo podamos ayudar?', true, 0, NOW(), NOW()),
    -- Mensajes personales de agentes FOH
    (7, 3, 23, 'Buenos días, soy su ejecutivo asignado. ¿En qué puedo ayudarle?', false, 0, NOW(), NOW()),
    (8, 3, 23, 'Le recuerdo que su próximo pago vence pronto. ¿Desea programar una cita?', false, 0, NOW(), NOW());

SELECT setval('canned_messages_id_seq', 8);

-- =====================================================
-- PASO 11: CRM INFO SETTINGS (para Financiera Oh)
-- =====================================================

INSERT INTO crm_info_settings (id, client_id, column_position, column_type, column_label, column_visible, status, created_at, updated_at)
VALUES
    (1, 3, 1, 0, 'DNI', true, 0, NOW(), NOW()),
    (2, 3, 2, 0, 'PRODUCTO', true, 0, NOW(), NOW()),
    (3, 3, 3, 0, 'SEDE', true, 0, NOW(), NOW()),
    (4, 3, 4, 0, 'CATEGORIA', true, 0, NOW(), NOW()),
    (5, 3, 5, 1, 'PORC_DESCUENTO', true, 0, NOW(), NOW()),
    (6, 3, 6, 0, 'ESTADO', true, 0, NOW(), NOW()),
    (7, 3, 7, 1, 'LINEA_ACTUAL', true, 0, NOW(), NOW()),
    (8, 3, 8, 1, 'LINEA_AMPLIA', true, 0, NOW(), NOW()),
    (9, 3, 9, 1, 'MONTO', true, 0, NOW(), NOW()),
    (10, 3, 10, 1, 'PLAZO', true, 0, NOW(), NOW()),
    (11, 3, 11, 2, 'TASA', true, 0, NOW(), NOW()),
    (12, 3, 12, 0, 'EQUIPO', true, 0, NOW(), NOW());

SELECT setval('crm_info_settings_id_seq', 12);

-- =====================================================
-- PASO 12: CRM INFOS (valores para clientes FOH)
-- =====================================================

INSERT INTO crm_infos (id, user_id, crm_info_setting_id, column_position, column_label, column_visible, column_value, created_at, updated_at)
VALUES
    -- Cliente 26 (María García)
    (1, 26, 1, 1, 'DNI', true, '70123456', NOW(), NOW()),
    (2, 26, 2, 2, 'PRODUCTO', true, 'Tarjeta Oh', NOW(), NOW()),
    (3, 26, 3, 3, 'SEDE', true, 'Lima Centro', NOW(), NOW()),
    (4, 26, 4, 4, 'CATEGORIA', true, 'Premium', NOW(), NOW()),
    (5, 26, 5, 5, 'PORC_DESCUENTO', true, '15', NOW(), NOW()),
    (6, 26, 6, 6, 'ESTADO', true, 'Activo', NOW(), NOW()),
    (7, 26, 7, 7, 'LINEA_ACTUAL', true, '5000', NOW(), NOW()),
    (8, 26, 8, 8, 'LINEA_AMPLIA', true, '8000', NOW(), NOW()),
    (9, 26, 9, 9, 'MONTO', true, '3500', NOW(), NOW()),
    (10, 26, 10, 10, 'PLAZO', true, '12', NOW(), NOW()),
    (11, 26, 11, 11, 'TASA', true, '2.5', NOW(), NOW()),
    (12, 26, 12, 12, 'EQUIPO', true, 'Cobranzas A', NOW(), NOW()),

    -- Cliente 27 (José Pérez)
    (13, 27, 1, 1, 'DNI', true, '70234567', NOW(), NOW()),
    (14, 27, 2, 2, 'PRODUCTO', true, 'Préstamo Personal', NOW(), NOW()),
    (15, 27, 3, 3, 'SEDE', true, 'Lima Norte', NOW(), NOW()),
    (16, 27, 4, 4, 'CATEGORIA', true, 'Standard', NOW(), NOW()),
    (17, 27, 9, 9, 'MONTO', true, '12000', NOW(), NOW()),
    (18, 27, 10, 10, 'PLAZO', true, '24', NOW(), NOW());

SELECT setval('crm_infos_id_seq', 18);

-- =====================================================
-- PASO 13: TICKETS
-- =====================================================

INSERT INTO tickets (id, user_id, agent_id, closed_at, subject, notes, status, close_type, created_at, updated_at)
VALUES
    -- Tickets abiertos FOH
    (1, 26, 23, NULL, 'Consulta sobre línea de crédito', 'Cliente consulta ampliación de línea', 0, NULL, NOW(), NOW()),
    (2, 27, 23, NULL, 'Problema con pago', 'Cliente reporta doble cobro', 0, NULL, NOW() - INTERVAL '2 hours', NOW()),
    (3, 28, 24, NULL, 'Solicitud de refinanciamiento', 'Cliente solicita reestructuración de deuda', 0, NULL, NOW() - INTERVAL '1 day', NOW()),

    -- Tickets cerrados FOH
    (4, 29, 24, NOW() - INTERVAL '2 days', 'Consulta resuelta', 'Se brindó información solicitada', 1, 'con_acuerdo', NOW() - INTERVAL '3 days', NOW() - INTERVAL '2 days'),
    (5, 30, 25, NOW() - INTERVAL '5 days', 'Cliente no interesado', 'Cliente declinó oferta de producto', 1, 'sin_acuerdo', NOW() - INTERVAL '6 days', NOW() - INTERVAL '5 days'),

    -- Tickets Devtech
    (6, 10, 7, NULL, 'Consulta general', 'Cliente con dudas sobre servicio', 0, NULL, NOW(), NOW()),
    (7, 11, 7, NOW() - INTERVAL '1 day', 'Caso resuelto', 'Se resolvió consulta', 1, 'con_acuerdo', NOW() - INTERVAL '2 days', NOW() - INTERVAL '1 day');

SELECT setval('tickets_id_seq', 7);

-- =====================================================
-- PASO 14: MESSAGES
-- =====================================================

INSERT INTO messages (id, sender_id, new_sender_phone, recipient_id, is_prospect, prospect_sender_id, prospect_recipient_id, ticket_id, device_id, direction, status, content, binary_content_data, whatsapp_business_routed, original_whatsapp_business_recipient_id, is_event, processed, sent_at, is_template, template_name, historic_sender_name, worker_processed_at, message_order, created_at, updated_at)
VALUES
    -- Ticket 1: Conversación activa FOH
    (1, 26, NULL, 23, false, NULL, NULL, 1, NULL, 0, 3, 'Hola, quisiera saber si puedo ampliar mi línea de crédito', NULL, false, NULL, false, true, NOW() - INTERVAL '1 hour', false, NULL, 'María García López', NOW() - INTERVAL '1 hour', 1, NOW() - INTERVAL '1 hour', NOW()),
    (2, 23, NULL, 26, false, NULL, NULL, 1, NULL, 1, 3, 'Buenos días María! Con gusto le ayudo. Déjeme verificar su cuenta.', NULL, false, NULL, false, true, NOW() - INTERVAL '55 minutes', false, NULL, 'Agente1 FOH', NOW() - INTERVAL '55 minutes', 2, NOW() - INTERVAL '55 minutes', NOW()),
    (3, 23, NULL, 26, false, NULL, NULL, 1, NULL, 1, 3, 'Veo que tiene disponible una ampliación de S/. 3,000. ¿Desea solicitarla?', NULL, false, NULL, false, true, NOW() - INTERVAL '50 minutes', false, NULL, 'Agente1 FOH', NOW() - INTERVAL '50 minutes', 3, NOW() - INTERVAL '50 minutes', NOW()),
    (4, 26, NULL, 23, false, NULL, NULL, 1, NULL, 0, 2, 'Sí, me interesa. ¿Cuáles son los requisitos?', NULL, false, NULL, false, true, NOW() - INTERVAL '30 minutes', false, NULL, 'María García López', NOW() - INTERVAL '30 minutes', 4, NOW() - INTERVAL '30 minutes', NOW()),

    -- Ticket 2: Problema con pago
    (5, 27, NULL, 23, false, NULL, NULL, 2, NULL, 0, 3, 'Me cobraron doble en mi última cuota', NULL, false, NULL, false, true, NOW() - INTERVAL '3 hours', false, NULL, 'José Pérez Ruiz', NOW() - INTERVAL '3 hours', 1, NOW() - INTERVAL '3 hours', NOW()),
    (6, 23, NULL, 27, false, NULL, NULL, 2, NULL, 1, 0, 'Lamento el inconveniente José. Voy a revisar su caso inmediatamente.', NULL, false, NULL, false, true, NOW() - INTERVAL '2 hours', false, NULL, 'Agente1 FOH', NOW() - INTERVAL '2 hours', 2, NOW() - INTERVAL '2 hours', NOW()),

    -- Ticket 6: Devtech
    (7, 10, NULL, 7, false, NULL, NULL, 6, NULL, 0, 3, 'Tengo una consulta sobre el servicio', NULL, false, NULL, false, true, NOW() - INTERVAL '1 hour', false, NULL, 'Frank Giraldo', NOW() - INTERVAL '1 hour', 1, NOW() - INTERVAL '1 hour', NOW()),
    (8, 7, NULL, 10, false, NULL, NULL, 6, NULL, 1, 0, 'Hola Frank! En qué puedo ayudarte?', NULL, false, NULL, false, true, NOW() - INTERVAL '45 minutes', false, NULL, 'Augusto Sectorista1', NOW() - INTERVAL '45 minutes', 2, NOW() - INTERVAL '45 minutes', NOW());

SELECT setval('messages_id_seq', 8);

-- =====================================================
-- PASO 15: PROSPECTS
-- =====================================================

INSERT INTO prospects (id, manager_id, name, phone, client_id, status, upgraded_to_user, created_at, updated_at)
VALUES
    (1, 7, 'Prospecto Nuevo 1', '999800001', 2, 0, false, NOW(), NOW()),
    (2, 7, 'Prospecto Nuevo 2', '999800002', 2, 0, false, NOW() - INTERVAL '1 day', NOW()),
    (3, 23, 'Prospecto FOH 1', '999800003', 3, 0, false, NOW(), NOW()),
    (4, 23, NULL, '999800004', 3, 0, false, NOW(), NOW());

SELECT setval('prospects_id_seq', 4);

-- =====================================================
-- PASO 16: ALERTS
-- =====================================================

INSERT INTO alerts (id, user_id, alert_type, severity, title, body, read, url, message_id, sender_id, recipient_id, created_at, updated_at)
VALUES
    (1, 23, 0, 0, 'Nueva conversación', 'Tienes una nueva conversación pendiente', false, '/app/conversations/1', NULL, '26', '23', NOW(), NOW()),
    (2, 23, 0, 1, 'Respuesta pendiente', 'Cliente esperando respuesta hace 30 minutos', false, '/app/conversations/2', NULL, '27', '23', NOW() - INTERVAL '30 minutes', NOW()),
    (3, 22, 0, 1, 'Rendimiento de equipo', 'Tu equipo tiene 2 conversaciones sin responder', false, '/app/dashboard', NULL, NULL, NULL, NOW(), NOW()),
    (4, 7, 0, 0, 'Nueva asignación', 'Se te asignó un nuevo cliente', true, '/app/clients/10', NULL, NULL, NULL, NOW() - INTERVAL '1 day', NOW());

SELECT setval('alerts_id_seq', 4);

-- =====================================================
-- PASO 17: DEVICE INFOS
-- =====================================================

INSERT INTO device_infos (id, user_id, device_id, token, device_type, status, created_at, updated_at)
VALUES
    (1, 23, 'android-device-001', 'fcm-token-agente1-foh', 0, 0, NOW(), NOW()),
    (2, 24, 'ios-device-001', 'apns-token-agente2-foh', 1, 0, NOW(), NOW()),
    (3, 7, 'android-device-002', 'fcm-token-sectorista1', 0, 0, NOW(), NOW());

SELECT setval('device_infos_id_seq', 3);

-- =====================================================
-- PASO 18: KPIS
-- =====================================================

INSERT INTO kpis (id, client_id, user_id, kpi_type, value, data_hash, ticket_id, created_at, updated_at)
VALUES
    -- KPIs FOH hoy
    (1, 3, 23, 0, 2, '{}', NULL, NOW(), NOW()), -- new_client
    (2, 3, 23, 1, 2, '{}', 1, NOW(), NOW()), -- new_ticket
    (3, 3, 23, 3, 300, '{"ticket_id": 1}', 1, NOW(), NOW()), -- first_response_time
    (4, 3, 23, 4, 4, '{}', NULL, NOW(), NOW()), -- responded_to_client
    (5, 3, 23, 6, 6, '{}', NULL, NOW(), NOW()), -- sent_message
    (6, 3, 24, 5, 1, '{}', 4, NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 days'), -- closed_ticket
    (7, 3, 24, 9, 1, '{}', 4, NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 days'), -- closed_con_acuerdo
    (8, 3, 25, 10, 1, '{}', 5, NOW() - INTERVAL '5 days', NOW() - INTERVAL '5 days'), -- closed_sin_acuerdo
    -- KPIs Devtech
    (9, 2, 7, 1, 1, '{}', 6, NOW(), NOW()),
    (10, 2, 7, 5, 1, '{}', 7, NOW() - INTERVAL '1 day', NOW() - INTERVAL '1 day');

SELECT setval('kpis_id_seq', 10);

-- =====================================================
-- PASO 19: KPI COUNTERS
-- =====================================================

INSERT INTO kpi_counters (id, client_id, user_id, kpi_type, count, created_at, updated_at)
VALUES
    (1, 3, 23, 0, 50, NOW(), NOW()),
    (2, 3, 23, 1, 80, NOW(), NOW()),
    (3, 3, 23, 4, 500, NOW(), NOW()),
    (4, 3, 23, 5, 70, NOW(), NOW()),
    (5, 3, 24, 5, 65, NOW(), NOW()),
    (6, 3, 25, 5, 55, NOW(), NOW()),
    (7, 2, 7, 5, 100, NOW(), NOW());

SELECT setval('kpi_counters_id_seq', 7);

-- =====================================================
-- PASO 20: MESSAGE TEMPLATES
-- =====================================================

INSERT INTO message_templates (id, user_id, client_id, name, category, template_whatsapp_type, language_id, header_media_type, header_content, body_content, footer_content, tot_buttons, closes_ticket, template_whatsapp_status, visibility, status, created_at, updated_at)
VALUES
    (1, 20, 3, 'bienvenida_foh', 0, 0, 1, 1, 'Bienvenido a Financiera Oh', 'Hola {{1}}, gracias por ser parte de Financiera Oh. Estamos para ayudarte.', 'Equipo Financiera Oh', 0, false, 1, 0, 0, NOW(), NOW()),
    (2, 20, 3, 'recordatorio_pago', 7, 1, 1, 0, NULL, 'Estimado {{1}}, le recordamos que su pago de S/. {{2}} vence el {{3}}. Evite recargos.', 'Financiera Oh', 1, false, 1, 0, 0, NOW(), NOW()),
    (3, 20, 3, 'oferta_ampliacion', 1, 0, 1, 0, NULL, 'Felicidades {{1}}! Tiene pre-aprobada una ampliación de línea de S/. {{2}}. Contáctenos para más información.', NULL, 2, false, 1, 0, 0, NOW(), NOW()),
    (4, 4, 2, 'bienvenida_devtech', 0, 0, 1, 1, 'Bienvenido', 'Hola {{1}}, bienvenido a DevTech Perú.', NULL, 0, false, 1, 0, 0, NOW(), NOW());

SELECT setval('message_templates_id_seq', 4);

-- =====================================================
-- PASO 21: MESSAGE TEMPLATE PARAMS
-- =====================================================

INSERT INTO message_template_params (id, message_template_id, component, position, data_field, default_value, status, created_at, updated_at)
VALUES
    (1, 1, 1, 1, 'first_name', 'Cliente', 0, NOW(), NOW()),
    (2, 2, 1, 1, 'first_name', 'Cliente', 0, NOW(), NOW()),
    (3, 2, 1, 2, 'amount', '', 0, NOW(), NOW()),
    (4, 2, 1, 3, 'due_date', '', 0, NOW(), NOW()),
    (5, 3, 1, 1, 'first_name', 'Cliente', 0, NOW(), NOW()),
    (6, 3, 1, 2, 'credit_amount', '', 0, NOW(), NOW()),
    (7, 4, 1, 1, 'first_name', 'Cliente', 0, NOW(), NOW());

SELECT setval('message_template_params_id_seq', 7);

-- =====================================================
-- RESUMEN FINAL
-- =====================================================

COMMIT;

-- Mostrar resumen
SELECT '=============================================' AS info
UNION ALL SELECT 'MASTER SEED EJECUTADO EXITOSAMENTE'
UNION ALL SELECT '=============================================';

SELECT 'TABLA' AS tabla, 'REGISTROS' AS registros
UNION ALL SELECT 'countries', (SELECT COUNT(*)::text FROM countries)
UNION ALL SELECT 'languages', (SELECT COUNT(*)::text FROM languages)
UNION ALL SELECT 'settings', (SELECT COUNT(*)::text FROM settings)
UNION ALL SELECT 'clients', (SELECT COUNT(*)::text FROM clients)
UNION ALL SELECT 'client_structures', (SELECT COUNT(*)::text FROM client_structures)
UNION ALL SELECT 'client_settings', (SELECT COUNT(*)::text FROM client_settings)
UNION ALL SELECT 'users', (SELECT COUNT(*)::text FROM users)
UNION ALL SELECT 'bulk_messages', (SELECT COUNT(*)::text FROM bulk_messages)
UNION ALL SELECT 'canned_messages', (SELECT COUNT(*)::text FROM canned_messages)
UNION ALL SELECT 'crm_info_settings', (SELECT COUNT(*)::text FROM crm_info_settings)
UNION ALL SELECT 'crm_infos', (SELECT COUNT(*)::text FROM crm_infos)
UNION ALL SELECT 'tickets', (SELECT COUNT(*)::text FROM tickets)
UNION ALL SELECT 'messages', (SELECT COUNT(*)::text FROM messages)
UNION ALL SELECT 'prospects', (SELECT COUNT(*)::text FROM prospects)
UNION ALL SELECT 'alerts', (SELECT COUNT(*)::text FROM alerts)
UNION ALL SELECT 'device_infos', (SELECT COUNT(*)::text FROM device_infos)
UNION ALL SELECT 'kpis', (SELECT COUNT(*)::text FROM kpis)
UNION ALL SELECT 'kpi_counters', (SELECT COUNT(*)::text FROM kpi_counters)
UNION ALL SELECT 'message_templates', (SELECT COUNT(*)::text FROM message_templates);

SELECT '=============================================' AS info
UNION ALL SELECT 'USUARIOS DE PRUEBA'
UNION ALL SELECT 'Password: 12345678 (o test123 en dev)'
UNION ALL SELECT 'OTP: 123456'
UNION ALL SELECT '=============================================';

SELECT
    email,
    first_name || ' ' || last_name AS nombre,
    CASE role
        WHEN 0 THEN 'standard'
        WHEN 1 THEN 'super_admin'
        WHEN 2 THEN 'admin'
        WHEN 6 THEN 'supervisor'
        WHEN 7 THEN 'agent'
        WHEN 9 THEN 'whatsapp_business'
    END AS rol,
    (SELECT name FROM clients WHERE id = users.client_id) AS cliente
FROM users
WHERE role IN (1, 2, 6, 7)
ORDER BY client_id, role, id;
