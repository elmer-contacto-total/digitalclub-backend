package com.digitalgroup.holape.domain.message.service;

import com.digitalgroup.holape.api.v1.dto.message.CapturedMessagesRequest;
import com.digitalgroup.holape.api.v1.dto.message.DeletedMessagesRequest;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.common.enums.MessageDirection;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.domain.prospect.entity.Prospect;
import com.digitalgroup.holape.domain.prospect.repository.ProspectRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registro de las conversaciones capturadas por la aplicación de escritorio.
 *
 * Dos reglas gobiernan este servicio:
 *
 * 1. Unicidad por identificador de WhatsApp. Un mensaje ya registrado no se
 *    vuelve a insertar, sin importar cuántas veces se relea la conversación.
 *
 * 2. Identificación del cliente a partir de la ficha de contacto. Si el
 *    identificador recibido es un número que corresponde a un cliente de la
 *    cartera, el mensaje va a su ficha. Si no lo es --porque el cliente ocultó
 *    su número y WhatsApp entregó un nombre de usuario-- el mensaje se conserva
 *    igual, asociado a un prospecto. No se pierde: queda sin vincular.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CapturedMessageService {

    /**
     * Usuario del sistema con el que se rellena el extremo de la conversacion que
     * corresponde a un prospecto. Las columnas sender_id y recipient_id no admiten
     * nulos y apuntan a users, de modo que el prospecto no cabe en ellas: vive en
     * prospect_sender_id / prospect_recipient_id, que es por donde la aplicacion
     * recupera esas conversaciones. Es la misma convencion que ya usa el sistema
     * para los mensajes que entran por la integracion de WhatsApp.
     */
    private static final long USUARIO_SISTEMA = 1L;

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ProspectRepository prospectRepository;

    @Transactional
    public Map<String, Object> registrar(CapturedMessagesRequest request) {
        User agente = userRepository.findById(request.getAgentId()).orElse(null);
        if (agente == null) {
            log.warn("[CAPTURA] asesor {} no existe, lote descartado", request.getAgentId());
            return resultado(0, 0, request.getMessages().size(), null);
        }

        User cliente = resolverCliente(request);
        Prospect prospecto = (cliente == null) ? resolverProspecto(request, agente) : null;

        int guardados = 0, repetidos = 0, descartados = 0;

        for (CapturedMessagesRequest.CapturedMessage m : request.getMessages()) {
            if (m.getWhatsappMessageId() == null || m.getWhatsappMessageId().isBlank()) {
                descartados++;
                continue;
            }
            // Nunca registramos un mensaje sin contenido: si el texto todavía no
            // terminó de dibujarse, la aplicación lo reintenta más tarde.
            if (m.getContent() == null || m.getContent().isBlank()) {
                descartados++;
                continue;
            }
            if (messageRepository.existsByWhatsappMessageId(m.getWhatsappMessageId())) {
                repetidos++;
                continue;
            }

            try {
                messageRepository.save(construir(m, request, agente, cliente, prospecto));
                guardados++;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Otro equipo del mismo asesor insertó el mismo mensaje entre la
                // comprobación y el guardado. El índice único hace su trabajo.
                repetidos++;
            }
        }

        log.info("[CAPTURA] conversacion={} asesor={} guardados={} repetidos={} descartados={} vinculado={}",
                request.getConversationId(), request.getAgentId(), guardados, repetidos, descartados,
                cliente != null ? ("cliente:" + cliente.getId()) : ("prospecto:" + (prospecto != null ? prospecto.getId() : "-")));

        return resultado(guardados, repetidos, descartados, cliente);
    }

    /**
     * Deja constancia de los mensajes que desaparecieron de la conversación.
     *
     * El contenido registrado se conserva intacto: lo único que cambia es la marca
     * de eliminación, y solo la primera vez que se recibe el aviso. Un mensaje que
     * nunca llegó a registrarse no deja rastro, porque no hay a qué asociarlo.
     */
    @Transactional
    public Map<String, Object> registrarEliminados(DeletedMessagesRequest request, Long clientId) {
        LocalDateTime detectadoEn = request.getDetectedAt() != null
                ? request.getDetectedAt()
                : LocalDateTime.now();

        int marcados = 0, sinRegistro = 0;

        for (String id : request.getWhatsappMessageIds()) {
            if (id == null || id.isBlank()) {
                continue;
            }
            if (messageRepository.marcarEliminado(id, detectadoEn, clientId) > 0) {
                marcados++;
            } else {
                // O el mensaje no se capturó nunca, o ya estaba marcado, o no
                // pertenece a esta organización. En ninguno de los casos hay algo
                // que corregir.
                sinRegistro++;
            }
        }

        log.info("[CAPTURA] eliminaciones recibidas={} marcados={} sin_registro={}",
                request.getWhatsappMessageIds().size(), marcados, sinRegistro);

        Map<String, Object> r = new HashMap<>();
        r.put("marcados", marcados);
        r.put("sinRegistro", sinRegistro);
        return r;
    }

    /**
     * Busca al cliente en la cartera de la organización del asesor. Acepta el
     * número con o sin prefijo de país, porque la ficha y WhatsApp no siempre
     * coinciden en el formato.
     */
    private User resolverCliente(CapturedMessagesRequest request) {
        if (request.getClientUserId() != null) {
            Optional<User> porId = userRepository.findById(request.getClientUserId());
            if (porId.isPresent()) {
                return porId.get();
            }
        }

        String soloDigitos = request.getConversationId().replaceAll("[^0-9]", "");
        // Un identificador que no es numérico --un nombre de usuario-- no puede
        // corresponder a ninguna ficha: no tiene sentido consultarla.
        if (soloDigitos.length() < 9) {
            return null;
        }

        Optional<User> encontrado =
                userRepository.findByPhoneAndClientId(soloDigitos, request.getClientId());

        if (encontrado.isEmpty()) {
            String ultimos9 = soloDigitos.substring(soloDigitos.length() - 9);
            encontrado = userRepository.findByPhoneAndClientId(ultimos9, request.getClientId());
            if (encontrado.isEmpty()) {
                encontrado = userRepository.findByPhoneAndClientId("51" + ultimos9, request.getClientId());
            }
        }
        return encontrado.orElse(null);
    }

    /**
     * Cuando no hay cliente, la conversación se apoya en un prospecto con el mismo
     * identificador. Así los mensajes quedan consultables aunque no estén en una
     * ficha, y los archivos adjuntos de esa conversación caen en el mismo lugar.
     */
    private Prospect resolverProspecto(CapturedMessagesRequest request, User agente) {
        return prospectRepository
                .findByPhoneAndClientId(request.getConversationId(), request.getClientId())
                .orElseGet(() -> {
                    Prospect nuevo = new Prospect();
                    nuevo.setPhone(request.getConversationId());
                    nuevo.setName(request.getConversationName());
                    nuevo.setClientId(request.getClientId());
                    nuevo.setManager(agente);
                    Prospect creado = prospectRepository.save(nuevo);
                    log.info("[CAPTURA] prospecto {} creado para la conversacion {}",
                            creado.getId(), request.getConversationId());
                    return creado;
                });
    }

    private Message construir(CapturedMessagesRequest.CapturedMessage m,
                              CapturedMessagesRequest request,
                              User agente, User cliente, Prospect prospecto) {

        MessageDirection direccion = "OUTGOING".equalsIgnoreCase(m.getDirection())
                ? MessageDirection.OUTGOING
                : MessageDirection.INCOMING;

        Message mensaje = new Message();
        mensaje.setWhatsappMessageId(m.getWhatsappMessageId());
        mensaje.setContent(m.getContent());
        mensaje.setDirection(direccion);
        mensaje.setSentAt(m.getSentAt() != null ? m.getSentAt() : LocalDateTime.now());
        mensaje.setNewSenderPhone(request.getConversationId());
        mensaje.setProcessed(false);

        if (cliente != null) {
            mensaje.setIsProspect(false);
            if (direccion == MessageDirection.OUTGOING) {
                mensaje.setSender(agente);
                mensaje.setRecipient(cliente);
            } else {
                mensaje.setSender(cliente);
                mensaje.setRecipient(agente);
            }
        } else {
            mensaje.setIsProspect(true);
            User sistema = userRepository.getReferenceById(USUARIO_SISTEMA);
            Long idProspecto = prospecto != null ? prospecto.getId() : null;
            // El asesor queda en el extremo conocido de la conversación y el
            // prospecto en la columna que le corresponde.
            if (direccion == MessageDirection.OUTGOING) {
                mensaje.setSender(agente);
                mensaje.setRecipient(sistema);
                mensaje.setProspectRecipientId(idProspecto);
            } else {
                mensaje.setSender(sistema);
                mensaje.setRecipient(agente);
                mensaje.setProspectSenderId(idProspecto);
            }
        }
        return mensaje;
    }

    private Map<String, Object> resultado(int guardados, int repetidos, int descartados, User cliente) {
        Map<String, Object> r = new HashMap<>();
        r.put("guardados", guardados);
        r.put("repetidos", repetidos);
        r.put("descartados", descartados);
        r.put("clienteVinculado", cliente != null ? cliente.getId() : null);
        return r;
    }
}
