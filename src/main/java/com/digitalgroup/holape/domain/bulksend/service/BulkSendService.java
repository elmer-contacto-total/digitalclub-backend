package com.digitalgroup.holape.domain.bulksend.service;

import com.digitalgroup.holape.domain.bulksend.entity.BulkSend;
import com.digitalgroup.holape.domain.bulksend.entity.BulkSendRecipient;
import com.digitalgroup.holape.domain.bulksend.entity.BulkSendRule;
import com.digitalgroup.holape.domain.bulksend.repository.BulkSendRecipientRepository;
import com.digitalgroup.holape.domain.bulksend.repository.BulkSendRepository;
import com.digitalgroup.holape.domain.bulksend.repository.BulkSendRuleRepository;
import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.repository.ClientRepository;
import com.digitalgroup.holape.domain.common.enums.UserRole;
import com.digitalgroup.holape.domain.message.entity.BulkMessage;
import com.digitalgroup.holape.domain.message.repository.BulkMessageRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.exception.BusinessException;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import com.digitalgroup.holape.websocket.WebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;


@Slf4j
@Service
@RequiredArgsConstructor
public class BulkSendService {

    private final BulkSendRepository bulkSendRepository;
    private final BulkSendRecipientRepository recipientRepository;
    private final BulkSendRuleRepository ruleRepository;
    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final BulkMessageRepository bulkMessageRepository;
    private final WebSocketService webSocketService;

    private void broadcastUpdate(BulkSend bs) {
        if (bs.getClient() == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("bulk_send_id", bs.getId());
        data.put("status", bs.getStatus());
        data.put("sent_count", bs.getSentCount());
        data.put("failed_count", bs.getFailedCount());
        data.put("total_recipients", bs.getTotalRecipients());
        data.put("progress_percent", bs.getProgressPercent());
        webSocketService.sendBulkSendUpdate(bs.getClient().getId(), data);
    }


    /**
     * Create a bulk send from CSV data
     */
    @Transactional
    public BulkSend createFromCsv(Long userId, Long clientId, String messageContent,
                                   String attachmentPath, String attachmentType,
                                   Long attachmentSize, String attachmentOriginalName,
                                   List<CsvRecipientDTO> csvRecipients,
                                   Long assignedAgentId) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        Client client = user.getClient();
        if (client == null || !client.getId().equals(clientId)) {
            client = clientRepository.findById(clientId)
                    .orElseThrow(() -> new ResourceNotFoundException("Client", clientId));
        }

        if (messageContent == null || messageContent.isBlank()) {
            throw new BusinessException("El contenido del mensaje es requerido");
        }

        if (csvRecipients == null || csvRecipients.isEmpty()) {
            throw new BusinessException("Se requiere al menos un destinatario");
        }

        // Resolve assigned agent (default to creator if not specified)
        User assignedAgent;
        if (assignedAgentId != null && !assignedAgentId.equals(userId)) {
            assignedAgent = userRepository.findById(assignedAgentId)
                    .orElseThrow(() -> new ResourceNotFoundException("User (assigned agent)", assignedAgentId));
            // Validate agent belongs to same client
            if (assignedAgent.getClient() == null || !assignedAgent.getClient().getId().equals(clientId)) {
                throw new BusinessException("El agente asignado no pertenece al mismo cliente");
            }
        } else {
            assignedAgent = user;
        }

        // Check daily limit for assigned agent
        BulkSendRule rules = getOrCreateRules(clientId, userId);
        long sentToday = bulkSendRepository.sumSentByAssignedAgentSince(assignedAgent.getId(),
                LocalDateTime.of(LocalDate.now(), LocalTime.MIN));
        if (sentToday + csvRecipients.size() > rules.getMaxDailyMessages()) {
            throw new BusinessException("Límite diario de mensajes (" + rules.getMaxDailyMessages()
                    + ") sería excedido. Ya enviados hoy por el agente: " + sentToday);
        }

        // Create bulk send
        BulkSend bulkSend = BulkSend.builder()
                .client(client)
                .user(user)
                .assignedAgent(assignedAgent)
                .sendMethod("ELECTRON")
                .status("PENDING")
                .messageContent(messageContent)
                .attachmentPath(attachmentPath)
                .attachmentType(attachmentType)
                .attachmentSize(attachmentSize)
                .attachmentOriginalName(attachmentOriginalName)
                .totalRecipients(csvRecipients.size())
                .build();

        bulkSend = bulkSendRepository.save(bulkSend);

        // Create recipients from CSV
        final BulkSend savedBulkSend = bulkSend;
        List<BulkSendRecipient> recipients = new ArrayList<>();
        for (CsvRecipientDTO csv : csvRecipients) {
            if (csv.phone() == null || csv.phone().isBlank()) continue;

            BulkSendRecipient recipient = BulkSendRecipient.builder()
                    .bulkSend(savedBulkSend)
                    .phone(csv.phone())
                    .recipientName(csv.name())
                    .customVariables(csv.variables())
                    .status("PENDING")
                    .build();
            recipients.add(recipient);
        }

        recipientRepository.saveAll(recipients);

        // Update actual count (some may have been skipped)
        bulkSend.setTotalRecipients(recipients.size());
        bulkSendRepository.save(bulkSend);

        log.info("Created bulk send {} with {} recipients", bulkSend.getId(), recipients.size());
        return bulkSend;
    }

    /**
     * Pause a bulk send in progress (idempotent — no-op if already paused)
     */
    public void pauseBulkSend(Long bulkSendId) {
        BulkSend bulkSend = bulkSendRepository.findById(bulkSendId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkSend", bulkSendId));

        if ("PAUSED".equals(bulkSend.getStatus())) return; // idempotent
        if (!"PROCESSING".equals(bulkSend.getStatus())) {
            throw new BusinessException("Solo envíos en proceso pueden ser pausados");
        }

        bulkSend.setStatus("PAUSED");
        bulkSendRepository.save(bulkSend);
        broadcastUpdate(bulkSend);
    }

    /**
     * Resume a paused bulk send (idempotent — no-op if already processing)
     */
    @Transactional
    public void resumeBulkSend(Long bulkSendId) {
        BulkSend bulkSend = bulkSendRepository.findById(bulkSendId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkSend", bulkSendId));

        if ("PROCESSING".equals(bulkSend.getStatus())) return; // idempotent
        if (!"PAUSED".equals(bulkSend.getStatus())) {
            throw new BusinessException("Solo envíos pausados pueden ser reanudados");
        }

        // Reset any stuck IN_PROGRESS recipients back to PENDING
        recipientRepository.resetInProgressToPending(bulkSendId);

        bulkSend.setStatus("PROCESSING");
        bulkSendRepository.save(bulkSend);
        broadcastUpdate(bulkSend);
    }

    /**
     * Cancel a bulk send
     */
    public void cancelBulkSend(Long bulkSendId) {
        BulkSend bulkSend = bulkSendRepository.findById(bulkSendId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkSend", bulkSendId));

        if ("COMPLETED".equals(bulkSend.getStatus()) || "CANCELLED".equals(bulkSend.getStatus())) {
            throw new BusinessException("El envío ya está " + bulkSend.getStatus().toLowerCase());
        }

        bulkSend.setStatus("CANCELLED");
        bulkSend.setCompletedAt(LocalDateTime.now());
        bulkSendRepository.save(bulkSend);
        broadcastUpdate(bulkSend);
    }

    /**
     * Get next pending recipient for Electron polling.
     * Returns resolved message content and attachment info.
     */
    @Transactional
    public Optional<Map<String, Object>> getNextRecipient(Long bulkSendId) {
        BulkSend bulkSend = bulkSendRepository.findById(bulkSendId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkSend", bulkSendId));

        if (!"PROCESSING".equals(bulkSend.getStatus()) && !"PENDING".equals(bulkSend.getStatus())) {
            return Optional.empty();
        }

        // Check daily limit for assigned agent
        if (bulkSend.getAssignedAgent() != null && bulkSend.getClient() != null) {
            BulkSendRule rules = getOrCreateRules(bulkSend.getClient().getId(), bulkSend.getUser().getId());
            long sentToday = bulkSendRepository.sumSentByAssignedAgentSince(
                    bulkSend.getAssignedAgent().getId(),
                    LocalDateTime.of(LocalDate.now(), LocalTime.MIN));
            if (sentToday >= rules.getMaxDailyMessages()) {
                log.info("Daily limit reached for agent {} ({}/{})", bulkSend.getAssignedAgent().getId(), sentToday, rules.getMaxDailyMessages());
                return Optional.empty();
            }
        }

        // Start if still pending
        if ("PENDING".equals(bulkSend.getStatus())) {
            bulkSend.setStatus("PROCESSING");
            bulkSend.setStartedAt(LocalDateTime.now());
            bulkSendRepository.save(bulkSend);
        }

        Optional<BulkSendRecipient> next = recipientRepository
                .findNextPendingRecipientForUpdate(bulkSendId);

        if (next.isEmpty()) {
            return Optional.empty();
        }

        BulkSendRecipient recipient = next.get();
        // Mark as IN_PROGRESS atomically within the same transaction (lock held)
        recipient.setStatus("IN_PROGRESS");
        recipientRepository.save(recipient);
        String resolvedContent = recipient.getResolvedContent(bulkSend.getMessageContent());

        Map<String, Object> result = new HashMap<>();
        result.put("recipient_id", recipient.getId());
        result.put("phone", recipient.getPhone());
        result.put("recipient_name", recipient.getRecipientName());
        result.put("content", resolvedContent);

        if (bulkSend.hasAttachment()) {
            result.put("attachment_path", bulkSend.getAttachmentPath());
            result.put("attachment_type", bulkSend.getAttachmentType());
            result.put("attachment_original_name", bulkSend.getAttachmentOriginalName());
        }

        return Optional.of(result);
    }

    /**
     * Report result for a recipient (Electron polling)
     */
    @Transactional
    public void reportRecipientResult(Long bulkSendId, Long recipientId, boolean success, String errorMessage, String action) {
        BulkSend bulkSend = bulkSendRepository.findById(bulkSendId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkSend", bulkSendId));

        BulkSendRecipient recipient = recipientRepository.findById(recipientId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkSendRecipient", recipientId));

        if (!recipient.getBulkSend().getId().equals(bulkSendId)) {
            throw new BusinessException("El destinatario no pertenece a este envío");
        }

        if (success) {
            recipient.markSent();
            bulkSend.incrementSent();
        } else if ("SKIP".equals(action)) {
            recipient.markSkipped(errorMessage);
            bulkSend.incrementFailed();
        } else {
            recipient.markFailed(errorMessage);
            bulkSend.incrementFailed();
        }

        recipientRepository.save(recipient);

        // Check if complete (also consider IN_PROGRESS recipients to avoid premature completion)
        long pendingCount = recipientRepository.countByBulkSendIdAndStatus(bulkSendId, "PENDING");
        long inProgressCount = recipientRepository.countByBulkSendIdAndStatus(bulkSendId, "IN_PROGRESS");
        if (pendingCount == 0 && inProgressCount == 0) {
            bulkSend.setStatus("COMPLETED");
            bulkSend.setCompletedAt(LocalDateTime.now());
        }

        bulkSendRepository.save(bulkSend);
        broadcastUpdate(bulkSend);
    }

    /**
     * Get or create rules for a specific supervisor
     */
    @Transactional
    public BulkSendRule getOrCreateRules(Long clientId, Long userId) {
        return ruleRepository.findByClientIdAndUserId(clientId, userId)
                .orElseGet(() -> {
                    Client client = clientRepository.findById(clientId)
                            .orElseThrow(() -> new ResourceNotFoundException("Client", clientId));
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new ResourceNotFoundException("User", userId));

                    // Fallback: clone client-level rules (user_id IS NULL) if they exist
                    Optional<BulkSendRule> clientDefaults = ruleRepository.findByClientIdAndUserIsNull(clientId);
                    BulkSendRule newRule;
                    if (clientDefaults.isPresent()) {
                        BulkSendRule src = clientDefaults.get();
                        newRule = BulkSendRule.builder()
                                .client(client)
                                .user(user)
                                .maxDailyMessages(src.getMaxDailyMessages())
                                .minDelaySeconds(src.getMinDelaySeconds())
                                .maxDelaySeconds(src.getMaxDelaySeconds())
                                .pauseAfterCount(src.getPauseAfterCount())
                                .pauseDurationMinutes(src.getPauseDurationMinutes())
                                .sendHourStart(src.getSendHourStart())
                                .sendHourEnd(src.getSendHourEnd())
                                .cloudApiDelayMs(src.getCloudApiDelayMs())
                                .enabled(src.getEnabled())
                                .build();
                    } else {
                        newRule = BulkSendRule.builder()
                                .client(client)
                                .user(user)
                                .build();
                    }
                    return ruleRepository.save(newRule);
                });
    }

    /**
     * Update rules for a specific supervisor
     */
    @Transactional
    public BulkSendRule updateRules(Long clientId, Long userId, Map<String, Object> updates) {
        BulkSendRule rules = getOrCreateRules(clientId, userId);
        applyRuleUpdates(rules, updates);
        return ruleRepository.save(rules);
    }

    private void applyRuleUpdates(BulkSendRule rules, Map<String, Object> updates) {
        if (updates.containsKey("max_daily_messages"))
            rules.setMaxDailyMessages((Integer) updates.get("max_daily_messages"));
        if (updates.containsKey("min_delay_seconds"))
            rules.setMinDelaySeconds((Integer) updates.get("min_delay_seconds"));
        if (updates.containsKey("max_delay_seconds"))
            rules.setMaxDelaySeconds((Integer) updates.get("max_delay_seconds"));
        if (updates.containsKey("pause_after_count"))
            rules.setPauseAfterCount((Integer) updates.get("pause_after_count"));
        if (updates.containsKey("pause_duration_minutes"))
            rules.setPauseDurationMinutes((Integer) updates.get("pause_duration_minutes"));
        if (updates.containsKey("send_hour_start"))
            rules.setSendHourStart((Integer) updates.get("send_hour_start"));
        if (updates.containsKey("send_hour_end"))
            rules.setSendHourEnd((Integer) updates.get("send_hour_end"));
        if (updates.containsKey("enabled"))
            rules.setEnabled((Boolean) updates.get("enabled"));
    }

    /**
     * Get list of agents assignable for bulk sends based on current user's role.
     * Admin → all active agents in client
     * Manager → agents under this manager
     * Agent → only themselves
     */
    public List<User> getAssignableAgents(Long currentUserId, Long clientId, UserRole currentRole) {
        if (currentRole == UserRole.SUPER_ADMIN || currentRole == UserRole.ADMIN) {
            return userRepository.findActiveAgentsByClientId(clientId);
        } else if (currentRole.isManager()) {
            return userRepository.findAgentsBySupervisor(currentUserId);
        } else {
            // Agent: only themselves
            return userRepository.findById(currentUserId)
                    .map(List::of)
                    .orElse(List.of());
        }
    }

    /**
     * Find a BulkMessage by ID
     */
    public BulkMessage findBulkMessage(Long id) {
        return bulkMessageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BulkMessage", id));
    }

    /**
     * DTO for CSV recipient data
     */
    public record CsvRecipientDTO(
            String phone,
            String name,
            Map<String, String> variables
    ) {}
}
