-- Quien envio cada archivo adjunto.
--
-- La tabla no guardaba ningun dato del remitente, de modo que en la ficha todo
-- lo multimedia figuraba del lado del cliente: tambien lo que habia enviado el
-- asesor. Se registra igual que en los mensajes: INCOMING lo escribio el
-- cliente, OUTGOING el asesor.
--
-- Nulo en los adjuntos ya capturados, que no traen esa informacion.
ALTER TABLE captured_media ADD COLUMN IF NOT EXISTS direction VARCHAR(10);
