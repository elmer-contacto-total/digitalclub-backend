package com.digitalgroup.holape.job;

import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.event.DomainEventListener.MessageCreatedEvent;
import com.digitalgroup.holape.websocket.WebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Process Prospect Message Job
 * Equivalent to Rails ProcessProspectMessageWorker
 *
 * Processes messages for prospects without creating tickets or KPIs.
 * This is a lighter version of ProcessMessageJob for prospect-related messages.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessProspectMessageJob {

    private final MessageRepository messageRepository;
    private final WebSocketService webSocketService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Process a prospect message
     * - Marks as processed
     * - Broadcasts to WebSocket for UI updates
     * - Does NOT create tickets or KPIs
     */
    @Transactional
    public void processProspectMessage(Long messageId) {
        try {
            Optional<Message> messageOpt = messageRepository.findById(messageId);
            if (messageOpt.isEmpty()) {
                log.warn("Prospect message {} not found", messageId);
                return;
            }

            Message message = messageOpt.get();

            // Skip if already processed
            if (Boolean.TRUE.equals(message.getProcessed())) {
                log.debug("Prospect message {} already processed", messageId);
                return;
            }

            // Mark as processed
            message.setProcessed(true);
            messageRepository.save(message);

            // Publish event for WebSocket broadcast
            eventPublisher.publishEvent(new MessageCreatedEvent(message));

            // PARIDAD RAILS: Send direct WebSocket notification with swapped IDs (líneas 8-18)
            // Para incoming: sender_id = sender, recipient_id = recipient
            // Para outgoing: sender_id = recipient, recipient_id = sender (swap)
            Map<String, Object> payload = buildMessagePayload(message);
            Long targetUserId;
            if (message.getDirection() == com.digitalgroup.holape.domain.common.enums.MessageDirection.INCOMING) {
                payload.put("sender_id", message.getSender() != null ? message.getSender().getId() : null);
                payload.put("recipient_id", message.getRecipient() != null ? message.getRecipient().getId() : null);
                targetUserId = message.getRecipient() != null ? message.getRecipient().getId() : null;
            } else {
                // PARIDAD RAILS: Swap para outgoing (líneas 14-17)
                payload.put("sender_id", message.getRecipient() != null ? message.getRecipient().getId() : null);
                payload.put("recipient_id", message.getSender() != null ? message.getSender().getId() : null);
                targetUserId = message.getSender() != null ? message.getSender().getId() : null;
            }

            if (targetUserId != null) {
                webSocketService.sendMessageToUser(
                        targetUserId,
                        "new_prospect_message",
                        payload
                );
            }

            log.info("Processed prospect message {}", messageId);

        } catch (Exception e) {
            log.error("Error processing prospect message {}: {}", messageId, e.getMessage(), e);
        }
    }

    /**
     * Build payload for WebSocket notification
     */
    private Map<String, Object> buildMessagePayload(Message message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", message.getId());
        payload.put("content", message.getContent());
        payload.put("sender_name", message.getSender() != null ? message.getSender().getFullName() : "Unknown");
        payload.put("direction", message.getDirection() != null ? message.getDirection().name().toLowerCase() : "unknown");
        payload.put("created_at", message.getCreatedAt());
        return payload;
    }
}
