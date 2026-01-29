package com.digitalgroup.holape.domain.alert.service;

import com.digitalgroup.holape.domain.alert.entity.Alert;
import com.digitalgroup.holape.domain.alert.repository.AlertRepository;
import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.common.enums.AlertSeverity;
import com.digitalgroup.holape.domain.common.enums.AlertType;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import com.digitalgroup.holape.websocket.WebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Alert Service
 * Handles alerts for require response, escalations, etc.
 *
 * PARIDAD RAILS: Alert entity tiene:
 * - body (no message)
 * - read (no acknowledged)
 * - url (almacena referencia a ticket como "/tickets/{id}")
 * - messageId como String
 * - No tiene: client, ticket, acknowledgedAt, acknowledgedBy, dataHash
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRepository alertRepository;
    private final UserRepository userRepository;
    private final WebSocketService webSocketService;

    public Alert findById(Long id) {
        return alertRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Alert", id));
    }

    public Page<Alert> findByClient(Long clientId, boolean acknowledged, Pageable pageable) {
        // PARIDAD: acknowledged -> read
        return alertRepository.findByClientIdAndAcknowledged(clientId, acknowledged, pageable);
    }

    public Page<Alert> findByClientAndType(Long clientId, AlertType type,
                                           boolean acknowledged, Pageable pageable) {
        return alertRepository.findByClientIdAndTypeAndAcknowledged(
                clientId, type, acknowledged, pageable);
    }

    public Page<Alert> findByUser(Long userId, boolean acknowledged, Pageable pageable) {
        // PARIDAD: acknowledged -> read
        return alertRepository.findByUserIdAndAcknowledged(userId, acknowledged, pageable);
    }

    public List<Alert> findByTicket(Long ticketId) {
        return alertRepository.findByTicketId(ticketId);
    }

    public long countUnacknowledged(Long clientId) {
        // PARIDAD: acknowledged -> read
        return alertRepository.countByClientIdAndAcknowledged(clientId, false);
    }

    /**
     * Create alert
     * PARIDAD RAILS: Alert entity tiene body en lugar de message, no tiene ticket directo
     * ticket_id se almacena como URL o en campos auxiliares
     */
    @Transactional
    public Alert createAlert(Long clientId, AlertType type, AlertSeverity severity,
                             String title, String message, Ticket ticket, User user) {
        Alert alert = new Alert();
        alert.setType(type);
        alert.setSeverity(severity);
        alert.setTitle(title);
        alert.setBody(message);  // PARIDAD: body en lugar de message
        alert.setUser(user);
        alert.setRead(false);  // PARIDAD: read en lugar de acknowledged

        // PARIDAD: ticket no existe como relación, almacenar ID como URL
        if (ticket != null) {
            alert.setUrl("/tickets/" + ticket.getId());
        }

        alert = alertRepository.save(alert);

        // Broadcast alert via WebSocket (equivalent to Rails after_create_commit { broadcast_alert })
        broadcastAlert(alert);

        log.info("Created alert {} of type {} for ticket {}",
                alert.getId(), type, ticket != null ? ticket.getId() : "N/A");

        return alert;
    }

    /**
     * Broadcast alert via WebSocket
     * Equivalent to Rails: after_create_commit { broadcast_alert }
     */
    private void broadcastAlert(Alert alert) {
        if (alert.getUser() != null) {
            long unreadCount = alertRepository.countByUserIdAndReadFalse(alert.getUser().getId());
            // PARIDAD RAILS: body en lugar de message
            webSocketService.sendAlertToUser(
                    alert.getUser().getId(),
                    alert.getAlertType().name(),
                    alert.getBody() != null ? alert.getBody() : alert.getTitle()
            );
            log.debug("Broadcasted alert {} to user {}, unread count: {}",
                    alert.getId(), alert.getUser().getId(), unreadCount);
        }
    }

    @Transactional
    public Alert createRequireResponseAlert(Ticket ticket) {
        String title = "Respuesta requerida";
        String message = String.format("El ticket #%d requiere respuesta", ticket.getId());

        return createAlert(
                ticket.getClient().getId(),
                AlertType.REQUIRE_RESPONSE,
                AlertSeverity.WARNING,
                title,
                message,
                ticket,
                ticket.getAgent()
        );
    }

    @Transactional
    public Alert createEscalationAlert(Ticket ticket, User escalatedTo) {
        String title = "Ticket escalado";
        String message = String.format("El ticket #%d ha sido escalado", ticket.getId());

        return createAlert(
                ticket.getClient().getId(),
                AlertType.ESCALATION,
                AlertSeverity.HIGH,
                title,
                message,
                ticket,
                escalatedTo
        );
    }

    /**
     * Acknowledge alert
     * PARIDAD RAILS: read en lugar de acknowledged, no hay acknowledgedAt ni acknowledgedBy
     */
    @Transactional
    public Alert acknowledgeAlert(Long alertId, Long acknowledgedById) {
        Alert alert = findById(alertId);
        alert.markAsRead();  // PARIDAD: usa read en lugar de acknowledged
        return alertRepository.save(alert);
    }

    /**
     * Acknowledge multiple alerts
     * PARIDAD RAILS: read en lugar de acknowledged
     */
    @Transactional
    public int acknowledgeAlerts(List<Long> alertIds, Long acknowledgedById) {
        int count = 0;
        for (Long alertId : alertIds) {
            try {
                Alert alert = alertRepository.findById(alertId).orElse(null);
                if (alert != null && !Boolean.TRUE.equals(alert.getRead())) {
                    alert.markAsRead();
                    alertRepository.save(alert);
                    count++;
                }
            } catch (Exception e) {
                log.warn("Failed to acknowledge alert {}", alertId, e);
            }
        }

        return count;
    }

    /**
     * Acknowledge all alerts for a ticket
     * PARIDAD RAILS: read en lugar de acknowledged
     */
    @Transactional
    public void acknowledgeByTicket(Long ticketId) {
        List<Alert> alerts = alertRepository.findByTicketIdAndAcknowledged(ticketId, false);

        for (Alert alert : alerts) {
            alert.markAsRead();
        }

        alertRepository.saveAll(alerts);

        log.info("Acknowledged {} alerts for ticket {}", alerts.size(), ticketId);
    }

    /**
     * Create require response alert for a message (when no ticket exists)
     * Used by RequireResponseAlertJob for messages without tickets
     * PARIDAD RAILS: usa campos correctos de Alert entity
     */
    @Transactional
    public Alert createRequireResponseAlertForMessage(Message message, User sender, User recipient, int delayMinutes) {
        String title = "Respuesta requerida";
        String messageText = String.format(
                "Mensaje de %s requiere respuesta (esperando %d minutos)",
                sender.getFullName(), delayMinutes
        );

        Alert alert = new Alert();
        alert.setAlertType(AlertType.REQUIRE_RESPONSE);
        alert.setSeverity(AlertSeverity.WARNING);
        alert.setTitle(title);
        alert.setBody(messageText);  // PARIDAD: body en lugar de message
        alert.setUser(recipient);
        alert.setMessageId(message.getId() != null ? message.getId().toString() : null);
        alert.setSenderId(sender.getId() != null ? sender.getId().toString() : null);
        alert.setRecipientId(recipient.getId() != null ? recipient.getId().toString() : null);
        alert.setRead(false);  // PARIDAD: read en lugar of acknowledged

        alert = alertRepository.save(alert);

        // Broadcast alert via WebSocket
        broadcastAlert(alert);

        log.info("Created require_response alert {} for message {} (no ticket)",
                alert.getId(), message.getId());

        return alert;
    }

    /**
     * Create escalation alert for manager when agent hasn't responded
     * Used by RequireResponseAlertJob for ticket escalation
     * PARIDAD RAILS: usa campos correctos de Alert entity
     */
    @Transactional
    public Alert createEscalationAlert(Ticket ticket, User manager, int alertCount) {
        String title = "Ticket escalado";
        String messageText = String.format(
                "El ticket #%d ha sido escalado - %d alertas sin respuesta del agente %s",
                ticket.getId(),
                alertCount,
                ticket.getAgent() != null ? ticket.getAgent().getFullName() : "desconocido"
        );

        Alert alert = new Alert();
        alert.setAlertType(AlertType.ESCALATION);
        alert.setSeverity(AlertSeverity.HIGH);
        alert.setTitle(title);
        alert.setBody(messageText);  // PARIDAD: body en lugar de message
        alert.setUrl("/tickets/" + ticket.getId());  // PARIDAD: url en lugar de ticket
        alert.setUser(manager);
        alert.setRead(false);  // PARIDAD: read en lugar of acknowledged

        alert = alertRepository.save(alert);

        // Broadcast alert via WebSocket
        broadcastAlert(alert);

        log.info("Created escalation alert {} for ticket {} to manager {}",
                alert.getId(), ticket.getId(), manager.getId());

        return alert;
    }

    /**
     * Get unacknowledged alert count for a user
     * PARIDAD RAILS: read en lugar de acknowledged
     */
    public long getUnacknowledgedCount(Long userId) {
        return alertRepository.countByUserIdAndReadFalse(userId);
    }

    /**
     * Find recent alerts for a user
     */
    public List<Alert> findRecentForUser(Long userId, int hoursBack) {
        LocalDateTime since = LocalDateTime.now().minusHours(hoursBack);
        return alertRepository.findRecentByUser(userId, since);
    }
}
