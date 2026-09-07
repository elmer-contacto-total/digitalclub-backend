-- Constancia de eliminación de un mensaje capturado.
--
-- Cuando un mensaje desaparece de la conversación de WhatsApp, el contenido ya
-- registrado se conserva y solo se deja la marca del momento en que se detectó
-- la eliminación. Nulo significa que el mensaje sigue a la vista.
--
-- La columna es opcional para no afectar a los mensajes históricos.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS index_messages_on_deleted_at
    ON messages (deleted_at)
    WHERE deleted_at IS NOT NULL;
