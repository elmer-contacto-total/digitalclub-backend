-- =====================================================
-- Seed Data - Datos iniciales para desarrollo
-- Ejecutar despues de 02_foreign_keys.sql
-- =====================================================

-- =====================================================
-- PAISES
-- =====================================================

INSERT INTO countries (name, iso_code, flag_url, default_locale, default_phone_country_code, default_currency)
VALUES
    ('Peru', 'PE', 'https://flagcdn.com/pe.svg', 'es-PE', '51', 'PEN'),
    ('Chile', 'CL', 'https://flagcdn.com/cl.svg', 'es-CL', '56', 'CLP'),
    ('Colombia', 'CO', 'https://flagcdn.com/co.svg', 'es-CO', '57', 'COP'),
    ('Mexico', 'MX', 'https://flagcdn.com/mx.svg', 'es-MX', '52', 'MXN'),
    ('Argentina', 'AR', 'https://flagcdn.com/ar.svg', 'es-AR', '54', 'ARS'),
    ('Estados Unidos', 'US', 'https://flagcdn.com/us.svg', 'en-US', '1', 'USD'),
    ('Espana', 'ES', 'https://flagcdn.com/es.svg', 'es-ES', '34', 'EUR')
ON CONFLICT DO NOTHING;

-- =====================================================
-- IDIOMAS
-- =====================================================

INSERT INTO languages (name, language_code, status, display_order)
VALUES
    ('Espanol', 'es', 0, 1),
    ('Espanol (Mexico)', 'es_MX', 0, 2),
    ('Espanol (Argentina)', 'es_AR', 0, 3),
    ('Ingles', 'en', 0, 4),
    ('Ingles (US)', 'en_US', 0, 5),
    ('Portugues (Brasil)', 'pt_BR', 0, 6)
ON CONFLICT DO NOTHING;

-- =====================================================
-- CLIENTE DE PRUEBA
-- =====================================================

INSERT INTO clients (id, name, company_name, doc_type, doc_number, country_id, client_type, status)
VALUES (1, 'Cliente Demo', 'Empresa Demo S.A.C.', 1, '20123456789', 1, 0, 0)
ON CONFLICT (id) DO NOTHING;

-- Client Structure para el cliente demo
INSERT INTO client_structures (client_id, manager_level_4, exists_manager_level_4, agent, exists_agent)
VALUES (1, 'Supervisor', true, 'Sectorista', true)
ON CONFLICT DO NOTHING;

-- Client Settings basicos
INSERT INTO client_settings (client_id, name, localized_name, internal, data_type, integer_value, status)
VALUES
    (1, 'time_for_ticket_autoclose', 'Tiempo para auto-cierre de ticket (horas)', false, 1, 24, 0),
    (1, 'alert_time_not_responded_conversation', 'Tiempo alerta sin respuesta (minutos)', false, 1, 30, 0)
ON CONFLICT DO NOTHING;

INSERT INTO client_settings (client_id, name, localized_name, internal, data_type, string_value, status)
VALUES
    (1, 'templates_language', 'Idioma de templates', false, 0, 'es', 0)
ON CONFLICT DO NOTHING;

INSERT INTO client_settings (client_id, name, localized_name, internal, data_type, boolean_value, status)
VALUES
    (1, 'create_user_from_prospect', 'Crear usuario desde prospecto', false, 4, true, 0)
ON CONFLICT DO NOTHING;

-- =====================================================
-- USUARIOS DE PRUEBA
-- Password: "password123" (bcrypt hash)
-- $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n7sCl3gEXQ.F9HnYBvXku
-- =====================================================

-- Super Admin (role = 1)
INSERT INTO users (id, email, encrypted_password, first_name, last_name, phone, client_id, country_id, role, status, time_zone, uuid_token)
VALUES (1, 'admin@holape.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n7sCl3gEXQ.F9HnYBvXku',
        'Super', 'Admin', '999999999', 1, 1, 1, 0, 'America/Lima', 'uuid-super-admin-001')
ON CONFLICT (id) DO NOTHING;

-- Admin (role = 2)
INSERT INTO users (id, email, encrypted_password, first_name, last_name, phone, client_id, country_id, role, status, manager_id, time_zone, uuid_token)
VALUES (2, 'administrador@holape.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n7sCl3gEXQ.F9HnYBvXku',
        'Admin', 'Sistema', '999999998', 1, 1, 2, 0, 1, 'America/Lima', 'uuid-admin-002')
ON CONFLICT (id) DO NOTHING;

-- Manager Level 4 / Supervisor (role = 6)
INSERT INTO users (id, email, encrypted_password, first_name, last_name, phone, client_id, country_id, role, status, manager_id, time_zone, uuid_token)
VALUES (3, 'supervisor@holape.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n7sCl3gEXQ.F9HnYBvXku',
        'Supervisor', 'Demo', '999999997', 1, 1, 6, 0, 2, 'America/Lima', 'uuid-supervisor-003')
ON CONFLICT (id) DO NOTHING;

-- Agent / Sectorista (role = 7)
INSERT INTO users (id, email, encrypted_password, first_name, last_name, phone, client_id, country_id, role, status, manager_id, time_zone, uuid_token)
VALUES (4, 'agente@holape.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n7sCl3gEXQ.F9HnYBvXku',
        'Agente', 'Demo', '999999996', 1, 1, 7, 0, 3, 'America/Lima', 'uuid-agente-004')
ON CONFLICT (id) DO NOTHING;

-- Standard User / Cliente (role = 0)
INSERT INTO users (id, email, encrypted_password, first_name, last_name, phone, client_id, country_id, role, status, manager_id, time_zone, uuid_token)
VALUES (5, 'cliente@demo.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n7sCl3gEXQ.F9HnYBvXku',
        'Cliente', 'Ejemplo', '999999995', 1, 1, 0, 0, 4, 'America/Lima', 'uuid-cliente-005')
ON CONFLICT (id) DO NOTHING;

-- Reset sequence
SELECT setval('users_id_seq', (SELECT MAX(id) FROM users));
SELECT setval('clients_id_seq', (SELECT MAX(id) FROM clients));

-- =====================================================
-- SETTINGS GLOBALES
-- =====================================================

INSERT INTO settings (name, localized_name, internal, data_type, string_value, status)
VALUES
    ('app_version', 'Version de la aplicacion', true, 0, '2.0.0', 0),
    ('maintenance_mode', 'Modo mantenimiento', true, 4, NULL, 0)
ON CONFLICT DO NOTHING;

INSERT INTO settings (name, localized_name, internal, data_type, boolean_value, status)
VALUES
    ('maintenance_mode', 'Modo mantenimiento', true, 4, false, 0)
ON CONFLICT DO NOTHING;

-- =====================================================
-- RESUMEN DE USUARIOS CREADOS
-- =====================================================

SELECT 'Datos semilla insertados exitosamente' AS resultado;

SELECT
    '=== USUARIOS DE PRUEBA ===' AS info
UNION ALL
SELECT 'Email: admin@holape.com | Password: password123 | Rol: Super Admin'
UNION ALL
SELECT 'Email: administrador@holape.com | Password: password123 | Rol: Admin'
UNION ALL
SELECT 'Email: supervisor@holape.com | Password: password123 | Rol: Supervisor'
UNION ALL
SELECT 'Email: agente@holape.com | Password: password123 | Rol: Agente'
UNION ALL
SELECT 'Email: cliente@demo.com | Password: password123 | Rol: Cliente';
