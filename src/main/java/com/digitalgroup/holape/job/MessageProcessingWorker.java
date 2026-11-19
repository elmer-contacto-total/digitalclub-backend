package com.digitalgroup.holape.job;

import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.domain.message.service.MessageService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Worker de procesamiento de mensajes crudos (FORWARD).
 *
 * CONTEXTO: el interceptor NodeJS (digitalgroup-message-intake-master) inserta los mensajes de
 * WhatsApp DIRECTO en la tabla `messages` con processed=false, SIN ticket ni KPIs. Hasta el
 * cutover a Spring (8-sep-2026) ese procesamiento lo hacía un worker de Rails; al pasar a Spring
 * quedó sin dueño (el @Scheduled de ProcessMessageJob está deshabilitado desde feb-2026
 * "delegated to Rails"). Resultado: desde sep todos los mensajes quedan crudos → ticket_id NULL
 * y los 6 KPIs de engagement muertos.
 *
 * Este worker reactiva ese procesamiento: toma los mensajes nuevos sin procesar y, por cada uno,
 * asigna/crea ticket + escribe KPIs + marca processed=true (MessageService.processRawMessage).
 *
 * IMPORTANTE: solo procesa mensajes creados DESPUÉS del arranque del worker (cutoff). El backlog
 * histórico previo (~12k, sep→hoy) NO se toca acá a propósito: procesarlo ahora fecharía sus KPIs
 * con la fecha de hoy y distorsionaría el histórico. Ese backlog se rellena por separado, con
 * fecha real (sent_at) y dry-run. Este worker solo "detiene la hemorragia" de aquí en adelante.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageProcessingWorker {

    private final MessageRepository messageRepository;
    private final MessageService messageService;

    private static final int BATCH_SIZE = 100;

    /** Solo se procesan mensajes creados después de este instante (arranque del worker). */
    private volatile LocalDateTime cutoff;

    @PostConstruct
    void init() {
        this.cutoff = LocalDateTime.now();
        log.info("MessageProcessingWorker ACTIVO. Procesa mensajes nuevos (processed=false) creados después de {}. "
                + "El backlog histórico previo se rellena por separado.", cutoff);
    }

    /**
     * Cada 30s (fixedDelay = no solapa: espera a terminar antes del siguiente ciclo).
     * Procesa en lotes de {@link #BATCH_SIZE}; errores por mensaje se registran y no frenan el resto.
     */
    @Scheduled(fixedDelay = 30000)
    public void processNewMessages() {
        try {
            List<Message> batch = messageRepository.findUnprocessedMessagesAfter(
                    cutoff, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                return;
            }
            log.info("Procesando {} mensaje(s) nuevo(s) sin procesar", batch.size());
            int ok = 0, err = 0;
            for (Message m : batch) {
                try {
                    messageService.processRawMessage(m.getId());
                    ok++;
                } catch (Exception e) {
                    err++;
                    log.error("Error procesando mensaje {}: {}", m.getId(), e.getMessage(), e);
                }
            }
            log.info("Lote procesado: ok={}, errores={}", ok, err);
        } catch (Exception e) {
            log.error("Error en el ciclo del worker de procesamiento: {}", e.getMessage(), e);
        }
    }
}
