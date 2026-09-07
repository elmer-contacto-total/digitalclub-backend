-- Identificador que WhatsApp asigna a cada mensaje (el "data-id" del DOM).
-- Se usa como criterio de unicidad al capturar desde la aplicacion de escritorio:
-- un mismo mensaje leido varias veces --al abrir la conversacion, al desplazarse,
-- o desde dos equipos del mismo asesor-- se registra una sola vez.
--
-- Reemplaza al criterio anterior de comparar contenido + hora aproximada, que
-- descartaba por error mensajes legitimos cuando el cliente enviaba textos
-- identicos seguidos.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS whatsapp_message_id VARCHAR(120);

-- Indice unico PARCIAL: solo aplica a las filas que traen identificador.
-- Las ~580.000 filas historicas lo tienen nulo y quedan fuera de la restriccion,
-- por eso la migracion no falla sobre datos existentes.
CREATE UNIQUE INDEX IF NOT EXISTS index_messages_on_whatsapp_message_id
    ON messages (whatsapp_message_id)
    WHERE whatsapp_message_id IS NOT NULL;
