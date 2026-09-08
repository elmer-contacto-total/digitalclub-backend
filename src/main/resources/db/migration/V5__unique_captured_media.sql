-- Unicidad de los adjuntos capturados, garantizada por la base.
--
-- Hasta ahora la comprobacion era del programa: consultar si ya existe y
-- despues escribir. Entre esos dos pasos hay una rendija, y con varias sesiones
-- de WhatsApp sobre la misma cuenta --Business admite hasta cuatro-- todas
-- pueden consultar antes de que ninguna haya escrito y duplicar el adjunto.
--
-- El par es el mismo criterio que ya usaba el programa: mismo archivo Y mismo
-- mensaje. Un adjunto reenviado a otra conversacion tiene otro identificador y
-- se sigue guardando, que es lo correcto: son dos hechos distintos.
--
-- Los adjuntos sin identificador no se ven afectados: en PostgreSQL los nulos
-- no chocan entre si.
CREATE UNIQUE INDEX IF NOT EXISTS index_captured_media_on_hash_and_message
    ON captured_media (sha256_hash, whatsapp_message_id)
    WHERE whatsapp_message_id IS NOT NULL;
