package com.digitalgroup.holape.integration.whatsapp;

import com.digitalgroup.holape.domain.common.enums.MessageDirection;
import com.digitalgroup.holape.domain.common.enums.UserRole;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.domain.prospect.entity.Prospect;
import com.digitalgroup.holape.domain.prospect.repository.ProspectRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * WhatsApp Webhook Service
 * Equivalent to Rails Services::WhatsappWebhook
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppWebhookService {

    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final ProspectRepository prospectRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Processes incoming webhook from WhatsApp
     */
    @Transactional
    public void processWebhook(Map<String, Object> payload) {
        log.info("Processing WhatsApp webhook");

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) payload.get("entry");
            if (entries == null || entries.isEmpty()) return;

            for (Map<String, Object> entry : entries) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> changes = (List<Map<String, Object>>) entry.get("changes");
                if (changes == null) continue;

                for (Map<String, Object> change : changes) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> value = (Map<String, Object>) change.get("value");
                    if (value == null) continue;

                    processChange(value);
                }
            }
        } catch (Exception e) {
            log.error("Error processing WhatsApp webhook: {}", e.getMessage(), e);
        }
    }

    private void processChange(Map<String, Object> value) {
        // Get metadata
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) value.get("metadata");
        if (metadata == null) return;

        String displayPhoneNumber = (String) metadata.get("display_phone_number");

        // Find WhatsApp Business user by phone
        User whatsappBusinessUser = userRepository.findByPhone(displayPhoneNumber.replaceAll("[^0-9]", ""))
                .orElse(null);

        if (whatsappBusinessUser == null) {
            log.warn("WhatsApp Business user not found for phone: {}", displayPhoneNumber);
            return;
        }

        // Process messages
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) value.get("messages");
        if (messages != null) {
            for (Map<String, Object> msg : messages) {
                processIncomingMessage(msg, whatsappBusinessUser, value);
            }
        }

        // Process statuses (template status updates)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> statuses = (List<Map<String, Object>>) value.get("statuses");
        if (statuses != null) {
            for (Map<String, Object> status : statuses) {
                processStatusUpdate(status);
            }
        }
    }

    private void processIncomingMessage(Map<String, Object> msg, User whatsappBusinessUser,
                                        Map<String, Object> value) {
        String fromPhone = (String) msg.get("from");
        String messageType = (String) msg.get("type");
        String timestamp = (String) msg.get("timestamp");

        // Extract message content based on type
        String content = extractMessageContent(msg, messageType);

        // Find or create sender
        User sender = findOrCreateSender(fromPhone, whatsappBusinessUser, value);

        if (sender == null) {
            // Create as prospect
            Prospect prospect = createProspect(fromPhone, whatsappBusinessUser);
            createProspectMessage(prospect, whatsappBusinessUser, content, timestamp);
        } else {
            // Route message to appropriate agent
            User recipient = routeMessage(sender, whatsappBusinessUser);
            createMessage(sender, recipient, content, timestamp, whatsappBusinessUser);
        }
    }

    private String extractMessageContent(Map<String, Object> msg, String messageType) {
        return switch (messageType) {
            case "text" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> text = (Map<String, Object>) msg.get("text");
                yield text != null ? (String) text.get("body") : "";
            }
            case "image", "video", "document", "audio" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> media = (Map<String, Object>) msg.get(messageType);
                String caption = media != null ? (String) media.get("caption") : null;
                yield caption != null ? caption : "[" + messageType.toUpperCase() + "]";
            }
            default -> "[" + messageType.toUpperCase() + "]";
        };
    }

    private User findOrCreateSender(String phone, User whatsappBusinessUser, Map<String, Object> value) {
        String normalizedPhone = phone.replaceAll("[^0-9]", "");

        // Find existing user by phone
        return userRepository.findByPhone(normalizedPhone)
                .filter(u -> u.getRole() == UserRole.STANDARD)
                .orElse(null);
    }

    private User routeMessage(User sender, User whatsappBusinessUser) {
        // If sender has a manager (sticky agent), route to that agent
        if (sender.getManager() != null && sender.getManager().isAgent()) {
            return sender.getManager();
        }

        // Otherwise, find a random available agent for this client
        List<User> agents = userRepository.findAgentsByClient(whatsappBusinessUser.getClientId());
        if (!agents.isEmpty()) {
            User agent = agents.get(new Random().nextInt(agents.size()));
            // Update sender's manager (sticky agent)
            sender.setManager(agent);
            userRepository.save(sender);
            return agent;
        }

        // Fallback to WhatsApp Business user
        return whatsappBusinessUser;
    }

    private Prospect createProspect(String phone, User whatsappBusinessUser) {
        Prospect prospect = Prospect.builder()
                .phone(phone.replaceAll("[^0-9]", ""))
                .clientId(whatsappBusinessUser.getClientId())
                .build();
        return prospectRepository.save(prospect);
    }

    private void createMessage(User sender, User recipient, String content,
                               String timestamp, User originalRecipient) {
        LocalDateTime sentAt = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(Long.parseLong(timestamp)),
                ZoneId.of("America/Lima")
        );

        Message message = Message.builder()
                .sender(sender)
                .recipient(recipient)
                .content(content)
                .direction(MessageDirection.INCOMING)
                .sentAt(sentAt)
                .whatsappBusinessRouted(true)
                .originalWhatsappBusinessRecipientId(originalRecipient.getId())
                .build();

        Message savedMessage = messageRepository.save(message);
        log.info("Created incoming WhatsApp message from {} to {}", sender.getPhone(), recipient.getEmail());

        // PARIDAD RAILS: Publish event for async processing (equivalent to after_commit callbacks)
        eventPublisher.publishEvent(new com.digitalgroup.holape.event.DomainEventListener.MessageCreatedEvent(savedMessage));
    }

    private void createProspectMessage(Prospect prospect, User recipient, String content, String timestamp) {
        LocalDateTime sentAt = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(Long.parseLong(timestamp)),
                ZoneId.of("America/Lima")
        );

        Message message = Message.builder()
                .recipient(recipient)
                .content(content)
                .direction(MessageDirection.INCOMING)
                .sentAt(sentAt)
                .isProspect(true)
                .prospectSenderId(prospect.getId())
                .newSenderPhone(prospect.getPhone())
                .build();

        // Note: sender is null for prospect messages, need to handle in repository
        messageRepository.save(message);
        log.info("Created incoming WhatsApp message from prospect {} to {}", prospect.getPhone(), recipient.getEmail());
    }

    private void processStatusUpdate(Map<String, Object> status) {
        String statusType = (String) status.get("status");
        log.info("WhatsApp status update: {}", statusType);
        // Handle template approval/rejection status updates
    }
}
