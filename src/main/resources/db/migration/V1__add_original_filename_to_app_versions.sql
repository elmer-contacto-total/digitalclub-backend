-- Almacena el nombre original del archivo subido, para usarlo como nombre de descarga
-- en lugar del nombre generado con la versión.
ALTER TABLE app_versions ADD COLUMN IF NOT EXISTS original_filename VARCHAR(255);
