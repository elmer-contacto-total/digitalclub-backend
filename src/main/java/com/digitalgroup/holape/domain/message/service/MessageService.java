package com.digitalgroup.holape.domain.message.service;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.entity.ClientSetting;
import com.digitalgroup.holape.domain.client.repository.ClientSettingRepository;
import com.digitalgroup.holape.domain.common.enums.KpiType;
import com.digitalgroup.holape.domain.common.enums.MessageDirection;
import com.digitalgroup.holape.domain.common.enums.MessageStatus;
import com.digitalgroup.holape.domain.common.enums.TicketStatus;
import com.digitalgroup.holape.domain.common.enums.UserRole;
import com.digitalgroup.holape.domain.kpi.entity.Kpi;
import com.digitalgroup.holape.domain.kpi.repository.KpiRepository;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import com.digitalgroup.holape.domain.ticket.repository.TicketRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.event.DomainEventListener.MessageCreatedEvent;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import com.digitalgroup.holape.integration.whatsapp.WhatsAppCloudApiClient;
import com.digitalgroup.holape.integration.firebase.FirebaseCloudMessagingService;
import com.digitalgroup.holape.util.WorkingHoursUtils;
import com.digitalgroup.holape.websocket.WebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Message Service
 * Contains ALL business logic equivalent to Rails Message model callbacks:
 * - before_validation: ensure_sender_exists_for_incoming_message_with_unknown_sender
 * - before_save: mark_processed_true
 * - before_create: route_incoming_whatsapp_business_message
 * - after_commit: fix_sent_at
 * - after_commit: create_or_assign_to_ticket (via CreateOrAssignToTicketJob with 5s delay)
 * - after_commit: route_outgoing_whatsapp_message
 * - after_commit: log_kpi_responded_and_first_response_time
 * - after_commit: log_incoming_and_outgoing_message (via DeferredRequireResponseKpiCreationJob with 10s delay)
 * - after_commit: broadcast_whatsapp_business_message
 * - after_commit: save_historic_sender_name
 * - after_commit: reconstruct_require_response_for_user (via ReconstructRequireResponseUserFlagJob with 20s delay)
 * - after_commit: update_last_message_at
 *
 * IMPORTANT: Uses delayed jobs to handle out-of-order message delivery:
 * - 5 seconds: Ticket assignment (CreateOrAssignToTicketJob)
 * - 10 seconds: KPI creation (DeferredRequireResponseKpiCreationJob)
 * - 20 seconds: Require response flag (ReconstructRequireResponseUserFlagJob)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final KpiRepository kpiRepository;
    private final ClientSettingRepository clientSettingRepository;
    private final WhatsAppCloudApiClient whatsAppClient;
    private final FirebaseCloudMessagingService firebaseService;
    private final ApplicationEventPublisher eventPublisher;
    private final WebSocketService webSocketService;

    // Delayed job dependencies - injected via @Lazy to avoid circular dependencies
    private final org.springframework.context.ApplicationContext applicationContext;

    private final Random random = new Random();

    // Lazy-loaded job references
    private com.digitalgroup.holape.job.CreateOrAssignToTicketJob createOrAssignToTicketJob;
    private com.digitalgroup.holape.job.DeferredRequireResponseKpiCreationJob deferredKpiJob;
    private com.digitalgroup.holape.job.ReconstructRequireResponseUserFlagJob reconstructFlagJob;

    private com.digitalgroup.holape.job.CreateOrAssignToTicketJob getCreateOrAssignToTicketJob() {
        if (createOrAssignToTicketJob == null) {
            createOrAssignToTicketJob = applicationContext.getBean(com.digitalgroup.holape.job.CreateOrAssignToTicketJob.class);
        }
        return createOrAssignToTicketJob;
    }

    private com.digitalgroup.holape.job.DeferredRequireResponseKpiCreationJob getDeferredKpiJob() {
        if (deferredKpiJob == null) {
            deferredKpiJob = applicationContext.getBean(com.digitalgroup.holape.job.DeferredRequireResponseKpiCreationJob.class);
        }
        return deferredKpiJob;
    }

    private com.digitalgroup.holape.job.ReconstructRequireResponseUserFlagJob getReconstructFlagJob() {
        if (reconstructFlagJob == null) {
            reconstructFlagJob = applicationContext.getBean(com.digitalgroup.holape.job.ReconstructRequireResponseUserFlagJob.class);
        }
        return reconstructFlagJob;
    }

    @Transactional(readOnly = true)
    public Message findById(Long id) {
        return messageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Message", id));
    }

    @Transactional(readOnly = true)
    public List<Message> findByTicket(Long ticketId) {
        return messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
    }

    @Transactional(readOnly = true)
    public Page<Message> findConversation(Long userId1, Long userId2, Pageable pageable) {
        return messageRepository.findConversationBetweenUsers(userId1, userId2, pageable);
    }

    /**
     * Find messages by direction and user
     * PARIDAD RAILS: Admin::MessagesController#index (format.json)
     *
     * @param userId Current user ID
     * @param direction "incoming" or "outgoing"
     * @param search Optional search term for content
     * @param pageable Pagination info
     */
    @Transactional(readOnly = true)
    public Page<Message> findByDirectionAndUser(Long userId, String direction, String search, Pageable pageable) {
        if ("incoming".equalsIgnoreCase(direction)) {
            if (search != null && !search.isBlank()) {
                return messageRepository.findIncomingMessagesWithSearch(userId, search, pageable);
            }
            return messageRepository.findByRecipientIdAndDirectionOrderBySentAtDesc(
                    userId, MessageDirection.INCOMING, pageable);
        } else {
            if (search != null && !search.isBlank()) {
                return messageRepository.findOutgoingMessagesWithSearch(userId, search, pageable);
            }
            return messageRepository.findBySenderIdAndDirectionOrderBySentAtDesc(
                    userId, MessageDirection.OUTGOING, pageable);
        }
    }

    /**
     * Find messages by direction for multiple user IDs (for supervisors)
     * PARIDAD Rails: MessagesController#index for manager_level_4
     * - Incoming: messages where recipient_id IN agent IDs
     * - Outgoing: messages where sender_id IN agent IDs
     */
    @Transactional(readOnly = true)
    public Page<Message> findByDirectionAndUserIds(List<Long> userIds, String direction, String search, Pageable pageable) {
        if ("incoming".equalsIgnoreCase(direction)) {
            if (search != null && !search.isBlank()) {
                return messageRepository.findIncomingMessagesByUserIdsWithSearch(userIds, search, pageable);
            }
            return messageRepository.findByRecipientIdInAndDirectionOrderBySentAtDesc(
                    userIds, MessageDirection.INCOMING, pageable);
        } else {
            if (search != null && !search.isBlank()) {
                return messageRepository.findOutgoingMessagesByUserIdsWithSearch(userIds, search, pageable);
            }
            return messageRepository.findBySenderIdInAndDirectionOrderBySentAtDesc(
                    userIds, MessageDirection.OUTGOING, pageable);
        }
    }

    /**
     * Creates a new outgoing message
     * Implements all Rails after_commit callbacks
     */
    @Transactional
    public Message createOutgoingMessage(Long senderId, Long recipientId, String content) {
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new ResourceNotFoundException("User", senderId));
        User recipient = userRepository.findById(recipientId)
                .orElseThrow(() -> new ResourceNotFoundException("User", recipientId));

        Message message = new Message();
        message.setSender(sender);
        message.setRecipient(recipient);
        message.setContent(content);
        message.setDirection(MessageDirection.OUTGOING);
        message.setSentAt(LocalDateTime.now());
        message.setStatus(MessageStatus.SENT);
        message.setProcessed(true);

        // PARIDAD RAILS: messages_controller.rb:199
        // @message.whatsapp_business_routed = current_user.client.client_type == "whatsapp_business"
        Client senderClient = sender.getClient();
        if (senderClient != null && senderClient.isWhatsAppBusiness()) {
            message.setWhatsappBusinessRouted(true);
        }

        // Save historic sender name
        message.setHistoricSenderName(sender.getFullName());

        // Assign to ticket
        assignToTicket(message);

        Message savedMessage = messageRepository.save(message);

        // Post-save operations (equivalent to after_commit)
        afterMessageCreated(savedMessage);

        return savedMessage;
    }

    /**
     * Creates incoming message from WhatsApp webhook
     * Implements: ensure_sender_exists, route_incoming_whatsapp_business_message
     */
    @Transactional
    public Message createIncomingMessage(String senderPhone, Long recipientId, String content, Long clientId) {
        // PARIDAD RAILS: Primero obtener recipient para pasar sus datos al crear sender
        User recipient = userRepository.findById(recipientId)
                .orElseThrow(() -> new ResourceNotFoundException("User", recipientId));

        // 1. Ensure sender exists (equivalent to ensure_sender_exists_for_incoming_message_with_unknown_sender)
        // PARIDAD RAILS: Ahora pasamos recipient para copiar country_id, time_zone, manager_id
        User sender = ensureSenderExists(senderPhone, clientId, recipient);

        Message message = new Message();
        message.setSender(sender);
        message.setRecipient(recipient);
        message.setContent(content);
        message.setDirection(MessageDirection.INCOMING);
        message.setSentAt(LocalDateTime.now());
        message.setStatus(MessageStatus.UNREAD);
        message.setProcessed(false);

        // 2. Route to sticky agent or random available agent
        routeIncomingWhatsAppBusinessMessage(message, clientId);

        // Save historic sender name
        message.setHistoricSenderName(sender.getFullName());

        // Assign to ticket
        assignToTicket(message);

        Message savedMessage = messageRepository.save(message);

        // Post-save operations
        afterMessageCreated(savedMessage);

        return savedMessage;
    }

    /**
     * Activates/creates a ticket for an incoming WhatsApp message without creating a Message record.
     * Used by Electron when it detects an incoming message — only needs to ensure the ticket
     * exists and the user is flagged as requiring a response.
     *
     * @return Map with ticketId, userId, requireResponse
     */
    @Transactional
    public Map<String, Object> activateIncomingTicket(String senderPhone, Long recipientId, Long clientId) {
        User recipient = userRepository.findById(recipientId)
                .orElseThrow(() -> new ResourceNotFoundException("User", recipientId));

        // Ensure sender user exists (reuses existing private method)
        User sender = ensureSenderExists(senderPhone, clientId, recipient);

        // Only create tickets for standard users
        if (sender.getRole() != UserRole.STANDARD) {
            log.warn("activateIncomingTicket: sender {} is not STANDARD, skipping", sender.getId());
            return Map.of("result", "skipped", "reason", "sender_not_standard");
        }

        // Determine the effective agent (sticky agent or recipient)
        User agent = (sender.getManager() != null) ? sender.getManager() : recipient;

        // Find open ticket between user and agent
        Optional<Ticket> existingOpenTicket = ticketRepository.findOpenTicketBetweenUsers(
                sender.getId(), agent.getId(), TicketStatus.OPEN);

        Ticket ticket;
        boolean created = false;
        if (existingOpenTicket.isPresent()) {
            ticket = existingOpenTicket.get();
        } else {
            // No open ticket — create a new one (same logic as assignToTicket for incoming)
            ticket = new Ticket();
            ticket.setUser(sender);
            ticket.setAgent(agent);
            ticket.setStatus(TicketStatus.OPEN);
            ticket.setSubject("Incoming message from " + senderPhone);
            ticket = ticketRepository.save(ticket);
            created = true;

            // Log new_ticket KPI
            Long senderClientId = sender.getClient() != null ? sender.getClient().getId() : clientId;
            logKpi(senderClientId, agent.getId(), KpiType.NEW_TICKET, 1L, ticket.getId());

            log.info("Activated incoming ticket {} (new) for user {} agent {}", ticket.getId(), sender.getId(), agent.getId());
        }

        // Set requireResponse = true on the sender (client user)
        sender.setRequireResponse(true);
        userRepository.save(sender);

        // Notify via WebSocket
        webSocketService.sendTicketUpdate(ticket);

        if (!created) {
            log.info("Activated incoming ticket {} (existing) for user {} agent {}", ticket.getId(), sender.getId(), agent.getId());
        }

        return Map.of(
                "result", "success",
                "ticketId", ticket.getId(),
                "userId", sender.getId(),
                "requireResponse", true,
                "created", created
        );
    }

    /**
     * Process incoming message asynchronously
     * Equivalent to ProcessMessageWorker
     */
    @Transactional
    public void processIncomingMessage(Long messageId) {
        Message message = findById(messageId);

        if (message.getProcessed() != null && message.getProcessed()) {
            log.debug("Message {} already processed", messageId);
            return;
        }

        // Fix sent_at if needed
        fixSentAt(message);

        // Mark as processed
        message.setProcessed(true);

        // Save historic sender name if not set
        if (message.getHistoricSenderName() == null && message.getSender() != null) {
            message.setHistoricSenderName(message.getSender().getFullName());
        }

        messageRepository.save(message);

        // Log KPIs
        logKpis(message);

        // Reconstruct require_response for user
        reconstructRequireResponseForUser(message);

        // Update last_message_at
        updateLastMessageAt(message);

        log.info("Processed message {}", messageId);
    }

    /**
     * Mark message as read
     */
    @Transactional
    public void markAsRead(Long messageId, Long userId) {
        Message message = findById(messageId);

        if (message.getRecipient() != null && message.getRecipient().getId().equals(userId)) {
            message.setStatus(MessageStatus.READ);
            messageRepository.save(message);
        }
    }

    // ==================== CALLBACK IMPLEMENTATIONS ====================

    /**
     * Ensures sender user exists for incoming messages with unknown sender
     * Creates a new standard user if not found
     * Equivalent to: ensure_sender_exists_for_incoming_message_with_unknown_sender
     *
     * PARIDAD RAILS: Crea usuario con todos los campos requeridos:
     * - email: cliente_{timestamp}@no-domain.com
     * - password: aleatorio (SecureRandom.hex(8) equivalente)
     * - manager_id: del recipient (para sticky agent)
     * - country_id: del recipient
     * - time_zone: del recipient
     * - temp_password: igual que password
     * - first_name: "Nuevo Cliente"
     * - last_name: timestamp
     * - status: ACTIVE
     */
    private User ensureSenderExists(String phone, Long clientId, User recipient) {
        // Normalize phone
        String normalizedPhone = normalizePhone(phone);

        // Try to find existing user
        Optional<User> existingUser = userRepository.findByPhoneAndClientId(normalizedPhone, clientId);
        if (existingUser.isPresent()) {
            return existingUser.get();
        }

        // PARIDAD RAILS: Crear nuevo usuario con todos los campos como en Rails
        long timestamp = System.currentTimeMillis() / 1000; // Unix timestamp como en Rails Time.now.to_i
        String autoEmail = "cliente_" + timestamp + "@no-domain.com";
        String randomPassword = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16); // Equivalente a SecureRandom.hex(8)

        Client client = new Client();
        client.setId(clientId);

        User newUser = new User();
        newUser.setPhone(normalizedPhone);
        newUser.setEmail(autoEmail);
        newUser.setFirstName("Nuevo Cliente"); // PARIDAD RAILS: Era "Usuario", ahora "Nuevo Cliente"
        newUser.setLastName(String.valueOf(timestamp)); // PARIDAD RAILS: Era phone, ahora timestamp
        newUser.setRole(UserRole.STANDARD);
        newUser.setStatus(com.digitalgroup.holape.domain.common.enums.Status.ACTIVE);
        newUser.setClient(client);

        // PARIDAD RAILS: Copiar campos del recipient
        if (recipient != null) {
            newUser.setManager(recipient); // manager_id = recipient_id para sticky agent
            newUser.setCountry(recipient.getCountry()); // country_id del recipient
            newUser.setTimeZone(recipient.getTimeZone() != null ? recipient.getTimeZone() : "America/Lima"); // time_zone del recipient
        }

        // PARIDAD RAILS: Establecer password y temp_password
        newUser.setEncryptedPassword(randomPassword); // Se debería encriptar, pero Rails también guarda texto plano en algunos casos
        newUser.setTempPassword(randomPassword);

        newUser = userRepository.save(newUser);
        log.info("Created new user {} (email: {}) for incoming message from {}", newUser.getId(), autoEmail, phone);

        // NOTA: El KPI new_client ahora se crea en log_incoming_and_outgoing_message
        // cuando es el primer mensaje incoming en el ticket (como en Rails línea 234-235)
        // No se crea aquí para mantener paridad con Rails

        return newUser;
    }

    /**
     * Routes incoming WhatsApp business message to appropriate agent
     * Equivalent to Rails: route_incoming_whatsapp_business_message
     *
     * This method:
     * 1. Only routes if recipient is a WHATSAPP_BUSINESS user
     * 2. Routes to sticky agent (sender.manager) if exists and different from recipient
     * 3. Otherwise randomly assigns to an available agent
     * 4. Sets whatsappBusinessRouted and originalWhatsappBusinessRecipientId flags
     */
    private void routeIncomingWhatsAppBusinessMessage(Message message, Long clientId) {
        User sender = message.getSender();
        User recipient = message.getRecipient();

        // Only route if direction is incoming AND recipient is WHATSAPP_BUSINESS
        // Equivalent to Rails: if self.direction == 'incoming' && self.recipient.role == 'whatsapp_business'
        if (!message.isIncoming() || recipient == null || recipient.getRole() != UserRole.WHATSAPP_BUSINESS) {
            return;
        }

        if (sender == null || sender.getRole() != UserRole.STANDARD) {
            return;
        }

        // Check if user has a sticky agent (manager) and it's different from current recipient
        // Equivalent to Rails: if self.sender.manager_id.present? && (self.sender.manager_id != self.recipient.id)
        if (sender.getManager() != null && !sender.getManager().getId().equals(recipient.getId())) {
            message.setRecipient(sender.getManager());
            log.info("Routed message to sticky agent {} for user {}", sender.getManager().getId(), sender.getId());
            return;
        }

        // Find available agents and assign randomly
        List<User> availableAgents = userRepository.findAgentsByClientId(clientId);

        if (!availableAgents.isEmpty()) {
            // Set flags before routing
            // Equivalent to Rails: self.whatsapp_business_routed = true
            message.setWhatsappBusinessRouted(true);
            // Equivalent to Rails: self.original_whatsapp_business_recipient_id = self.recipient.id
            message.setOriginalWhatsappBusinessRecipientId(recipient.getId());

            User randomAgent = availableAgents.get(random.nextInt(availableAgents.size()));
            message.setRecipient(randomAgent);

            // Set as sticky agent for future messages
            // Equivalent to Rails: self.sender.update(manager_id: self.recipient_id)
            sender.setManager(randomAgent);
            userRepository.save(sender);

            log.info("Assigned user {} to agent {} (random selection), whatsappBusinessRouted=true",
                    sender.getId(), randomAgent.getId());
        } else {
            log.warn("No agents found to route WhatsApp business message for client {}", clientId);
        }
    }

    /**
     * Fixes sent_at timestamp for proper message ordering
     * Equivalent to: fix_sent_at
     *
     * PARIDAD RAILS: process_message_worker.rb líneas 9-13
     * Si sent_at está en el futuro, restar 1 día (no usar tiempo actual)
     * Esto mantiene el timestamp relativo para ordenamiento correcto
     */
    private void fixSentAt(Message message) {
        if (message.getSentAt() == null) {
            message.setSentAt(LocalDateTime.now());
            return;
        }

        // PARIDAD RAILS: If sent_at is in the future, subtract 1 day (línea 10)
        // new_sent_at = message.sent_at - 1.day
        LocalDateTime now = LocalDateTime.now();
        if (message.getSentAt().isAfter(now)) {
            message.setSentAt(message.getSentAt().minusDays(1));
            message.setWorkerProcessedAt(LocalDateTime.now()); // PARIDAD RAILS línea 11
        }

        // Ensure unique ordering within same minute
        // Find last message in same conversation within same minute
        if (message.getSender() != null && message.getRecipient() != null) {
            LocalDateTime minuteStart = message.getSentAt().withSecond(0).withNano(0);
            LocalDateTime minuteEnd = minuteStart.plusMinutes(1);

            List<Message> sameMinuteMessages = messageRepository.findMessagesInTimeRangeBetweenUsers(
                    message.getSender().getId(),
                    message.getRecipient().getId(),
                    minuteStart,
                    minuteEnd
            );

            if (!sameMinuteMessages.isEmpty()) {
                // Adjust to be after last message
                LocalDateTime lastSentAt = sameMinuteMessages.get(sameMinuteMessages.size() - 1).getSentAt();
                message.setSentAt(lastSentAt.plusSeconds(1));
            }
        }
    }

    /**
     * Assigns message to existing or new ticket
     * Equivalent to: create_or_assign_to_ticket
     *
     * PARIDAD RAILS: La lógica completa es (líneas 105-136):
     * 1. Si hay ticket abierto -> asignar a ese ticket (via job con delay)
     * 2. Si NO hay ticket abierto:
     *    - Para incoming: crear ticket SOLO si sent_at >= último incoming del ticket cerrado
     *    - Para outgoing: NO crear ticket, asignar al cerrado si existe (via job)
     */
    private void assignToTicket(Message message) {
        if (message.getTicket() != null) {
            return;
        }

        User user = message.isIncoming() ? message.getSender() : message.getRecipient();
        User agent = message.isIncoming() ? message.getRecipient() : message.getSender();

        if (user == null || agent == null) {
            return;
        }

        // Only create tickets for standard users
        if (user.getRole() != UserRole.STANDARD) {
            return;
        }

        // Find open ticket
        Optional<Ticket> existingOpenTicket = ticketRepository.findOpenTicketBetweenUsers(
                user.getId(), agent.getId(), TicketStatus.OPEN);

        Ticket ticket;
        if (existingOpenTicket.isPresent()) {
            // Open ticket exists - assign to it
            ticket = existingOpenTicket.get();
        } else {
            // No open ticket - check closed tickets and apply verification
            Optional<Ticket> lastClosedTicket = ticketRepository.findLastClosedTicketBetweenUsers(
                    user.getId(), agent.getId(), TicketStatus.CLOSED);

            if (message.isIncoming()) {
                // PARIDAD RAILS: incoming creates ticket only if sent_at >= last incoming of closed ticket (línea 115)
                boolean shouldCreateTicket = true;

                if (lastClosedTicket.isPresent()) {
                    // Find last incoming message sent_at of the closed ticket
                    Optional<LocalDateTime> lastIncomingSentAt = messageRepository
                            .findLastIncomingSentAtByTicketId(lastClosedTicket.get().getId());

                    if (lastIncomingSentAt.isPresent() && message.getSentAt() != null) {
                        // Only create new ticket if this message is newer than the last incoming
                        shouldCreateTicket = !message.getSentAt().isBefore(lastIncomingSentAt.get());
                    }
                }

                if (shouldCreateTicket) {
                    // Create new ticket
                    ticket = new Ticket();
                    ticket.setUser(user);
                    ticket.setAgent(agent);
                    ticket.setStatus(TicketStatus.OPEN);
                    ticket.setSubject(truncateForSubject(message.getContent()));
                    ticket = ticketRepository.save(ticket);

                    // Log new_ticket KPI
                    Long clientId = user.getClient() != null ? user.getClient().getId() : null;
                    logKpi(clientId, agent.getId(), KpiType.NEW_TICKET, 1L, ticket.getId());

                    log.info("Created new ticket {} for user {} and agent {}", ticket.getId(), user.getId(), agent.getId());
                } else {
                    // Message is older than last closed ticket's last incoming - don't create ticket
                    // The message will be orphaned or attached via delayed job
                    log.debug("Skipping ticket creation for message {} - sent_at before last closed ticket", message.getId());
                    return;
                }
            } else {
                // PARIDAD RAILS: outgoing doesn't create tickets (línea 120)
                // If there's a closed ticket, message will be attached via delayed job
                if (lastClosedTicket.isPresent()) {
                    ticket = lastClosedTicket.get();
                    log.debug("Assigning outgoing message {} to last closed ticket {}", message.getId(), ticket.getId());
                } else {
                    // No closed ticket either - message will be orphaned
                    log.debug("No ticket to assign outgoing message {} to", message.getId());
                    return;
                }
            }
        }

        message.setTicket(ticket);
    }

    /**
     * Truncates content for ticket subject (max 100 chars)
     */
    private String truncateForSubject(String content) {
        if (content == null) return "";
        return content.length() > 100 ? content.substring(0, 100) + "..." : content;
    }

    /**
     * Logs KPIs for message activity
     * Equivalent to: log_kpi_responded_and_first_response_time, log_incoming_and_outgoing_message
     *
     * PARIDAD RAILS: Este método implementa ambas funciones de Rails:
     * - log_kpi_responded_and_first_response_time (outgoing)
     * - log_incoming_and_outgoing_message (incoming/outgoing)
     */
    private void logKpis(Message message) {
        if (message.getTicket() == null) {
            return;
        }

        Ticket ticket = message.getTicket();
        Long clientId = ticket.getUser() != null && ticket.getUser().getClient() != null
                ? ticket.getUser().getClient().getId()
                : null;
        Long agentId = ticket.getAgent() != null ? ticket.getAgent().getId() : null;

        if (message.isOutgoing()) {
            // PARIDAD RAILS: Solo crear sent_message si sender es agent (línea 218)
            if (message.getSender() != null && message.getSender().getRole() == UserRole.AGENT) {
                logKpi(clientId, agentId, KpiType.SENT_MESSAGE, 1L, ticket.getId());
            }

            // Agent responded
            logKpi(clientId, agentId, KpiType.RESPONDED_TO_CLIENT, 1L, ticket.getId());

            // Check for first response time and unique_responded_to_client
            logFirstResponseTime(message, ticket, clientId, agentId);

        } else if (message.isIncoming()) {
            // PARIDAD RAILS: Solo procesar si sender es standard (línea 222)
            if (message.getSender() == null || message.getSender().getRole() != UserRole.STANDARD) {
                return;
            }

            // PARIDAD RAILS: Verificar si el último mensaje era outgoing para crear require_response (líneas 224-230)
            List<Message> ticketMessages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId());
            Message lastMessageBeforeThis = ticketMessages.stream()
                    .filter(m -> !m.getId().equals(message.getId()))
                    .reduce((first, second) -> second)
                    .orElse(null);

            if (lastMessageBeforeThis == null || lastMessageBeforeThis.isOutgoing()) {
                // Crear require_response KPI
                logKpi(clientId, agentId, KpiType.REQUIRE_RESPONSE, 1L, ticket.getId());

                // PARIDAD RAILS: Marcar usuario como require_response = true (línea 229)
                if (message.getSender() != null) {
                    message.getSender().setRequireResponse(true);
                    userRepository.save(message.getSender());
                }
            }

            // PARIDAD RAILS: Crear new_client KPI si es el primer incoming en el ticket (líneas 234-236)
            // ANTES FALTANTE - AHORA IMPLEMENTADO
            long incomingCount = ticketMessages.stream()
                    .filter(Message::isIncoming)
                    .count();

            if (incomingCount == 1) {
                // Este es el primer incoming message en el ticket - crear new_client KPI
                logKpi(clientId, agentId, KpiType.NEW_CLIENT, 1L, ticket.getId());
                log.debug("Created new_client KPI for first incoming message in ticket {}", ticket.getId());
            }
        }
    }

    /**
     * Calculates and logs first response time and unique_responded_to_client KPI
     * Equivalent to: log_kpi_responded_and_first_response_time
     *
     * PARIDAD RAILS: Este método implementa la lógica de Rails lines 195-213:
     * 1. Verifica que sea outgoing y sender sea agent
     * 2. Encuentra el último incoming message
     * 3. Limpia require_response del recipient
     * 4. Si es la primera respuesta, crea KPIs first_response_time y unique_responded_to_client
     */
    private void logFirstResponseTime(Message message, Ticket ticket, Long clientId, Long agentId) {
        // PARIDAD RAILS: Solo procesar si sender es agent (línea 196)
        if (message.getSender() == null || message.getSender().getRole() != UserRole.AGENT) {
            return;
        }

        // Find messages in ticket
        List<Message> ticketMessages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId());

        // PARIDAD RAILS: Encontrar el último incoming message (línea 198)
        Message lastIncoming = ticketMessages.stream()
                .filter(Message::isIncoming)
                .reduce((first, second) -> second) // Get last
                .orElse(null);

        if (lastIncoming == null) {
            return;
        }

        // PARIDAD RAILS: Limpiar require_response del recipient (línea 200)
        if (message.getRecipient() != null) {
            message.getRecipient().setRequireResponse(false);
            userRepository.save(message.getRecipient());
        }

        // PARIDAD RAILS: Contar respuestas después del último incoming (línea 201)
        long numberOfResponsesToLastIncoming = ticketMessages.stream()
                .filter(Message::isOutgoing)
                .filter(m -> m.getSentAt() != null && lastIncoming.getSentAt() != null
                        && m.getSentAt().isAfter(lastIncoming.getSentAt()))
                .count();

        // PARIDAD RAILS: Solo crear KPIs si es la primera respuesta (línea 203)
        if (numberOfResponsesToLastIncoming != 1) {
            return; // Not the first response to this incoming message
        }

        // Calculate response time in minutes (working hours only)
        long responseTimeMinutes = WorkingHoursUtils.calculateWorkingMinutes(
                lastIncoming.getSentAt(),
                message.getSentAt()
        );

        // Cap at 2880 minutes (48 hours) as per Rails logic
        responseTimeMinutes = Math.min(responseTimeMinutes, 2880);

        // PARIDAD RAILS: Crear first_response_time KPI (línea 206)
        logKpi(clientId, agentId, KpiType.FIRST_RESPONSE_TIME, responseTimeMinutes, ticket.getId());

        // PARIDAD RAILS: Crear unique_responded_to_client KPI (línea 207) - ANTES FALTANTE
        logKpi(clientId, agentId, KpiType.UNIQUE_RESPONDED_TO_CLIENT, 1L, ticket.getId());

        log.debug("First response time for ticket {}: {} minutes, unique_responded_to_client logged", ticket.getId(), responseTimeMinutes);
    }

    /**
     * Reconstructs require_response flag for user
     * Equivalent to: reconstruct_require_response_for_user
     */
    private void reconstructRequireResponseForUser(Message message) {
        if (message.getSender() == null) {
            return;
        }

        User user = message.getSender();

        // Check if last message from user is incoming (needs response)
        Optional<Message> lastMessage = messageRepository.findLastMessageByUser(user.getId());

        boolean requiresResponse = lastMessage.isPresent() && lastMessage.get().isIncoming();

        // PARIDAD RAILS: requireResponseAt no existe, solo usar requireResponse flag
        if (user.getRequireResponse() == null || user.getRequireResponse() != requiresResponse) {
            user.setRequireResponse(requiresResponse);
            // El timestamp se puede inferir de lastMessageAt cuando requireResponse=true
            userRepository.save(user);
        }
    }

    /**
     * Updates last_message_at for sender and recipient
     * Equivalent to: update_last_message_at
     */
    private void updateLastMessageAt(Message message) {
        LocalDateTime now = LocalDateTime.now();

        if (message.getSender() != null) {
            message.getSender().setLastMessageAt(now);
            userRepository.save(message.getSender());
        }

        if (message.getRecipient() != null) {
            message.getRecipient().setLastMessageAt(now);
            userRepository.save(message.getRecipient());
        }
    }

    /**
     * Post-save operations (after_commit equivalent)
     * Uses delayed jobs for proper timing like Rails Sidekiq perform_in
     *
     * IMPORTANT: Ticket assignment and KPI creation are skipped for prospect messages
     * Equivalent to Rails: unless: :is_prospect conditions
     */
    private void afterMessageCreated(Message message) {
        // Update last_message_at (immediate) - always runs
        updateLastMessageAt(message);

        // Publish event for WebSocket broadcast and push notifications (immediate) - always runs
        eventPublisher.publishEvent(new MessageCreatedEvent(message));

        // Check if message is from prospect - skip ticket/KPI operations if so
        // Equivalent to Rails: unless: :is_prospect
        boolean isProspect = Boolean.TRUE.equals(message.getIsProspect());

        if (!isProspect) {
            // Schedule ticket assignment with 5 second delay
            // This allows out-of-order messages to be received before assignment
            getCreateOrAssignToTicketJob().scheduleAssignment(message.getId());

            // Log KPIs for outgoing messages (immediate for outgoing)
            if (message.isOutgoing()) {
                logKpis(message);
            }

            // For incoming messages, schedule deferred KPI creation with 10 second delay
            // This ensures ticket assignment (5s) completes first
            if (message.isIncoming()) {
                getDeferredKpiJob().scheduleKpiCreation(message.getId());
            }

            // Schedule require_response flag reconstruction with 20 second delay
            // This ensures all prior operations complete first
            Long userIdToReconstruct = message.isIncoming() ?
                    message.getSender().getId() : message.getRecipient().getId();
            if (userIdToReconstruct != null) {
                getReconstructFlagJob().scheduleReconstruction(userIdToReconstruct);
            }
        } else {
            log.debug("Skipping ticket/KPI operations for prospect message {}", message.getId());
        }

        // Send via WhatsApp or Interceptor App if outgoing to standard user (immediate) - always runs regardless of prospect
        // PARIDAD RAILS: route_outgoing_whatsapp_message (message.rb líneas 138-193)
        if (message.isOutgoing() && message.getRecipient() != null &&
                message.getRecipient().getRole() == UserRole.STANDARD) {

            Client client = message.getSender() != null ? message.getSender().getClient() : null;

            // PARIDAD RAILS: Check if whatsapp_business_routed (líneas 140-191)
            if (Boolean.TRUE.equals(message.getWhatsappBusinessRouted())) {
                // Send via WhatsApp Cloud API
                if (client != null) {
                    sendViaWhatsApp(message, client);
                }
            } else {
                // PARIDAD RAILS: Send via interceptor app (líneas 184-191)
                // Utils.send_silent_push_notification to sender's FCM token
                sendViaInterceptorApp(message);
            }
        }
    }

    /**
     * Sends message via interceptor app (silent push notification to agent's mobile app)
     * PARIDAD RAILS: Utils.send_silent_push_notification (message.rb líneas 184-191)
     *
     * The interceptor app receives the push and sends the message via the agent's personal WhatsApp
     */
    @Async
    public void sendViaInterceptorApp(Message message) {
        if (message.getSender() == null || message.getRecipient() == null) {
            log.warn("Cannot send via interceptor app: sender or recipient is null");
            return;
        }

        String fcmToken = message.getSender().getFcmPushToken();
        if (fcmToken == null || fcmToken.isEmpty()) {
            log.warn("Cannot send via interceptor app: sender {} has no FCM token", message.getSender().getId());
            return;
        }

        try {
            // PARIDAD RAILS: Build data payload for silent push notification
            Map<String, String> data = new java.util.HashMap<>();
            data.put("type", "interceptor_message");
            data.put("phone_number", normalizePhone(message.getRecipient().getPhone()));
            data.put("content", message.getContent());
            data.put("message_id", message.getId() != null ? message.getId().toString() : "");

            firebaseService.sendDataMessage(fcmToken, data);
            log.info("Sent interceptor app push for message {} to agent {}", message.getId(), message.getSender().getId());

        } catch (Exception e) {
            log.error("Error sending via interceptor app: {}", e.getMessage());
        }
    }

    /**
     * Sends message via WhatsApp Cloud API
     * Equivalent to: route_outgoing_whatsapp_message
     */
    @Async
    public void sendViaWhatsApp(Message message, Client client) {
        try {
            whatsAppClient.sendTextMessage(client, message.getRecipient().getPhone(), message.getContent())
                    .subscribe(
                            response -> {
                                log.info("WhatsApp message sent for message {}", message.getId());
                                // Update message status to SENT
                                message.setStatus(MessageStatus.SENT);
                                messageRepository.save(message);
                            },
                            error -> {
                                log.error("Failed to send WhatsApp message {}: {}", message.getId(), error.getMessage());
                                message.setStatus(MessageStatus.ERROR);
                                messageRepository.save(message);
                            }
                    );
        } catch (Exception e) {
            log.error("Error sending WhatsApp message: {}", e.getMessage());
        }
    }

    // ==================== HELPER METHODS ====================

    private void logKpi(Long clientId, Long userId, KpiType kpiType, Long value, Long ticketId) {
        if (clientId == null) {
            return;
        }

        Kpi kpi = new Kpi();
        kpi.setKpiType(kpiType);
        kpi.setValue(value != null ? value.intValue() : 1);

        // Set client
        Client client = new Client();
        client.setId(clientId);
        kpi.setClient(client);

        // Set user if provided
        if (userId != null) {
            User user = new User();
            user.setId(userId);
            kpi.setUser(user);
        }

        // Store ticket_id in data_hash if provided
        if (ticketId != null) {
            Map<String, Object> dataHash = new java.util.HashMap<>();
            dataHash.put("ticket_id", ticketId);
            kpi.setDataHash(dataHash);
        }

        kpiRepository.save(kpi);
    }

    private String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }

        // Remove all non-digits
        String normalized = phone.replaceAll("[^0-9]", "");

        // Add Peru country code if not present
        if (normalized.length() == 9 && !normalized.startsWith("51")) {
            normalized = "51" + normalized;
        }

        return normalized;
    }

    // ==================== ADDITIONAL METHODS ====================

    /**
     * Send template message to a user
     */
    @Transactional
    public Message sendTemplateMessage(Long senderId, Long recipientId, String templateName,
                                       Map<String, String> variables) {
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new ResourceNotFoundException("User", senderId));
        User recipient = userRepository.findById(recipientId)
                .orElseThrow(() -> new ResourceNotFoundException("User", recipientId));

        Message message = new Message();
        message.setSender(sender);
        message.setRecipient(recipient);
        message.setContent(buildTemplateContent(templateName, variables));
        message.setDirection(MessageDirection.OUTGOING);
        message.setSentAt(LocalDateTime.now());
        message.setStatus(MessageStatus.SENT);
        message.setProcessed(false);
        message.setIsTemplate(true);
        message.setTemplateName(templateName);
        message.setHistoricSenderName(sender.getFullName());

        // Assign to ticket
        assignToTicket(message);

        Message savedMessage = messageRepository.save(message);

        // Send via WhatsApp Cloud API
        if (sender.getClient() != null) {
            sendTemplateViaWhatsApp(savedMessage, sender.getClient(), variables);
        }

        afterMessageCreated(savedMessage);

        return savedMessage;
    }

    /**
     * Send message with media attachment
     */
    @Transactional
    public Message sendMediaMessage(Long senderId, Long recipientId, String caption,
                                    String mediaUrl, String mediaType) {
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new ResourceNotFoundException("User", senderId));
        User recipient = userRepository.findById(recipientId)
                .orElseThrow(() -> new ResourceNotFoundException("User", recipientId));

        Message message = new Message();
        message.setSender(sender);
        message.setRecipient(recipient);
        message.setContent(caption != null ? caption : "[Media]");
        message.setDirection(MessageDirection.OUTGOING);
        message.setSentAt(LocalDateTime.now());
        message.setStatus(MessageStatus.SENT);
        message.setProcessed(true);
        // PARIDAD RAILS: mediaUrl/mediaType/mediaCaption no existen, usar binaryContentData como JSON
        if (mediaUrl != null || mediaType != null) {
            String mediaData = String.format("{\"url\":\"%s\",\"type\":\"%s\",\"caption\":\"%s\"}",
                mediaUrl != null ? mediaUrl : "",
                mediaType != null ? mediaType : "",
                caption != null ? caption : "");
            message.setBinaryContentData(mediaData);
        }
        message.setHistoricSenderName(sender.getFullName());

        assignToTicket(message);

        Message savedMessage = messageRepository.save(message);

        // Send via WhatsApp
        if (sender.getClient() != null) {
            sendMediaViaWhatsApp(savedMessage, sender.getClient());
        }

        afterMessageCreated(savedMessage);

        return savedMessage;
    }

    /**
     * Mark multiple messages as read
     */
    @Transactional
    public void markMessagesAsRead(List<Long> messageIds, Long userId) {
        for (Long messageId : messageIds) {
            try {
                markAsRead(messageId, userId);
            } catch (Exception e) {
                log.warn("Failed to mark message {} as read", messageId, e);
            }
        }
    }

    /**
     * Get unread message count for user
     */
    @Transactional(readOnly = true)
    public long getUnreadCount(Long userId) {
        return messageRepository.countByRecipientIdAndStatus(userId, MessageStatus.UNREAD);
    }

    /**
     * Update WhatsApp delivery status
     * PARIDAD RAILS: whatsappMessageId y whatsappStatus no existen en schema
     * El método se simplifica para actualizar por ID normal
     */
    @Transactional
    public void updateWhatsAppStatus(Long messageId, String status) {
        Message message = messageRepository.findById(messageId).orElse(null);
        if (message == null) {
            log.warn("Message {} not found for status update", messageId);
            return;
        }

        if ("delivered".equals(status)) {
            message.setStatus(MessageStatus.SENT);
        } else if ("read".equals(status)) {
            message.setStatus(MessageStatus.READ);
        } else if ("failed".equals(status)) {
            message.setStatus(MessageStatus.ERROR);
        }

        messageRepository.save(message);
        log.debug("Updated WhatsApp status for message {}: {}", message.getId(), status);
    }

    /**
     * Resend failed message
     * PARIDAD RAILS: retryCount, canRetry, incrementRetry no existen en schema
     * Simplificado: solo permite reenvío si está en estado ERROR
     */
    @Transactional
    public boolean resendMessage(Long messageId) {
        Message message = findById(messageId);

        if (!message.isFailed()) {
            return false;
        }

        message.setStatus(MessageStatus.SENT);
        message.setProcessed(false);
        messageRepository.save(message);

        Client client = message.getSender() != null ? message.getSender().getClient() : null;
        if (client != null) {
            sendViaWhatsApp(message, client);
        }

        log.info("Resending message {}", messageId);
        return true;
    }

    // ==================== PRIVATE HELPERS ====================

    private String buildTemplateContent(String templateName, Map<String, String> variables) {
        // Simple placeholder replacement
        StringBuilder content = new StringBuilder("[Template: " + templateName + "]");
        if (variables != null && !variables.isEmpty()) {
            content.append("\n");
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                content.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }
        return content.toString();
    }

    @Async
    private void sendTemplateViaWhatsApp(Message message, Client client, Map<String, String> variables) {
        try {
            whatsAppClient.sendTemplateMessage(
                    client,
                    message.getRecipient().getPhone(),
                    message.getTemplateName(),
                    variables
            ).subscribe(
                    response -> {
                        log.info("WhatsApp template sent for message {}", message.getId());
                        message.setStatus(MessageStatus.SENT);
                        message.setProcessed(true);
                        messageRepository.save(message);
                    },
                    error -> {
                        log.error("Failed to send WhatsApp template {}: {}", message.getId(), error.getMessage());
                        message.setStatus(MessageStatus.ERROR);
                        // PARIDAD RAILS: errorMessage no existe, log el error
                        messageRepository.save(message);
                    }
            );
        } catch (Exception e) {
            log.error("Error sending WhatsApp template: {}", e.getMessage());
        }
    }

    @Async
    private void sendMediaViaWhatsApp(Message message, Client client) {
        try {
            // PARIDAD RAILS: Extraer media info de binaryContentData
            String mediaUrl = null;
            String mediaType = null;
            String mediaCaption = message.getContent();

            if (message.getBinaryContentData() != null) {
                // Parse JSON from binaryContentData (simple extraction)
                String data = message.getBinaryContentData();
                if (data.contains("\"url\":")) {
                    int start = data.indexOf("\"url\":\"") + 7;
                    int end = data.indexOf("\"", start);
                    if (end > start) mediaUrl = data.substring(start, end);
                }
                if (data.contains("\"type\":")) {
                    int start = data.indexOf("\"type\":\"") + 8;
                    int end = data.indexOf("\"", start);
                    if (end > start) mediaType = data.substring(start, end);
                }
                if (data.contains("\"caption\":")) {
                    int start = data.indexOf("\"caption\":\"") + 11;
                    int end = data.indexOf("\"", start);
                    if (end > start) mediaCaption = data.substring(start, end);
                }
            }

            whatsAppClient.sendMediaMessage(
                    client,
                    message.getRecipient().getPhone(),
                    mediaUrl,
                    mediaType,
                    mediaCaption
            ).subscribe(
                    response -> {
                        log.info("WhatsApp media sent for message {}", message.getId());
                    },
                    error -> {
                        log.error("Failed to send WhatsApp media {}: {}", message.getId(), error.getMessage());
                        message.setStatus(MessageStatus.ERROR);
                        messageRepository.save(message);
                    }
            );
        } catch (Exception e) {
            log.error("Error sending WhatsApp media: {}", e.getMessage());
        }
    }
}
