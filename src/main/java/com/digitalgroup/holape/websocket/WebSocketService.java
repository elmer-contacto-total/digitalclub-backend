package com.digitalgroup.holape.websocket;

import com.digitalgroup.holape.api.v1.dto.media.CapturedMediaResponse;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket Service
 * Handles real-time messaging via STOMP WebSocket
 * Equivalent to Rails ActionCable
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketService {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Send message to specific user
     */
    public void sendMessageToUser(Long userId, Message message) {
        Map<String, Object> payload = buildMessagePayload(message);

        String destination = "/queue/messages";
        messagingTemplate.convertAndSendToUser(
                userId.toString(),
                destination,
                payload
        );

        log.debug("Sent message {} to user {}", message.getId(), userId);
    }

    /**
     * Send message to specific user with custom type and payload
     */
    public void sendMessageToUser(Long userId, String type, Map<String, Object> payload) {
        Map<String, Object> wrappedPayload = new HashMap<>();
        wrappedPayload.put("type", type);
        wrappedPayload.putAll(payload);
        wrappedPayload.put("timestamp", System.currentTimeMillis());

        String destination = "/queue/messages";
        messagingTemplate.convertAndSendToUser(
                userId.toString(),
                destination,
                wrappedPayload
        );

        log.debug("Sent {} message to user {}", type, userId);
    }

    /**
     * Send message to ticket channel (all agents viewing the ticket)
     */
    public void sendMessageToTicket(Long ticketId, Message message) {
        Map<String, Object> payload = buildMessagePayload(message);

        String destination = "/topic/ticket." + ticketId;
        messagingTemplate.convertAndSend(destination, payload);

        log.debug("Sent message {} to ticket channel {}", message.getId(), ticketId);
    }

    /**
     * Send ticket update notification
     */
    public void sendTicketUpdate(Ticket ticket) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "ticket_update");
        payload.put("ticket_id", ticket.getId());
        payload.put("status", ticket.getStatus().name().toLowerCase());

        if (ticket.getUser() != null) {
            payload.put("user_id", ticket.getUser().getId());
            payload.put("user_name", ticket.getUser().getFullName());
        }
        if (ticket.getAgent() != null) {
            payload.put("agent_id", ticket.getAgent().getId());
            payload.put("agent_name", ticket.getAgent().getFullName());
        }

        // Send to ticket channel
        String ticketDestination = "/topic/ticket." + ticket.getId();
        messagingTemplate.convertAndSend(ticketDestination, payload);

        // Also send to agent's personal queue
        if (ticket.getAgent() != null) {
            messagingTemplate.convertAndSendToUser(
                    ticket.getAgent().getId().toString(),
                    "/queue/tickets",
                    payload
            );
        }
    }

    /**
     * Send alert to user
     */
    public void sendAlertToUser(Long userId, String alertType, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "alert");
        payload.put("alert_type", alertType);
        payload.put("message", message);
        payload.put("timestamp", System.currentTimeMillis());

        messagingTemplate.convertAndSendToUser(
                userId.toString(),
                "/queue/alerts",
                payload
        );

        log.debug("Sent alert to user {}: {}", userId, alertType);
    }

    /**
     * Send notification to client channel (all users of a client)
     */
    public void sendToClient(Long clientId, String type, Object data) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", type);
        payload.put("data", data);
        payload.put("timestamp", System.currentTimeMillis());

        String destination = "/topic/client." + clientId;
        messagingTemplate.convertAndSend(destination, payload);

        log.debug("Sent {} to client channel {}", type, clientId);
    }

    /**
     * Send KPI update to dashboard
     */
    public void sendKpiUpdate(Long clientId, Map<String, Object> kpiData) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "kpi_update");
        payload.put("data", kpiData);
        payload.put("timestamp", System.currentTimeMillis());

        String destination = "/topic/client." + clientId + ".kpis";
        messagingTemplate.convertAndSend(destination, payload);
    }

    /**
     * Send typing indicator
     */
    public void sendTypingIndicator(Long ticketId, Long userId, boolean isTyping) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "typing");
        payload.put("user_id", userId);
        payload.put("is_typing", isTyping);

        String destination = "/topic/ticket." + ticketId + ".typing";
        messagingTemplate.convertAndSend(destination, payload);
    }

    /**
     * Send online status update
     */
    public void sendOnlineStatus(Long userId, boolean isOnline) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "online_status");
        payload.put("user_id", userId);
        payload.put("is_online", isOnline);
        payload.put("timestamp", System.currentTimeMillis());

        // Broadcast to all connected users
        messagingTemplate.convertAndSend("/topic/presence", payload);
    }

    /**
     * Send captured media notification.
     * Broadcasts to /topic/captured_media so ANY user viewing the conversation
     * can receive it (frontend filters by clientUserId).
     * Also sends to the capturing agent's personal queue.
     */
    public void sendCapturedMediaUpdate(CapturedMediaResponse media) {
        if (media == null) {
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "CAPTURED_MEDIA");
        payload.put("payload", media);
        payload.put("timestamp", System.currentTimeMillis());

        // Broadcast to topic (any user viewing the conversation receives it)
        messagingTemplate.convertAndSend("/topic/captured_media", payload);

        // Also send to agent's personal queue (for Electron app)
        if (media.getAgentId() != null) {
            messagingTemplate.convertAndSendToUser(
                    media.getAgentId().toString(),
                    "/queue/captured_media",
                    payload
            );
        }

        log.info("Broadcast captured media {} for client {}", media.getMediaUuid(), media.getClientUserId());
    }

    /**
     * Notify that a captured media was deleted (WhatsApp message was deleted).
     * Broadcasts to /topic/captured_media so all viewers are notified.
     */
    public void sendCapturedMediaDeleted(CapturedMediaResponse media) {
        if (media == null) {
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "CAPTURED_MEDIA_DELETED");
        payload.put("payload", media);
        payload.put("timestamp", System.currentTimeMillis());

        // Broadcast to topic
        messagingTemplate.convertAndSend("/topic/captured_media", payload);

        // Also send to agent's personal queue
        if (media.getAgentId() != null) {
            messagingTemplate.convertAndSendToUser(
                    media.getAgentId().toString(),
                    "/queue/captured_media",
                    payload
            );
        }

        log.info("Broadcast captured media deleted {} for client {}", media.getMediaUuid(), media.getClientUserId());
    }

    /**
     * Broadcast WhatsApp Business message in real-time
     * Equivalent to Rails: broadcast_whatsapp_business_message callback
     *
     * This sends the message to:
     * 1. The recipient's personal queue
     * 2. The original WhatsApp Business recipient if routed to another agent
     * 3. The ticket channel if message has a ticket
     */
    public void broadcastWhatsAppBusinessMessage(Message message) {
        if (message == null) {
            return;
        }

        Map<String, Object> payload = buildWhatsAppBusinessPayload(message);

        // Send to recipient's personal queue
        if (message.getRecipient() != null) {
            messagingTemplate.convertAndSendToUser(
                    message.getRecipient().getId().toString(),
                    "/queue/messages",
                    payload
            );
            log.debug("Broadcast WA Business message {} to recipient {}", message.getId(), message.getRecipient().getId());
        }

        // If message was routed, also notify the original WhatsApp Business recipient
        if (Boolean.TRUE.equals(message.getWhatsappBusinessRouted()) &&
                message.getOriginalWhatsappBusinessRecipientId() != null &&
                (message.getRecipient() == null || !message.getOriginalWhatsappBusinessRecipientId().equals(message.getRecipient().getId()))) {

            messagingTemplate.convertAndSendToUser(
                    message.getOriginalWhatsappBusinessRecipientId().toString(),
                    "/queue/messages",
                    payload
            );
            log.debug("Broadcast WA Business message {} to original recipient {}",
                    message.getId(), message.getOriginalWhatsappBusinessRecipientId());
        }

        // Send to sender's personal queue (for incoming messages, notify the sender's agent/manager)
        if (message.getSender() != null && message.getSender().getManager() != null) {
            messagingTemplate.convertAndSendToUser(
                    message.getSender().getManager().getId().toString(),
                    "/queue/messages",
                    payload
            );
        }

        // Send to ticket channel if exists
        if (message.getTicket() != null) {
            String ticketDestination = "/topic/ticket." + message.getTicket().getId();
            messagingTemplate.convertAndSend(ticketDestination, payload);
        }

        // Send to client channel for dashboard updates
        Long clientId = getClientIdFromMessage(message);
        if (clientId != null) {
            sendToClient(clientId, "new_whatsapp_message", payload);
        }
    }

    /**
     * Build payload for WhatsApp Business message broadcast
     */
    private Map<String, Object> buildWhatsAppBusinessPayload(Message message) {
        Map<String, Object> payload = buildMessagePayload(message);
        payload.put("type", "whatsapp_business_message");
        payload.put("whatsapp_business_routed", Boolean.TRUE.equals(message.getWhatsappBusinessRouted()));

        if (message.getOriginalWhatsappBusinessRecipientId() != null) {
            payload.put("original_recipient_id", message.getOriginalWhatsappBusinessRecipientId());
        }

        // PARIDAD RAILS: mediaUrl/mediaType no existen, usar hasMedia() y binaryContentData
        if (message.hasMedia()) {
            payload.put("has_media", true);
            payload.put("media_data", message.getBinaryContentData());
        }

        return payload;
    }

    /**
     * Extract client ID from message
     */
    private Long getClientIdFromMessage(Message message) {
        if (message.getSender() != null && message.getSender().getClient() != null) {
            return message.getSender().getClient().getId();
        }
        if (message.getRecipient() != null && message.getRecipient().getClient() != null) {
            return message.getRecipient().getClient().getId();
        }
        return null;
    }

    private Map<String, Object> buildMessagePayload(Message message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "new_message");
        payload.put("id", message.getId());
        payload.put("content", message.getContent());
        payload.put("direction", message.getDirection().name().toLowerCase());
        payload.put("status", message.getStatus().name().toLowerCase());
        payload.put("created_at", message.getCreatedAt());

        if (message.getSender() != null) {
            payload.put("sender_id", message.getSender().getId());
            payload.put("sender_name", message.getSender().getFullName());
        }
        if (message.getRecipient() != null) {
            payload.put("recipient_id", message.getRecipient().getId());
        }
        if (message.getTicket() != null) {
            payload.put("ticket_id", message.getTicket().getId());
        }

        return payload;
    }
}
