package com.digitalgroup.holape.websocket;

import com.digitalgroup.holape.domain.message.service.MessageService;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

/**
 * WebSocket Controller
 * Handles incoming WebSocket messages from clients
 * Equivalent to Rails ActionCable channels
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class WebSocketController {

    private final MessageService messageService;
    private final WebSocketService webSocketService;

    /**
     * Handle incoming chat message
     * Client sends to: /app/chat.send
     */
    @MessageMapping("/chat.send")
    @SendToUser("/queue/messages")
    public Map<String, Object> sendMessage(
            @Payload SendMessagePayload payload,
            Principal principal) {

        Long senderId = Long.parseLong(principal.getName());

        try {
            var message = messageService.createOutgoingMessage(
                    senderId,
                    payload.recipientId(),
                    payload.content()
            );

            log.debug("User {} sent message to {}", senderId, payload.recipientId());

            return Map.of(
                    "status", "sent",
                    "message_id", message.getId()
            );
        } catch (Exception e) {
            log.error("Error sending message from user {}", senderId, e);
            return Map.of(
                    "status", "error",
                    "message", e.getMessage()
            );
        }
    }

    /**
     * Handle typing indicator
     * Client sends to: /app/ticket.{ticketId}.typing
     */
    @MessageMapping("/ticket.{ticketId}.typing")
    public void handleTyping(
            @DestinationVariable Long ticketId,
            @Payload TypingPayload payload,
            Principal principal) {

        Long userId = Long.parseLong(principal.getName());
        webSocketService.sendTypingIndicator(ticketId, userId, payload.isTyping());
    }

    /**
     * Handle read receipt
     * Client sends to: /app/message.read
     */
    @MessageMapping("/message.read")
    public void handleReadReceipt(
            @Payload ReadReceiptPayload payload,
            Principal principal) {

        Long userId = Long.parseLong(principal.getName());

        try {
            messageService.markAsRead(payload.messageId(), userId);
            log.debug("User {} marked message {} as read", userId, payload.messageId());
        } catch (Exception e) {
            log.error("Error marking message as read", e);
        }
    }

    /**
     * Subscribe to ticket channel
     * Client sends to: /app/ticket.subscribe
     */
    @MessageMapping("/ticket.subscribe")
    @SendToUser("/queue/subscription")
    public Map<String, Object> subscribeToTicket(
            @Payload SubscribeTicketPayload payload,
            Principal principal) {

        Long userId = Long.parseLong(principal.getName());

        // Verify user has access to this ticket
        // In production, add proper authorization check

        log.debug("User {} subscribed to ticket {}", userId, payload.ticketId());

        return Map.of(
                "status", "subscribed",
                "ticket_id", payload.ticketId()
        );
    }

    /**
     * Update presence status
     * Client sends to: /app/presence
     */
    @MessageMapping("/presence")
    public void updatePresence(
            @Payload PresencePayload payload,
            Principal principal) {

        Long userId = Long.parseLong(principal.getName());
        webSocketService.sendOnlineStatus(userId, payload.isOnline());
    }

    // Payload records
    public record SendMessagePayload(Long recipientId, String content) {}
    public record TypingPayload(boolean isTyping) {}
    public record ReadReceiptPayload(Long messageId) {}
    public record SubscribeTicketPayload(Long ticketId) {}
    public record PresencePayload(boolean isOnline) {}
}
