package com.digitalgroup.holape.api.v1.dto.message;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Aviso de que uno o más mensajes ya capturados desaparecieron de la conversación.
 *
 * El contenido no se borra. Solo se deja constancia del momento en que se detectó
 * la eliminación, de modo que la auditoría conserve tanto el mensaje como el hecho
 * de que dejó de estar a la vista.
 */
@Data
public class DeletedMessagesRequest {

    /** Identificadores que WhatsApp asignó a los mensajes que desaparecieron. */
    @NotEmpty
    private List<String> whatsappMessageIds;

    /**
     * Momento en que la aplicación de escritorio detectó la desaparición. Si no
     * llega, se usa la hora del servidor.
     */
    private LocalDateTime detectedAt;
}
