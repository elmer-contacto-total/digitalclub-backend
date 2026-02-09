package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.campaign.entity.BulkCampaign;
import com.digitalgroup.holape.domain.campaign.entity.BulkCampaignRecipient;
import com.digitalgroup.holape.domain.campaign.entity.BulkSendRule;
import com.digitalgroup.holape.domain.campaign.repository.BulkCampaignRecipientRepository;
import com.digitalgroup.holape.domain.campaign.repository.BulkCampaignRepository;
import com.digitalgroup.holape.domain.campaign.service.BulkCampaignService;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Bulk Campaign Controller
 * Manages mass sending campaigns via Cloud API or Electron
 */
@Slf4j
@RestController
@RequestMapping("/app/campaigns")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4', 'AGENT')")
public class BulkCampaignController {

    private final BulkCampaignService campaignService;
    private final BulkCampaignRepository campaignRepository;
    private final BulkCampaignRecipientRepository recipientRepository;

    /**
     * Create a new campaign
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody CreateCampaignRequest request) {

        BulkCampaign campaign = campaignService.createCampaign(
                currentUser.getId(),
                currentUser.getClientId(),
                request.sendMethod(),
                request.bulkMessageId(),
                request.messageTemplateId(),
                request.recipientIds()
        );

        // Auto-start Cloud API campaigns
        if (campaign.isCloudApi()) {
            campaignService.processCloudApiCampaign(campaign.getId());
        }

        log.info("Campaign {} created by user {} via {}", campaign.getId(), currentUser.getId(), request.sendMethod());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "campaign", mapCampaignToResponse(campaign),
                "message", campaign.isCloudApi()
                        ? "Campaign started. Cloud API sending in progress."
                        : "Campaign created. Start sending from the desktop app."
        ));
    }

    /**
     * List campaigns
     * Agents see only their own; supervisors/admins see all for client
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> index(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<BulkCampaign> campaignsPage;

        boolean isSupervisor = currentUser.isAdmin() || currentUser.isManager();

        if (currentUser.isSuperAdmin()) {
            campaignsPage = campaignRepository.findAll(pageable);
        } else if (isSupervisor) {
            if (status != null && !status.isBlank()) {
                campaignsPage = campaignRepository.findByClientIdAndStatusOrderByCreatedAtDesc(
                        currentUser.getClientId(), status.toUpperCase(), pageable);
            } else {
                campaignsPage = campaignRepository.findByClientIdOrderByCreatedAtDesc(
                        currentUser.getClientId(), pageable);
            }
        } else {
            // Agent: only own campaigns
            campaignsPage = campaignRepository.findByUserIdOrderByCreatedAtDesc(
                    currentUser.getId(), pageable);
        }

        List<Map<String, Object>> data = campaignsPage.getContent().stream()
                .map(this::mapCampaignToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "campaigns", data,
                "total", campaignsPage.getTotalElements(),
                "page", page,
                "totalPages", campaignsPage.getTotalPages()
        ));
    }

    /**
     * Get campaign detail with recipients
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> show(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "0") int recipientPage,
            @RequestParam(required = false, defaultValue = "50") int recipientSize) {

        BulkCampaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BulkCampaign", id));

        Pageable pageable = PageRequest.of(recipientPage, recipientSize);
        Page<BulkCampaignRecipient> recipientsPage = recipientRepository.findByCampaignId(id, pageable);

        List<Map<String, Object>> recipientData = recipientsPage.getContent().stream()
                .map(this::mapRecipientToResponse)
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>(mapCampaignToResponse(campaign));
        response.put("recipients", recipientData);
        response.put("recipients_total", recipientsPage.getTotalElements());
        response.put("recipients_page", recipientPage);
        response.put("recipients_total_pages", recipientsPage.getTotalPages());

        return ResponseEntity.ok(response);
    }

    /**
     * Pause a campaign in progress
     */
    @PostMapping("/{id}/pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable Long id) {
        campaignService.pauseCampaign(id);
        return ResponseEntity.ok(Map.of("result", "success", "message", "Campaign paused"));
    }

    /**
     * Resume a paused campaign
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable Long id) {
        campaignService.resumeCampaign(id);
        return ResponseEntity.ok(Map.of("result", "success", "message", "Campaign resumed"));
    }

    /**
     * Cancel a campaign
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long id) {
        campaignService.cancelCampaign(id);
        return ResponseEntity.ok(Map.of("result", "success", "message", "Campaign cancelled"));
    }

    /**
     * Get next pending recipient for Electron polling
     */
    @GetMapping("/{id}/next-recipient")
    public ResponseEntity<Map<String, Object>> nextRecipient(@PathVariable Long id) {
        Optional<BulkCampaignRecipient> next = campaignService.getNextRecipient(id);

        if (next.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "has_next", false,
                    "message", "No more pending recipients"
            ));
        }

        BulkCampaignRecipient recipient = next.get();
        BulkCampaign campaign = recipient.getCampaign();

        Map<String, Object> response = new HashMap<>();
        response.put("has_next", true);
        response.put("recipient_id", recipient.getId());
        response.put("phone", recipient.getPhone());
        response.put("user_name", recipient.getUser() != null ? recipient.getUser().getFullName() : "");

        // Include message content
        if (campaign.getBulkMessage() != null) {
            response.put("content", campaign.getBulkMessage().getMessage());
        } else if (campaign.getMessageTemplate() != null) {
            response.put("content", campaign.getMessageTemplate().getBodyContent());
            response.put("template_name", campaign.getMessageTemplate().getName());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Report result for a recipient (Electron)
     */
    @PostMapping("/{id}/recipient-result")
    public ResponseEntity<Map<String, Object>> recipientResult(
            @PathVariable Long id,
            @RequestBody RecipientResultRequest request) {

        campaignService.reportRecipientResult(id, request.recipientId(), request.success(), request.errorMessage());

        BulkCampaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BulkCampaign", id));

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "campaign_status", campaign.getStatus(),
                "sent_count", campaign.getSentCount(),
                "failed_count", campaign.getFailedCount(),
                "progress_percent", campaign.getProgressPercent()
        ));
    }

    /**
     * Get send rules for current client (supervisors only)
     */
    @GetMapping("/rules")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4')")
    public ResponseEntity<Map<String, Object>> getRules(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        BulkSendRule rules = campaignService.getOrCreateRules(currentUser.getClientId());

        return ResponseEntity.ok(Map.of("rules", mapRulesToResponse(rules)));
    }

    /**
     * Update send rules (supervisors only)
     */
    @PutMapping("/rules")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4')")
    public ResponseEntity<Map<String, Object>> updateRules(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody Map<String, Object> request) {

        BulkSendRule rules = campaignService.updateRules(currentUser.getClientId(), request);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "rules", mapRulesToResponse(rules)
        ));
    }

    // --- Mapping helpers ---

    private Map<String, Object> mapCampaignToResponse(BulkCampaign campaign) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", campaign.getId());
        map.put("send_method", campaign.getSendMethod());
        map.put("status", campaign.getStatus());
        map.put("total_recipients", campaign.getTotalRecipients());
        map.put("sent_count", campaign.getSentCount());
        map.put("failed_count", campaign.getFailedCount());
        map.put("progress_percent", campaign.getProgressPercent());
        map.put("started_at", campaign.getStartedAt());
        map.put("completed_at", campaign.getCompletedAt());
        map.put("error_summary", campaign.getErrorSummary());
        map.put("created_at", campaign.getCreatedAt());
        map.put("updated_at", campaign.getUpdatedAt());
        map.put("client_id", campaign.getClient() != null ? campaign.getClient().getId() : null);
        map.put("user_id", campaign.getUser() != null ? campaign.getUser().getId() : null);
        map.put("user_name", campaign.getUser() != null ? campaign.getUser().getFullName() : null);

        if (campaign.getBulkMessage() != null) {
            map.put("bulk_message_id", campaign.getBulkMessage().getId());
            map.put("message_preview", truncate(campaign.getBulkMessage().getMessage(), 100));
        }
        if (campaign.getMessageTemplate() != null) {
            map.put("message_template_id", campaign.getMessageTemplate().getId());
            map.put("template_name", campaign.getMessageTemplate().getName());
            map.put("message_preview", truncate(campaign.getMessageTemplate().getBodyContent(), 100));
        }

        return map;
    }

    private Map<String, Object> mapRecipientToResponse(BulkCampaignRecipient recipient) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", recipient.getId());
        map.put("phone", recipient.getPhone());
        map.put("status", recipient.getStatus());
        map.put("sent_at", recipient.getSentAt());
        map.put("error_message", recipient.getErrorMessage());
        map.put("user_id", recipient.getUser() != null ? recipient.getUser().getId() : null);
        map.put("user_name", recipient.getUser() != null ? recipient.getUser().getFullName() : null);
        return map;
    }

    private Map<String, Object> mapRulesToResponse(BulkSendRule rules) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", rules.getId());
        map.put("max_daily_messages", rules.getMaxDailyMessages());
        map.put("min_delay_seconds", rules.getMinDelaySeconds());
        map.put("max_delay_seconds", rules.getMaxDelaySeconds());
        map.put("pause_after_count", rules.getPauseAfterCount());
        map.put("pause_duration_minutes", rules.getPauseDurationMinutes());
        map.put("send_hour_start", rules.getSendHourStart());
        map.put("send_hour_end", rules.getSendHourEnd());
        map.put("cloud_api_delay_ms", rules.getCloudApiDelayMs());
        map.put("enabled", rules.getEnabled());
        return map;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    // --- Request DTOs ---

    public record CreateCampaignRequest(
            String sendMethod,
            Long bulkMessageId,
            Long messageTemplateId,
            List<Long> recipientIds
    ) {}

    public record RecipientResultRequest(
            Long recipientId,
            boolean success,
            String errorMessage
    ) {}
}
