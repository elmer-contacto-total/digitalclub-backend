package com.digitalgroup.holape.api.v1;

import com.digitalgroup.holape.api.v1.dto.message.CapturedMessagesRequest;
import com.digitalgroup.holape.api.v1.dto.message.DeletedMessagesRequest;
import com.digitalgroup.holape.domain.message.service.CapturedMessageService;
import com.digitalgroup.holape.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Recepción de las conversaciones capturadas por la aplicación de escritorio.
 *
 * Recibe un lote por conversación. Es idempotente: reenviar el mismo lote no
 * duplica nada, porque cada mensaje se identifica por el id que le asigna
 * WhatsApp. Eso permite que la aplicación reintente sin miedo cuando la red o
 * el servidor no estaban disponibles.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/messages")
@RequiredArgsConstructor
public class CapturedMessageController {

    private final CapturedMessageService capturedMessageService;

    @PostMapping("/captured")
    public ResponseEntity<Map<String, Object>> capturar(
            @AuthenticationPrincipal CustomUserDetails usuarioActual,
            @Valid @RequestBody CapturedMessagesRequest request) {

        // El lote se registra a nombre de quien lo envía. Un asesor no puede
        // atribuir conversaciones a otro ni a otra organización.
        if (usuarioActual != null && !usuarioActual.isAdmin()) {
            boolean mismoAsesor = usuarioActual.getId().equals(request.getAgentId());
            boolean mismaOrganizacion = usuarioActual.getClientId() != null
                    && usuarioActual.getClientId().equals(request.getClientId());
            if (!mismoAsesor || !mismaOrganizacion) {
                log.warn("[CAPTURA] rechazado: usuario {} (organizacion {}) intento registrar como asesor {} de la organizacion {}",
                        usuarioActual.getId(), usuarioActual.getClientId(),
                        request.getAgentId(), request.getClientId());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "error", "El lote no corresponde al asesor autenticado"));
            }
        }

        log.info("[CAPTURA] lote recibido conversacion={} asesor={} mensajes={}",
                request.getConversationId(), request.getAgentId(),
                request.getMessages() != null ? request.getMessages().size() : 0);

        return ResponseEntity.ok(capturedMessageService.registrar(request));
    }

    /**
     * Aviso de mensajes que desaparecieron de la conversación.
     *
     * El mensaje registrado se conserva y queda marcado como eliminado, con la
     * fecha y hora de la detección. Es idempotente: repetir el aviso no corre la
     * marca original.
     */
    @PostMapping("/captured/deleted")
    public ResponseEntity<Map<String, Object>> eliminados(
            @AuthenticationPrincipal CustomUserDetails usuarioActual,
            @Valid @RequestBody DeletedMessagesRequest request) {

        // La marca solo alcanza a las conversaciones de la organización del
        // asesor: nadie puede tocar el registro de otra.
        Long organizacion = usuarioActual != null ? usuarioActual.getClientId() : null;
        if (organizacion == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "El usuario no pertenece a ninguna organización"));
        }

        log.info("[CAPTURA] aviso de eliminacion, mensajes={}",
                request.getWhatsappMessageIds() != null ? request.getWhatsappMessageIds().size() : 0);

        return ResponseEntity.ok(capturedMessageService.registrarEliminados(request, organizacion));
    }
}
