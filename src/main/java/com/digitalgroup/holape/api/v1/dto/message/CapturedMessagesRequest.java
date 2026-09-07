package com.digitalgroup.holape.api.v1.dto.message;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Lote de mensajes capturados por la aplicación de escritorio desde WhatsApp Web.
 *
 * El lote pertenece a UNA conversación. La identificación del cliente se hace con
 * {@code conversationId}, que es el número que figura en la ficha de contacto o,
 * cuando WhatsApp no lo expone, el identificador que muestre en su lugar.
 */
@Data
public class CapturedMessagesRequest {

    /**
     * Identificador de la conversación tomado de la ficha de contacto: normalmente
     * el número de teléfono. Cuando el cliente oculta su número, WhatsApp muestra
     * un nombre de usuario y es eso lo que llega aquí.
     */
    @NotNull
    private String conversationId;

    /** Cliente ya resuelto por el escritorio, si lo tenía. Opcional. */
    private Long clientUserId;

    /** Asesor que capturó la conversación. */
    @NotNull
    private Long agentId;

    /** Organización a la que pertenece el asesor. */
    @NotNull
    private Long clientId;

    /** Nombre visible de la conversación, solo informativo. */
    private String conversationName;

    @NotEmpty
    private List<CapturedMessage> messages;

    @Data
    public static class CapturedMessage {

        /** Identificador que WhatsApp asigna al mensaje. Criterio de unicidad. */
        @NotNull
        private String whatsappMessageId;

        private String content;

        /** INCOMING = lo escribió el cliente. OUTGOING = lo escribió el asesor. */
        @NotNull
        private String direction;

        /** Fecha y hora que muestra WhatsApp, no la del reloj del equipo. */
        private LocalDateTime sentAt;
    }
}
