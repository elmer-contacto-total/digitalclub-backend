package com.digitalgroup.holape.event;

import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import com.digitalgroup.holape.integration.firebase.PushNotificationService;
import com.digitalgroup.holape.websocket.WebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Domain Event Listener
 * Handles domain events after transaction commits
 * Equivalent to Rails after_commit callbacks
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventListener {

    private final WebSocketService webSocketService;
    private final PushNotificationService pushNotificationService;

    /**
     * Handle new message created event
     * Equivalent to Rails after_commit callbacks for messages
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handleMessageCreated(MessageCreatedEvent event) {
        Message message = event.message();

        log.debug("Handling MessageCreatedEvent for message {}", message.getId());

        // Send via WebSocket
        if (message.getRecipient() != null) {
            webSocketService.sendMessageToUser(message.getRecipient().getId(), message);
        }

        // Send to ticket channel if associated
        if (message.getTicket() != null) {
            webSocketService.sendMessageToTicket(message.getTicket().getId(), message);
        }

        // Broadcast WhatsApp Business message in real-time
        // Equivalent to Rails: after_commit :broadcast_whatsapp_business_message
        // This broadcasts to all relevant parties including the original WhatsApp Business recipient
        // and the assigned agent for real-time chat updates
        if (Boolean.TRUE.equals(message.getWhatsappBusinessRouted()) || message.isIncoming()) {
            webSocketService.broadcastWhatsAppBusinessMessage(message);
            log.debug("Broadcast WhatsApp Business message {} (routed={})",
                    message.getId(), message.getWhatsappBusinessRouted());
        }

        // Send push notification for incoming messages
        if (message.isIncoming() && message.getTicket() != null && message.getTicket().getAgent() != null) {
            pushNotificationService.notifyNewMessage(message.getTicket().getAgent(), message);
        }
    }

    /**
     * Handle ticket created event
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handleTicketCreated(TicketCreatedEvent event) {
        Ticket ticket = event.ticket();

        log.debug("Handling TicketCreatedEvent for ticket {}", ticket.getId());

        // Notify assigned agent
        if (ticket.getAgent() != null) {
            pushNotificationService.notifyTicketAssigned(ticket.getAgent(), ticket);
        }

        // Send WebSocket update
        webSocketService.sendTicketUpdate(ticket);
    }

    /**
     * Handle ticket updated event
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handleTicketUpdated(TicketUpdatedEvent event) {
        Ticket ticket = event.ticket();

        log.debug("Handling TicketUpdatedEvent for ticket {}", ticket.getId());

        webSocketService.sendTicketUpdate(ticket);
    }

    /**
     * Handle ticket closed event
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handleTicketClosed(TicketClosedEvent event) {
        Ticket ticket = event.ticket();

        log.debug("Handling TicketClosedEvent for ticket {}", ticket.getId());

        webSocketService.sendTicketUpdate(ticket);
    }

    /**
     * Handle KPI recorded event
     */
    @EventListener
    @Async
    public void handleKpiRecorded(KpiRecordedEvent event) {
        log.debug("Handling KpiRecordedEvent: {} for client {}",
                event.kpiType(), event.clientId());

        // Could trigger dashboard refresh via WebSocket
        // webSocketService.sendKpiUpdate(event.clientId(), Map.of("type", event.kpiType()));
    }

    // Event record classes
    public record MessageCreatedEvent(Message message) {}
    public record TicketCreatedEvent(Ticket ticket) {}
    public record TicketUpdatedEvent(Ticket ticket) {}
    public record TicketClosedEvent(Ticket ticket, String closeType) {}
    public record KpiRecordedEvent(Long clientId, String kpiType, Long value) {}
}
