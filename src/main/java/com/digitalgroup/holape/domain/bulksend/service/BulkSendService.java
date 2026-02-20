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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


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

    // In-memory WhatsApp rate limit tracking (reported by Electron instances, shared across all)
    private final ConcurrentHashMap<Long, Instant> rateLimitedClients = new ConcurrentHashMap<>();

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

        // Check daily limit for assigned agent (only if rules are enabled)
        BulkSendRule rules = getOrCreateRules(clientId, userId);
        if (Boolean.TRUE.equals(rules.getEnabled())) {
            long sentToday = bulkSendRepository.sumSentByAssignedAgentSince(assignedAgent.getId(),
                    LocalDateTime.of(LocalDate.now(), LocalTime.MIN));
            if (sentToday + csvRecipients.size() > rules.getMaxDailyMessages()) {
                throw new BusinessException("Límite diario de mensajes (" + rules.getMaxDailyMessages()
                        + ") sería excedido. Ya enviados hoy por el agente: " + sentToday);
            }
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

        broadcastUpdate(bulkSend);
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
        if (!"PROCESSING".equals(bulkSend.getStatus()) && !"PERIODIC_PAUSE".equals(bulkSend.getStatus())) {
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
     * Set periodic pause on a bulk send (automatic anti-ban pause)
     */
    public void periodicPauseBulkSend(Long bulkSendId) {
        BulkSend bulkSend = bulkSendRepository.findById(bulkSendId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkSend", bulkSendId));
        if (!"PROCESSING".equals(bulkSend.getStatus())) return;
        bulkSend.setStatus("PERIODIC_PAUSE");
        bulkSendRepository.save(bulkSend);
        broadcastUpdate(bulkSend);
    }

    /**
     * Resume from periodic pause
     */
    public void periodicResumeBulkSend(Long bulkSendId) {
        BulkSend bulkSend = bulkSendRepository.findById(bulkSendId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkSend", bulkSendId));
        if (!"PERIODIC_PAUSE".equals(bulkSend.getStatus())) return;
        bulkSend.setStatus("PROCESSING");
        bulkSendRepository.save(bulkSend);
        broadcastUpdate(bulkSend);
    }

    /**
     * Cancel a bulk send. Marks all pending/in-progress recipients as SKIPPED.
     */
    @Transactional
    public void cancelBulkSend(Long bulkSendId) {
        BulkSend bulkSend = bulkSendRepository.findById(bulkSendId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkSend", bulkSendId));

        if ("COMPLETED".equals(bulkSend.getStatus()) || "CANCELLED".equals(bulkSend.getStatus())) {
            throw new BusinessException("El envío ya está " + bulkSend.getStatus().toLowerCase());
        }

        // Mark pending/in-progress recipients as skipped
        int skipped = recipientRepository.cancelPendingRecipients(bulkSendId);
        if (skipped > 0) {
            bulkSendRepository.atomicAddFailed(bulkSendId, skipped);
        }

        // Re-fetch to get fresh counts after atomic update
        bulkSend = bulkSendRepository.findById(bulkSendId).orElseThrow();
        bulkSend.setStatus("CANCELLED");
        bulkSend.setCompletedAt(LocalDateTime.now());
        bulkSendRepository.save(bulkSend);
        broadcastUpdate(bulkSend);
    }

    /**
     * Get next pending recipient for Electron polling.
     * Returns resolved message content and attachment info.
     * Includes concurrency checks: dedup, active conversation, global rate limit.
     */
    @Transactional
    public Optional<Map<String, Object>> getNextRecipient(Long bulkSendId) {
        BulkSend bulkSend = bulkSendRepository.findById(bulkSendId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkSend", bulkSendId));

        if (!"PROCESSING".equals(bulkSend.getStatus()) && !"PENDING".equals(bulkSend.getStatus())) {
            return Optional.empty();
        }

        Long clientId = bulkSend.getClient().getId();
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);

        // Check WhatsApp rate limit (in-memory, reported by Electron instances)
        if (isRateLimited(clientId)) {
            log.info("Client {} rate-limited by WhatsApp, blocking sends", clientId);
            return Optional.empty();
        }

        // Check per-agent daily limit (only if rules are enabled)
        BulkSendRule rules = null;
        if (bulkSend.getAssignedAgent() != null) {
            rules = getOrCreateRules(clientId, bulkSend.getUser().getId());
            if (Boolean.TRUE.equals(rules.getEnabled())) {
                long sentToday = bulkSendRepository.sumSentByAssignedAgentSince(
                        bulkSend.getAssignedAgent().getId(), todayStart);
                if (sentToday >= rules.getMaxDailyMessages()) {
                    log.info("Daily limit reached for agent {} ({}/{})", bulkSend.getAssignedAgent().getId(), sentToday, rules.getMaxDailyMessages());
                    return Optional.empty();
                }
            }
        }

        // Check global daily limit for the WhatsApp number (= client)
        // Only applies if client-level rule exists (user_id IS NULL) and is enabled
        Optional<BulkSendRule> clientRule = ruleRepository.findByClientIdAndUserIsNull(clientId);
        if (clientRule.isPresent() && Boolean.TRUE.equals(clientRule.get().getEnabled())) {
            long globalSentToday = bulkSendRepository.sumSentByClientSince(clientId, todayStart);
            long globalLimit = clientRule.get().getMaxDailyMessages();
            if (globalSentToday >= globalLimit) {
                log.warn("Global daily limit reached for client {} ({}/{})", clientId, globalSentToday, globalLimit);
                return Optional.empty();
            }
        }

        // Start if still pending
        if ("PENDING".equals(bulkSend.getStatus())) {
            bulkSend.setStatus("PROCESSING");
            bulkSend.setStartedAt(LocalDateTime.now());
            bulkSendRepository.save(bulkSend);
        }

        // Loop: get recipients, skip if concurrency checks fail
        LocalDateTime dedupCutoff = LocalDateTime.now().minusHours(24);
        LocalDateTime activeConvCutoff = LocalDateTime.now().minusMinutes(30);
        int maxSkips = 50;

        for (int i = 0; i < maxSkips; i++) {
            Optional<BulkSendRecipient> next = recipientRepository
                    .findNextPendingRecipientForUpdate(bulkSendId);

            if (next.isEmpty()) {
                // Check if all done
                long pending = recipientRepository.countByBulkSendIdAndStatus(bulkSendId, "PENDING");
                long inProgress = recipientRepository.countByBulkSendIdAndStatus(bulkSendId, "IN_PROGRESS");
                if (pending == 0 && inProgress == 0) {
                    bulkSend.setStatus("COMPLETED");
                    bulkSend.setCompletedAt(LocalDateTime.now());
                    bulkSendRepository.save(bulkSend);
                    broadcastUpdate(bulkSend);
                }
                return Optional.empty();
            }

            BulkSendRecipient recipient = next.get();
            String phone = recipient.getPhone();

            // CHECK 1: Dedup — phone already sent in another bulk send of same client in 24h?
            if (recipientRepository.existsRecentlySentPhone(phone, clientId, bulkSendId, dedupCutoff)) {
                recipient.markSkipped("Ya enviado en otro envío masivo reciente");
                bulkSendRepository.atomicIncrementFailed(bulkSendId);
                recipientRepository.save(recipient);
                continue;
            }

            // CHECK 2: Active conversation — contact has recent messages?
            User contactUser = recipient.getUser();
            if (contactUser == null) {
                contactUser = userRepository.findByPhoneAndClientId(phone, clientId).orElse(null);
            }
            if (contactUser != null && contactUser.getLastMessageAt() != null
                    && contactUser.getLastMessageAt().isAfter(activeConvCutoff)) {
                recipient.markSkipped("Conversación activa - saltado para no interrumpir");
                bulkSendRepository.atomicIncrementFailed(bulkSendId);
                recipientRepository.save(recipient);
                continue;
            }

            // All checks passed — mark IN_PROGRESS and return
            recipient.setStatus("IN_PROGRESS");
            recipientRepository.save(recipient);

            String resolvedContent = recipient.getResolvedContent(bulkSend.getMessageContent());

            Map<String, Object> result = new HashMap<>();
            result.put("recipient_id", recipient.getId());
            result.put("phone", phone);
            result.put("recipient_name", recipient.getRecipientName());
            result.put("content", resolvedContent);

            if (bulkSend.hasAttachment()) {
                result.put("attachment_path", bulkSend.getAttachmentPath());
                result.put("attachment_type", bulkSend.getAttachmentType());
                result.put("attachment_original_name", bulkSend.getAttachmentOriginalName());
            }

            return Optional.of(result);
        }

        log.warn("Max skips reached for bulk send {}", bulkSendId);
        return Optional.empty();
    }

    /**
     * Report result for a recipient (Electron polling).
     * Uses atomic DB increments to avoid race conditions between concurrent agents.
     */
    @Transactional
    public void reportRecipientResult(Long bulkSendId, Long recipientId, boolean success, String errorMessage, String action) {
        BulkSendRecipient recipient = recipientRepository.findById(recipientId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkSendRecipient", recipientId));

        if (!recipient.getBulkSend().getId().equals(bulkSendId)) {
            throw new BusinessException("El destinatario no pertenece a este envío");
        }

        // Skip if recipient already in terminal state (e.g., cancelled while IN_PROGRESS)
        if (!"IN_PROGRESS".equals(recipient.getStatus())) {
            log.info("Recipient {} already in terminal state {}, ignoring result report",
                    recipientId, recipient.getStatus());
            return;
        }

        if (success) {
            recipient.markSent();
            bulkSendRepository.atomicIncrementSent(bulkSendId);
        } else if ("SKIP".equals(action)) {
            recipient.markSkipped(errorMessage);
            bulkSendRepository.atomicIncrementFailed(bulkSendId);
        } else {
            recipient.markFailed(errorMessage);
            bulkSendRepository.atomicIncrementFailed(bulkSendId);
        }

        recipientRepository.save(recipient);

        // Check completion
        long pendingCount = recipientRepository.countByBulkSendIdAndStatus(bulkSendId, "PENDING");
        long inProgressCount = recipientRepository.countByBulkSendIdAndStatus(bulkSendId, "IN_PROGRESS");

        // Re-read fresh entity (clearAutomatically=true on atomic queries clears cache)
        BulkSend bulkSend = bulkSendRepository.findById(bulkSendId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkSend", bulkSendId));

        if (pendingCount == 0 && inProgressCount == 0) {
            bulkSend.setStatus("COMPLETED");
            bulkSend.setCompletedAt(LocalDateTime.now());
            bulkSendRepository.save(bulkSend);
        }

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

    // --- WhatsApp rate limit coordination ---

    public void reportRateLimit(Long clientId, int cooldownMinutes) {
        rateLimitedClients.put(clientId, Instant.now().plusSeconds(cooldownMinutes * 60L));
        log.warn("WhatsApp rate limit reported for client {}, cooldown {} min", clientId, cooldownMinutes);
    }

    public boolean isRateLimited(Long clientId) {
        Instant until = rateLimitedClients.get(clientId);
        if (until == null) return false;
        if (Instant.now().isAfter(until)) {
            rateLimitedClients.remove(clientId);
            return false;
        }
        return true;
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
