package com.digitalgroup.holape.job;

import com.digitalgroup.holape.domain.alert.entity.Alert;
import com.digitalgroup.holape.domain.alert.repository.AlertRepository;
import com.digitalgroup.holape.domain.alert.service.AlertService;
import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.service.ClientService;
import com.digitalgroup.holape.domain.common.enums.AlertType;
import com.digitalgroup.holape.domain.common.enums.MessageDirection;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import com.digitalgroup.holape.domain.ticket.repository.TicketRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.websocket.WebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Require Response Alert Job
 * Equivalent to Rails RequireResponseAlertWorker
 * Creates alerts for tickets/messages that require a response
 *
 * Aligned with actual entity structure:
 * - Uses ClientService to get settings by name
 * - Iterates over active clients
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequireResponseAlertJob {

    private final TicketRepository ticketRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final AlertRepository alertRepository;
    private final ClientService clientService;
    private final AlertService alertService;
    private final WebSocketService webSocketService;

    private static final int DEFAULT_ALERT_MINUTES = 15;

    /**
     * Check for tickets requiring response
     * Runs every 5 minutes
     */
    @Scheduled(fixedDelay = 300000) // Every 5 minutes
    @Transactional
    public void checkRequireResponse() {
        log.debug("Checking for tickets requiring response");

        List<Client> activeClients = clientService.findAllActive();

        int alertsCreated = 0;

        for (Client client : activeClients) {
            try {
                Integer alertMinutes = clientService.getAlertTimeNotRespondedConversation(client.getId());
                if (alertMinutes == null) {
                    alertMinutes = DEFAULT_ALERT_MINUTES;
                }

                LocalDateTime threshold = LocalDateTime.now().minusMinutes(alertMinutes);

                // Find open tickets with last message from user that's older than threshold
                List<Ticket> ticketsNeedingResponse = ticketRepository
                        .findTicketsRequiringResponse(client.getId(), threshold);

                for (Ticket ticket : ticketsNeedingResponse) {
                    try {
                        // Check if alert already exists for this ticket
                        if (!hasRecentAlert(ticket)) {
                            alertService.createRequireResponseAlert(ticket);

                            // Notify via WebSocket
                            if (ticket.getAgent() != null) {
                                webSocketService.sendAlertToUser(
                                        ticket.getAgent().getId(),
                                        "require_response",
                                        "Ticket #" + ticket.getId() + " requiere respuesta"
                                );
                            }

                            alertsCreated++;
                        }
                    } catch (Exception e) {
                        log.error("Failed to create alert for ticket {}", ticket.getId(), e);
                    }
                }

            } catch (Exception e) {
                log.error("Error checking require response for client {}", client.getId(), e);
            }
        }

        if (alertsCreated > 0) {
            log.info("Created {} require response alerts", alertsCreated);
        }
    }

    private boolean hasRecentAlert(Ticket ticket) {
        // Check if there's an unacknowledged require_response alert for this ticket
        // in the last hour to avoid duplicate alerts
        // PARIDAD RAILS: usa AlertType enum, no String
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        List<Alert> recentAlerts = alertRepository.findByTicketIdAndTypeAndCreatedAfter(
                ticket.getId(), AlertType.REQUIRE_RESPONSE, oneHourAgo);
        return !recentAlerts.isEmpty();
    }

    /**
     * Check and create alert for a specific message
     * Called from DeferredRequireResponseKpiCreationJob after the configured delay
     * Equivalent to Rails RequireResponseAlertWorker.perform logic
     */
    @Transactional
    public void checkAndCreateAlert(Long messageId, Long senderId, Long recipientId, int delayMinutes) {
        try {
            Optional<Message> messageOpt = messageRepository.findById(messageId);
            if (messageOpt.isEmpty()) {
                log.warn("Message {} not found for alert check", messageId);
                return;
            }

            Message message = messageOpt.get();
            Ticket ticket = message.getTicket();

            // Check if a response was already sent
            if (hasResponseAfterMessage(message, recipientId)) {
                // PARIDAD RAILS: Response was sent, clear the require_response flag (líneas 18-21)
                // Diferencia según dirección del mensaje original
                if (message.getDirection() == MessageDirection.INCOMING) {
                    // Si el mensaje original era incoming, limpiar flag del sender (cliente)
                    clearRequireResponseFlag(senderId);
                } else {
                    // Si el mensaje original era outgoing, limpiar flag del recipient
                    clearRequireResponseFlag(recipientId);
                }
                log.debug("Response found for message {}, no alert needed", messageId);
                return;
            }

            // No response yet - create alert
            Optional<User> senderOpt = userRepository.findById(senderId);
            Optional<User> recipientOpt = userRepository.findById(recipientId);

            if (senderOpt.isEmpty() || recipientOpt.isEmpty()) {
                log.warn("Sender {} or recipient {} not found", senderId, recipientId);
                return;
            }

            User sender = senderOpt.get();
            User recipient = recipientOpt.get();

            // Create the alert
            if (ticket != null) {
                // Create alert for ticket
                if (!hasRecentAlert(ticket)) {
                    alertService.createRequireResponseAlert(ticket);
                    log.info("Created require_response alert for ticket {} (message {})",
                            ticket.getId(), messageId);
                }
            } else {
                // Create alert without ticket
                alertService.createRequireResponseAlertForMessage(message, sender, recipient, delayMinutes);
                log.info("Created require_response alert for message {}", messageId);
            }

            // Send WebSocket notification
            webSocketService.sendAlertToUser(
                    recipientId,
                    "require_response",
                    "Mensaje de " + sender.getFullName() + " requiere respuesta (esperando " + delayMinutes + " min)"
            );

            // PARIDAD RAILS: Ensure require_response flag is set (líneas 26-30)
            // Diferencia según dirección del mensaje original
            if (message.getDirection() == MessageDirection.INCOMING) {
                // Si incoming: mantener flag en sender (cliente que espera respuesta)
                sender.setRequireResponse(true);
                if (sender.getLastMessageAt() == null) {
                    sender.setLastMessageAt(message.getCreatedAt());
                }
                userRepository.save(sender);
            } else {
                // Si outgoing: mantener flag en recipient
                recipient.setRequireResponse(true);
                if (recipient.getLastMessageAt() == null) {
                    recipient.setLastMessageAt(message.getCreatedAt());
                }
                userRepository.save(recipient);
            }

        } catch (Exception e) {
            log.error("Error checking/creating alert for message {}: {}", messageId, e.getMessage(), e);
        }
    }

    /**
     * Check if there's an outgoing response after the given message
     */
    private boolean hasResponseAfterMessage(Message originalMessage, Long agentId) {
        if (originalMessage.getTicket() != null) {
            // Check for outgoing messages in ticket after the original
            List<Message> laterMessages = messageRepository.findMessagesByTicketAndDirectionSince(
                    originalMessage.getTicket().getId(),
                    MessageDirection.OUTGOING,
                    originalMessage.getCreatedAt()
            );
            return !laterMessages.isEmpty();
        }

        // Check for any outgoing message from agent to sender after original message
        Optional<Message> lastOutgoing = messageRepository.findLastOutgoingBySender(agentId);
        if (lastOutgoing.isPresent()) {
            return lastOutgoing.get().getCreatedAt().isAfter(originalMessage.getCreatedAt());
        }

        return false;
    }

    /**
     * Clear the require_response flag for a user
     * PARIDAD RAILS: requireResponseAt no existe en schema
     */
    private void clearRequireResponseFlag(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setRequireResponse(false);
            userRepository.save(user);
        });
    }

}
