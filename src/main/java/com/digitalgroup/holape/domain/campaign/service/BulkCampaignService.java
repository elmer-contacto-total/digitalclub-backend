package com.digitalgroup.holape.domain.campaign.service;

import com.digitalgroup.holape.domain.campaign.entity.BulkCampaign;
import com.digitalgroup.holape.domain.campaign.entity.BulkCampaignRecipient;
import com.digitalgroup.holape.domain.campaign.entity.BulkSendRule;
import com.digitalgroup.holape.domain.campaign.repository.BulkCampaignRecipientRepository;
import com.digitalgroup.holape.domain.campaign.repository.BulkCampaignRepository;
import com.digitalgroup.holape.domain.campaign.repository.BulkSendRuleRepository;
import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.repository.ClientRepository;
import com.digitalgroup.holape.domain.message.entity.BulkMessage;
import com.digitalgroup.holape.domain.message.entity.MessageTemplate;
import com.digitalgroup.holape.domain.message.repository.BulkMessageRepository;
import com.digitalgroup.holape.domain.message.repository.MessageTemplateRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.exception.BusinessException;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import com.digitalgroup.holape.integration.whatsapp.WhatsAppCloudApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * BulkCampaignService
 * Handles campaign creation, async Cloud API sending, and Electron polling
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkCampaignService {

    private final BulkCampaignRepository campaignRepository;
    private final BulkCampaignRecipientRepository recipientRepository;
    private final BulkSendRuleRepository ruleRepository;
    private final BulkMessageRepository bulkMessageRepository;
    private final MessageTemplateRepository messageTemplateRepository;
    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final WhatsAppCloudApiClient whatsAppClient;

    // Track paused/cancelled campaigns by id
    private final Set<Long> pausedCampaigns = ConcurrentHashMap.newKeySet();
    private final Set<Long> cancelledCampaigns = ConcurrentHashMap.newKeySet();

    /**
     * Create a new campaign with recipients
     */
    @Transactional
    public BulkCampaign createCampaign(Long userId, Long clientId, String sendMethod,
                                        Long bulkMessageId, Long messageTemplateId,
                                        List<Long> recipientIds) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        Client client = user.getClient();
        if (client == null || !client.getId().equals(clientId)) {
            client = clientRepository.findById(clientId)
                    .orElseThrow(() -> new ResourceNotFoundException("Client", clientId));
        }

        // Validate send method
        if (!"CLOUD_API".equals(sendMethod) && !"ELECTRON".equals(sendMethod)) {
            throw new BusinessException("send_method must be CLOUD_API or ELECTRON");
        }

        // Validate message source
        BulkMessage bulkMessage = null;
        MessageTemplate messageTemplate = null;

        if ("CLOUD_API".equals(sendMethod)) {
            if (messageTemplateId == null) {
                throw new BusinessException("message_template_id is required for Cloud API campaigns");
            }
            messageTemplate = messageTemplateRepository.findById(messageTemplateId)
                    .orElseThrow(() -> new ResourceNotFoundException("MessageTemplate", messageTemplateId));
            if (!messageTemplate.isApproved()) {
                throw new BusinessException("Template must be approved to send");
            }
        } else {
            if (bulkMessageId == null) {
                throw new BusinessException("bulk_message_id is required for Electron campaigns");
            }
            bulkMessage = bulkMessageRepository.findById(bulkMessageId)
                    .orElseThrow(() -> new ResourceNotFoundException("BulkMessage", bulkMessageId));
        }

        if (recipientIds == null || recipientIds.isEmpty()) {
            throw new BusinessException("At least one recipient is required");
        }

        // Check daily limit
        BulkSendRule rules = getOrCreateRules(clientId);
        long sentToday = campaignRepository.sumSentByUserSince(userId,
                LocalDateTime.of(LocalDate.now(), LocalTime.MIN));
        if (sentToday + recipientIds.size() > rules.getMaxDailyMessages()) {
            throw new BusinessException("Daily message limit (" + rules.getMaxDailyMessages()
                    + ") would be exceeded. Already sent " + sentToday + " today.");
        }

        // Create campaign
        BulkCampaign campaign = BulkCampaign.builder()
                .client(client)
                .user(user)
                .bulkMessage(bulkMessage)
                .messageTemplate(messageTemplate)
                .sendMethod(sendMethod)
                .status("PENDING")
                .totalRecipients(recipientIds.size())
                .build();

        campaign = campaignRepository.save(campaign);

        // Create recipients
        final BulkCampaign savedCampaign = campaign;
        List<BulkCampaignRecipient> recipients = recipientIds.stream()
                .map(recipientId -> {
                    User recipient = userRepository.findById(recipientId).orElse(null);
                    if (recipient == null || recipient.getPhone() == null || recipient.getPhone().isBlank()) {
                        return null;
                    }
                    return BulkCampaignRecipient.builder()
                            .campaign(savedCampaign)
                            .user(recipient)
                            .phone(recipient.getPhone())
                            .status("PENDING")
                            .build();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        recipientRepository.saveAll(recipients);

        // Update actual recipient count (some may have been skipped)
        campaign.setTotalRecipients(recipients.size());
        campaignRepository.save(campaign);

        log.info("Created campaign {} with {} recipients via {}", campaign.getId(), recipients.size(), sendMethod);

        return campaign;
    }

    /**
     * Start processing a Cloud API campaign asynchronously
     */
    @Async("taskExecutor")
    public void processCloudApiCampaign(Long campaignId) {
        BulkCampaign campaign = campaignRepository.findById(campaignId).orElse(null);
        if (campaign == null) {
            log.error("Campaign {} not found for processing", campaignId);
            return;
        }

        campaign.setStatus("PROCESSING");
        campaign.setStartedAt(LocalDateTime.now());
        campaignRepository.save(campaign);

        Client client = campaign.getClient();
        MessageTemplate template = campaign.getMessageTemplate();
        BulkSendRule rules = getOrCreateRules(client.getId());

        if (template == null) {
            campaign.setStatus("FAILED");
            campaign.setErrorSummary("Template not found");
            campaignRepository.save(campaign);
            return;
        }

        String languageCode = template.getLanguage() != null ? template.getLanguage().getLanguageCode() : "es";
        List<BulkCampaignRecipient> pendingRecipients = recipientRepository
                .findByCampaignIdAndStatus(campaignId, "PENDING");

        int consecutiveFailures = 0;

        for (int i = 0; i < pendingRecipients.size(); i++) {
            // Check pause/cancel
            if (cancelledCampaigns.contains(campaignId)) {
                campaign.setStatus("CANCELLED");
                campaign.setCompletedAt(LocalDateTime.now());
                campaignRepository.save(campaign);
                cancelledCampaigns.remove(campaignId);
                log.info("Campaign {} cancelled", campaignId);
                return;
            }

            if (pausedCampaigns.contains(campaignId)) {
                campaign.setStatus("PAUSED");
                campaignRepository.save(campaign);
                log.info("Campaign {} paused at recipient {}/{}", campaignId, i, pendingRecipients.size());
                return;
            }

            BulkCampaignRecipient recipient = pendingRecipients.get(i);

            try {
                // Build components for template
                User recipientUser = recipient.getUser();
                List<Map<String, Object>> components = buildTemplateComponents(template, recipientUser);

                whatsAppClient.sendTemplateMessage(
                        client,
                        recipient.getPhone(),
                        template.getName(),
                        languageCode,
                        components
                ).block();

                recipient.markSent();
                recipientRepository.save(recipient);
                campaign.incrementSent();
                consecutiveFailures = 0;

                // Rate limiting
                Thread.sleep(rules.getCloudApiDelayMs());

            } catch (Exception e) {
                recipient.markFailed(e.getMessage());
                recipientRepository.save(recipient);
                campaign.incrementFailed();
                consecutiveFailures++;

                log.error("Campaign {} - failed for recipient {} ({}): {}",
                        campaignId, recipient.getId(), recipient.getPhone(), e.getMessage());

                // Backoff: 3 consecutive → double delay, 5 → auto-pause
                if (consecutiveFailures >= 5) {
                    campaign.setStatus("PAUSED");
                    campaign.setErrorSummary("Auto-paused after 5 consecutive failures");
                    campaignRepository.save(campaign);
                    log.warn("Campaign {} auto-paused after 5 consecutive failures", campaignId);
                    return;
                }
                if (consecutiveFailures >= 3) {
                    try {
                        Thread.sleep(rules.getCloudApiDelayMs() * 2L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            // Save progress periodically
            if (i % 10 == 0) {
                campaignRepository.save(campaign);
            }
        }

        campaign.setStatus("COMPLETED");
        campaign.setCompletedAt(LocalDateTime.now());
        campaignRepository.save(campaign);

        log.info("Campaign {} completed: {} sent, {} failed",
                campaignId, campaign.getSentCount(), campaign.getFailedCount());
    }

    /**
     * Pause a campaign
     */
    public void pauseCampaign(Long campaignId) {
        BulkCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkCampaign", campaignId));

        if (!"PROCESSING".equals(campaign.getStatus())) {
            throw new BusinessException("Only processing campaigns can be paused");
        }

        pausedCampaigns.add(campaignId);
    }

    /**
     * Resume a paused campaign
     */
    public void resumeCampaign(Long campaignId) {
        BulkCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkCampaign", campaignId));

        if (!"PAUSED".equals(campaign.getStatus())) {
            throw new BusinessException("Only paused campaigns can be resumed");
        }

        pausedCampaigns.remove(campaignId);

        if (campaign.isCloudApi()) {
            processCloudApiCampaign(campaignId);
        } else {
            // For Electron, just update status — client will resume polling
            campaign.setStatus("PROCESSING");
            campaignRepository.save(campaign);
        }
    }

    /**
     * Cancel a campaign
     */
    public void cancelCampaign(Long campaignId) {
        BulkCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkCampaign", campaignId));

        if ("COMPLETED".equals(campaign.getStatus()) || "CANCELLED".equals(campaign.getStatus())) {
            throw new BusinessException("Campaign is already " + campaign.getStatus().toLowerCase());
        }

        if ("PROCESSING".equals(campaign.getStatus())) {
            cancelledCampaigns.add(campaignId);
        } else {
            campaign.setStatus("CANCELLED");
            campaign.setCompletedAt(LocalDateTime.now());
            campaignRepository.save(campaign);
        }
    }

    /**
     * Get next pending recipient for Electron polling
     */
    @Transactional
    public Optional<BulkCampaignRecipient> getNextRecipient(Long campaignId) {
        BulkCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkCampaign", campaignId));

        if (!"PROCESSING".equals(campaign.getStatus()) && !"PENDING".equals(campaign.getStatus())) {
            return Optional.empty();
        }

        // Start campaign if still pending
        if ("PENDING".equals(campaign.getStatus())) {
            campaign.setStatus("PROCESSING");
            campaign.setStartedAt(LocalDateTime.now());
            campaignRepository.save(campaign);
        }

        return recipientRepository.findFirstByCampaignIdAndStatusOrderByIdAsc(campaignId, "PENDING");
    }

    /**
     * Report result for a recipient (Electron polling)
     */
    @Transactional
    public void reportRecipientResult(Long campaignId, Long recipientId, boolean success, String errorMessage) {
        BulkCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkCampaign", campaignId));

        BulkCampaignRecipient recipient = recipientRepository.findById(recipientId)
                .orElseThrow(() -> new ResourceNotFoundException("BulkCampaignRecipient", recipientId));

        if (!recipient.getCampaign().getId().equals(campaignId)) {
            throw new BusinessException("Recipient does not belong to this campaign");
        }

        if (success) {
            recipient.markSent();
            campaign.incrementSent();
        } else {
            recipient.markFailed(errorMessage);
            campaign.incrementFailed();
        }

        recipientRepository.save(recipient);

        // Check if campaign is complete
        long pendingCount = recipientRepository.countByCampaignIdAndStatus(campaignId, "PENDING");
        if (pendingCount == 0) {
            campaign.setStatus("COMPLETED");
            campaign.setCompletedAt(LocalDateTime.now());
        }

        campaignRepository.save(campaign);
    }

    /**
     * Get or create default rules for a client
     */
    public BulkSendRule getOrCreateRules(Long clientId) {
        return ruleRepository.findByClientId(clientId)
                .orElseGet(() -> {
                    Client client = clientRepository.findById(clientId)
                            .orElseThrow(() -> new ResourceNotFoundException("Client", clientId));
                    BulkSendRule defaults = BulkSendRule.builder()
                            .client(client)
                            .build();
                    return ruleRepository.save(defaults);
                });
    }

    /**
     * Update rules for a client
     */
    @Transactional
    public BulkSendRule updateRules(Long clientId, Map<String, Object> updates) {
        BulkSendRule rules = getOrCreateRules(clientId);

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
        if (updates.containsKey("cloud_api_delay_ms"))
            rules.setCloudApiDelayMs((Integer) updates.get("cloud_api_delay_ms"));
        if (updates.containsKey("enabled"))
            rules.setEnabled((Boolean) updates.get("enabled"));

        return ruleRepository.save(rules);
    }

    private List<Map<String, Object>> buildTemplateComponents(MessageTemplate template, User recipient) {
        List<Map<String, Object>> components = new ArrayList<>();

        Map<String, String> vars = new HashMap<>();
        vars.put("first_name", recipient.getFirstName() != null ? recipient.getFirstName() : "");
        vars.put("last_name", recipient.getLastName() != null ? recipient.getLastName() : "");
        vars.put("full_name", recipient.getFullName() != null ? recipient.getFullName() : "");

        if (!vars.isEmpty()) {
            List<Map<String, Object>> parameters = vars.entrySet().stream()
                    .map(e -> Map.<String, Object>of("type", "text", "text", e.getValue()))
                    .collect(Collectors.toList());

            components.add(Map.of("type", "body", "parameters", parameters));
        }

        return components;
    }
}
