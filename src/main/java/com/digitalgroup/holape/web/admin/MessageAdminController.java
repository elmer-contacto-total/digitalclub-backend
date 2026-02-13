package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.entity.ClientSetting;
import com.digitalgroup.holape.domain.client.repository.ClientRepository;
import com.digitalgroup.holape.domain.client.repository.ClientSettingRepository;
import com.digitalgroup.holape.domain.crm.repository.CrmInfoRepository;
import com.digitalgroup.holape.domain.crm.service.CrmService;
import com.digitalgroup.holape.domain.media.entity.CapturedMedia;
import com.digitalgroup.holape.domain.media.repository.CapturedMediaRepository;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.domain.message.service.MessageService;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import com.digitalgroup.holape.domain.ticket.repository.TicketRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Message Admin Controller
 * Equivalent to Rails Admin::MessagesController
 */
@Slf4j
@RestController
@RequestMapping("/app/messages")
@RequiredArgsConstructor
public class MessageAdminController {

    private final MessageService messageService;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final CrmInfoRepository crmInfoRepository;
    private final CrmService crmService;
    private final TicketRepository ticketRepository;
    private final ClientRepository clientRepository;
    private final ClientSettingRepository clientSettingRepository;
    private final CapturedMediaRepository capturedMediaRepository;

    /**
     * Get messages - three modes:
     * 1. With client_id and include_detail: Get full conversation detail (PARIDAD Rails with client_id)
     * 2. With recipientId: Get conversation between two users
     * 3. Without params: Get messages list filtered by direction (PARIDAD Rails index)
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> getMessages(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(name = "client_id", required = false) Long clientId,
            @RequestParam(name = "chat_view_type", required = false) String chatViewType,
            @RequestParam(name = "include_detail", required = false) Boolean includeDetail,
            @RequestParam(required = false) Long recipientId,
            @RequestParam(required = false, defaultValue = "incoming") String direction,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "25") int pageSize) {

        // Mode 1: Full conversation detail (PARIDAD Rails when client_id is provided)
        if (clientId != null) {
            return getConversationDetail(currentUser, clientId, chatViewType);
        }

        // Mode 2: Conversation view (when recipientId is provided)
        if (recipientId != null) {
            Pageable pageable = PageRequest.of(page, pageSize, Sort.by("createdAt").descending());

            Page<Message> messagesPage = messageService.findConversation(
                    currentUser.getId(), recipientId, pageable);

            List<Map<String, Object>> data = messagesPage.getContent().stream()
                    .map(this::mapMessageToResponse)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "messages", data,
                    "total", messagesPage.getTotalElements(),
                    "page", page,
                    "totalPages", messagesPage.getTotalPages()
            ));
        }

        // Mode 3: Messages list view (PARIDAD Rails Admin::MessagesController#index)
        Pageable pageable = PageRequest.of(page, pageSize, Sort.by("sentAt").descending());

        Page<Message> messagesPage;

        // PARIDAD Rails: For managers, show messages of their subordinates (agents)
        if (currentUser.isManager()) {
            // Get subordinates' IDs (agents under this supervisor)
            List<Long> agentIds = userRepository.findAgentsBySupervisor(currentUser.getId())
                    .stream().map(User::getId).collect(Collectors.toList());

            if (agentIds.isEmpty()) {
                // No subordinates, return empty
                messagesPage = Page.empty(pageable);
            } else {
                messagesPage = messageService.findByDirectionAndUserIds(
                        agentIds,
                        direction,
                        search,
                        pageable
                );
            }
        } else {
            // For regular users (agents), show their own messages
            messagesPage = messageService.findByDirectionAndUser(
                    currentUser.getId(),
                    direction,
                    search,
                    pageable
            );
        }

        List<Map<String, Object>> data = messagesPage.getContent().stream()
                .map(msg -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", msg.getId());
                    map.put("content", msg.getContent());
                    map.put("createdAt", msg.getCreatedAt());
                    map.put("direction", msg.getDirection() != null ? msg.getDirection().name().toLowerCase() : direction);

                    // Always include both sender and receiver names
                    map.put("senderName", msg.getSender() != null ? msg.getSender().getFullName() : "Unknown");
                    map.put("receiverName", msg.getRecipient() != null ? msg.getRecipient().getFullName() : "Unknown");
                    return map;
                })
                .collect(Collectors.toList());

        // Return in PagedResponse format
        Map<String, Object> meta = new HashMap<>();
        meta.put("totalItems", messagesPage.getTotalElements());
        meta.put("totalPages", messagesPage.getTotalPages());
        meta.put("currentPage", page);
        meta.put("pageSize", pageSize);

        return ResponseEntity.ok(Map.of(
                "data", data,
                "meta", meta
        ));
    }

    /**
     * Get full conversation detail for a client
     * PARIDAD: Rails MessagesController#index when client_id is present
     */
    private ResponseEntity<?> getConversationDetail(
            CustomUserDetails currentUser,
            Long clientId,
            String chatViewType) {

        // Find the client
        Optional<User> clientOpt = userRepository.findById(clientId);
        if (clientOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        User client = clientOpt.get();

        // Get all messages for this conversation
        List<Message> messages = messageRepository.findBySenderIdOrRecipientIdOrderBySentAtAsc(
                clientId, clientId);

        // Get CRM fields for this user from unified custom_fields storage
        Map<String, String> visibleCrmData = crmService.getVisibleCrmDataByUser(client);

        // Get open ticket for this conversation
        // PARIDAD: Rails @show_close_button_flag = @messages_with_tickets.joins(:ticket).where(tickets: { status: 'open' }).exists?
        Page<Ticket> openTicketsPage = ticketRepository.findByUserIdAndStatus(
                clientId, com.digitalgroup.holape.domain.common.enums.TicketStatus.OPEN,
                PageRequest.of(0, 1, Sort.by("createdAt").descending()));
        Optional<Ticket> openTicket = openTicketsPage.hasContent() ?
                Optional.of(openTicketsPage.getContent().get(0)) : Optional.empty();

        // Get ticket close types from client_settings
        // PARIDAD: Rails @current_client.client_settings.find_by(name: 'ticket_close_types').hash_value
        List<Map<String, Object>> closeTypes = new ArrayList<>();
        Optional<ClientSetting> closeTypesSetting = clientSettingRepository.findByClientIdAndName(
                currentUser.getClientId(), "ticket_close_types");
        if (closeTypesSetting.isPresent() && closeTypesSetting.get().getHashValue() != null) {
            Object hashValue = closeTypesSetting.get().getHashValue();
            if (hashValue instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> typesList = (List<Map<String, Object>>) hashValue;
                closeTypes = typesList;
            } else if (hashValue instanceof Map) {
                // Handle case where it might be stored differently
                @SuppressWarnings("unchecked")
                Map<String, Object> typesMap = (Map<String, Object>) hashValue;
                if (typesMap.containsKey("types") && typesMap.get("types") instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> typesList = (List<Map<String, Object>>) typesMap.get("types");
                    closeTypes = typesList;
                }
            }
        }


        // Check if client is WhatsApp Business (need client info for this)
        Optional<Client> clientEntityForType = clientRepository.findById(currentUser.getClientId());
        boolean isClientWhatsappBusiness = clientEntityForType.isPresent() &&
                "whatsapp_business".equals(clientEntityForType.get().getClientType());

        // Check if last incoming message is older than 24 hours (for WhatsApp Business)
        // PARIDAD: Rails block_freeform_responses logic
        LocalDateTime twentyFourHoursAgo = LocalDateTime.now().minusHours(24);
        Optional<Message> lastIncoming = messages.stream()
                .filter(m -> m.getDirection() != null &&
                        m.getDirection().name().equalsIgnoreCase("incoming"))
                .reduce((first, second) -> second);

        boolean canSendFreeform = true;
        LocalDateTime lastIncomingMessageAt = null;

        // PARIDAD: Rails check for block_freeform_responses
        // @block_freeform_responses = (whatsapp_business && (!incoming.present? || (incoming.last.whatsapp_business_routed? && incoming.last.created_at < 24.hours.ago)))
        if (isClientWhatsappBusiness) {
            if (lastIncoming.isEmpty()) {
                canSendFreeform = false;
            } else {
                Message lastIncomingMsg = lastIncoming.get();
                lastIncomingMessageAt = lastIncomingMsg.getCreatedAt();
                // Check if message is WhatsApp Business routed and older than 24 hours
                Boolean isWBRouted = lastIncomingMsg.getWhatsappBusinessRouted();
                if (Boolean.TRUE.equals(isWBRouted) &&
                        lastIncomingMessageAt != null &&
                        lastIncomingMessageAt.isBefore(twentyFourHoursAgo)) {
                    canSendFreeform = false;
                }
            }
        } else if (lastIncoming.isPresent()) {
            lastIncomingMessageAt = lastIncoming.get().getCreatedAt();
        }

        // Build response
        Map<String, Object> response = new HashMap<>();

        // Client info
        Map<String, Object> clientMap = new HashMap<>();
        clientMap.put("id", client.getId());
        clientMap.put("firstName", client.getFirstName());
        clientMap.put("lastName", client.getLastName());
        clientMap.put("phone", client.getPhone());
        clientMap.put("email", client.getEmail());
        clientMap.put("codigo", client.getCodigo());
        clientMap.put("avatarData", client.getAvatarData());
        clientMap.put("requireResponse", client.getRequireResponse());
        clientMap.put("customFields", client.getCustomFields());
        clientMap.put("createdAt", client.getCreatedAt());
        clientMap.put("lastMessageAt", client.getLastMessageAt());
        response.put("client", clientMap);

        // Agent info (manager of the client)
        // Safely access lazy-loaded manager relation
        try {
            if (client.getManager() != null) {
                User agent = client.getManager();
                Map<String, Object> agentMap = new HashMap<>();
                agentMap.put("id", agent.getId());
                agentMap.put("firstName", agent.getFirstName());
                agentMap.put("lastName", agent.getLastName());
                agentMap.put("phone", agent.getPhone());
                agentMap.put("email", agent.getEmail());
                response.put("agent", agentMap);
            }
        } catch (Exception e) {
            log.debug("Could not load manager for client {}: {}", clientId, e.getMessage());
        }

        // Messages
        List<Map<String, Object>> messagesList = messages.stream()
                .map(this::mapMessageToResponse)
                .collect(Collectors.toList());
        response.put("messages", messagesList);

        // CRM fields — from unified custom_fields storage, filtered by crm_info_settings visibility
        List<Map<String, Object>> crmFieldsList = new ArrayList<>();
        for (Map.Entry<String, String> entry : visibleCrmData.entrySet()) {
            Map<String, Object> m = new HashMap<>();
            m.put("columnLabel", entry.getKey());
            m.put("columnValue", entry.getValue());
            m.put("columnVisible", true);
            crmFieldsList.add(m);
        }
        response.put("crmFields", crmFieldsList);

        // Custom fields — all custom_fields for the user
        response.put("customFields", client.getCustomFields() != null ? client.getCustomFields() : Map.of());

        // Ticket info
        if (openTicket.isPresent()) {
            Ticket ticket = openTicket.get();
            Map<String, Object> ticketMap = new HashMap<>();
            ticketMap.put("id", ticket.getId());
            ticketMap.put("status", ticket.getStatus().name().toLowerCase());
            ticketMap.put("createdAt", ticket.getCreatedAt());
            response.put("ticket", ticketMap);
        }

        // Close types (already mapped from client_settings)
        List<Map<String, Object>> closeTypesList = closeTypes.stream()
                .map(ct -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", ct.get("name"));
                    m.put("kpiName", ct.get("kpi_name"));
                    return m;
                })
                .collect(Collectors.toList());
        response.put("closeTypes", closeTypesList);

        // WhatsApp Business flags
        response.put("isWhatsappBusiness", isClientWhatsappBusiness);
        response.put("canSendFreeform", canSendFreeform);
        response.put("lastIncomingMessageAt", lastIncomingMessageAt);

        // Captured media (images and audios from Electron)
        // Uses COALESCE(messageSentAt, capturedAt) to properly order even when messageSentAt is null
        List<CapturedMedia> capturedMedia = capturedMediaRepository
                .findByClientUserIdOrderedByEffectiveTime(clientId, PageRequest.of(0, 100));
        List<Map<String, Object>> capturedMediaList = capturedMedia.stream()
                .map(media -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", media.getId());
                    m.put("mediaUuid", media.getMediaUuid());
                    m.put("mediaType", media.getMediaType().name().toLowerCase());
                    m.put("mimeType", media.getMimeType());
                    m.put("publicUrl", media.getPublicUrl());
                    m.put("filePath", media.getFilePath());
                    m.put("sizeBytes", media.getSizeBytes());
                    m.put("durationSeconds", media.getDurationSeconds());
                    m.put("capturedAt", media.getCapturedAt());
                    m.put("messageSentAt", media.getMessageSentAt());
                    m.put("chatPhone", media.getChatPhone());
                    m.put("chatName", media.getChatName());
                    m.put("deleted", media.getDeleted());
                    m.put("deletedAt", media.getDeletedAt());
                    return m;
                })
                .collect(Collectors.toList());
        response.put("capturedMedia", capturedMediaList);

        return ResponseEntity.ok(response);
    }

    /**
     * Get messages by ticket
     */
    @GetMapping("/ticket/{ticketId}")
    public ResponseEntity<List<Map<String, Object>>> getTicketMessages(@PathVariable Long ticketId) {
        List<Message> messages = messageService.findByTicket(ticketId);

        List<Map<String, Object>> data = messages.stream()
                .map(this::mapMessageToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(data);
    }

    /**
     * Send a new message
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody SendMessageRequest request) {

        Message message = messageService.createOutgoingMessage(
                currentUser.getId(),
                request.recipientId(),
                request.content()
        );

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", mapMessageToResponse(message)
        ));
    }

    private Map<String, Object> mapMessageToResponse(Message message) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", message.getId());
        map.put("content", message.getContent());
        map.put("direction", message.getDirection() != null ? message.getDirection().name().toLowerCase() : "unknown");
        map.put("status", message.getStatus() != null ? message.getStatus().name().toLowerCase() : "unknown");
        map.put("sent_at", message.getSentAt());
        map.put("created_at", message.getCreatedAt());
        map.put("is_template", message.getIsTemplate());
        map.put("template_name", message.getTemplateName());
        map.put("historic_sender_name", message.getHistoricSenderName());

        // Safely access lazy-loaded relations
        try {
            if (message.getSender() != null) {
                map.put("sender_id", message.getSender().getId());
                map.put("sender_name", message.getSender().getFullName());
            }
        } catch (Exception e) {
            log.debug("Could not load sender for message {}: {}", message.getId(), e.getMessage());
        }

        try {
            if (message.getRecipient() != null) {
                map.put("recipient_id", message.getRecipient().getId());
                map.put("recipient_name", message.getRecipient().getFullName());
            }
        } catch (Exception e) {
            log.debug("Could not load recipient for message {}: {}", message.getId(), e.getMessage());
        }

        try {
            if (message.getTicket() != null) {
                map.put("ticket_id", message.getTicket().getId());
            }
        } catch (Exception e) {
            log.debug("Could not load ticket for message {}: {}", message.getId(), e.getMessage());
        }

        return map;
    }

    /**
     * Send a template message
     */
    @PostMapping("/template")
    public ResponseEntity<Map<String, Object>> sendTemplate(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody SendTemplateRequest request) {

        Message message = messageService.sendTemplateMessage(
                currentUser.getId(),
                request.recipientId(),
                request.templateName(),
                request.variables()
        );

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", mapMessageToResponse(message)
        ));
    }

    /**
     * Send a media message
     */
    @PostMapping("/media")
    public ResponseEntity<Map<String, Object>> sendMedia(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody SendMediaRequest request) {

        Message message = messageService.sendMediaMessage(
                currentUser.getId(),
                request.recipientId(),
                request.caption(),
                request.mediaUrl(),
                request.mediaType()
        );

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", mapMessageToResponse(message)
        ));
    }

    /**
     * Mark messages as read
     */
    @PostMapping("/mark_read")
    public ResponseEntity<Map<String, Object>> markAsRead(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody MarkReadRequest request) {

        messageService.markMessagesAsRead(request.messageIds(), currentUser.getId());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "marked_count", request.messageIds().size()
        ));
    }

    /**
     * Get unread message count
     */
    @GetMapping("/unread_count")
    public ResponseEntity<Map<String, Object>> unreadCount(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        long count = messageService.getUnreadCount(currentUser.getId());

        return ResponseEntity.ok(Map.of("unread_count", count));
    }

    /**
     * Resend a failed message
     */
    @PostMapping("/{messageId}/resend")
    public ResponseEntity<Map<String, Object>> resend(@PathVariable Long messageId) {
        boolean success = messageService.resendMessage(messageId);

        if (success) {
            return ResponseEntity.ok(Map.of(
                    "result", "success",
                    "message", "Message queued for resend"
            ));
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                    "result", "error",
                    "message", "Cannot resend this message"
            ));
        }
    }

    /**
     * Get message by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> show(@PathVariable Long id) {
        Message message = messageService.findById(id);
        return ResponseEntity.ok(mapMessageToResponse(message));
    }

    /**
     * Create message from external API
     * Equivalent to Rails: Admin::MessagesController#api_intake_create
     * Used by external systems (NodeJS interceptor, etc.) to create messages
     */
    @PostMapping("/api_intake_create")
    public ResponseEntity<Map<String, Object>> apiIntakeCreate(@RequestBody ApiIntakeRequest request) {
        try {
            Message message;

            if ("incoming".equalsIgnoreCase(request.direction())) {
                message = messageService.createIncomingMessage(
                        request.senderPhone(),
                        request.recipientId(),
                        request.content(),
                        request.clientId()
                );
            } else {
                message = messageService.createOutgoingMessage(
                        request.senderId(),
                        request.recipientId(),
                        request.content()
                );
            }

            log.info("API intake created message {} direction={}", message.getId(), request.direction());

            return ResponseEntity.ok(Map.of(
                    "result", "success",
                    "message", mapMessageToResponse(message)
            ));

        } catch (Exception e) {
            log.error("API intake create failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "result", "error",
                    "message", e.getMessage()
            ));
        }
    }

    public record ApiIntakeRequest(
            String direction,
            Long senderId,
            String senderPhone,
            Long recipientId,
            String content,
            Long clientId
    ) {}

    /**
     * Activate/create ticket for incoming message without creating a Message record.
     * Used by Electron when it detects an incoming WhatsApp message.
     */
    @PostMapping("/activate_incoming_ticket")
    public ResponseEntity<Map<String, Object>> activateIncomingTicket(
            @RequestBody ActivateTicketRequest request) {
        try {
            Map<String, Object> result = messageService.activateIncomingTicket(
                    request.senderPhone(),
                    request.recipientId(),
                    request.clientId()
            );

            log.info("Activated incoming ticket for phone={} recipientId={} clientId={}",
                    request.senderPhone(), request.recipientId(), request.clientId());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("activate_incoming_ticket failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "result", "error",
                    "message", e.getMessage()
            ));
        }
    }

    public record ActivateTicketRequest(
            String senderPhone,
            Long recipientId,
            Long clientId
    ) {}

    public record SendMessageRequest(Long recipientId, String content) {}

    public record SendTemplateRequest(
            Long recipientId,
            String templateName,
            Map<String, String> variables
    ) {}

    public record SendMediaRequest(
            Long recipientId,
            String caption,
            String mediaUrl,
            String mediaType
    ) {}

    public record MarkReadRequest(List<Long> messageIds) {}
}
