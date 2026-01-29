package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.repository.ClientRepository;
import com.digitalgroup.holape.domain.common.enums.TemplateWhatsAppStatus;
import com.digitalgroup.holape.domain.message.entity.MessageTemplate;
import com.digitalgroup.holape.domain.message.repository.MessageTemplateRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.exception.BusinessException;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import com.digitalgroup.holape.integration.whatsapp.WhatsAppCloudApiClient;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Template Bulk Send Controller
 * Equivalent to Rails Admin::TemplateBulkSendsController
 * Handles bulk sending of WhatsApp template messages
 */
@Slf4j
@RestController
@RequestMapping("/app/template_bulk_sends")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2')")
public class TemplateBulkSendController {

    private final MessageTemplateRepository messageTemplateRepository;
    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final WhatsAppCloudApiClient whatsAppClient;

    // In-memory store for bulk send jobs (in production, use Redis or database)
    private final Map<String, BulkSendJob> activeJobs = new ConcurrentHashMap<>();

    /**
     * List available templates for bulk send
     */
    @GetMapping("/templates")
    public ResponseEntity<Map<String, Object>> getTemplates(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        List<MessageTemplate> templates = messageTemplateRepository
                .findByClientIdAndTemplateWhatsappStatus(currentUser.getClientId(), TemplateWhatsAppStatus.APPROVED);

        List<Map<String, Object>> data = templates.stream()
                .map(t -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", t.getId());
                    map.put("name", t.getName());
                    map.put("category", t.getCategory() != null ? t.getCategory() : 0);
                    map.put("language", t.getLanguage() != null ? t.getLanguage().getLanguageCode() : "es");
                    map.put("header_content", t.getHeaderContent() != null ? t.getHeaderContent() : "");
                    map.put("body_content", t.getBodyContent() != null ? t.getBodyContent() : "");
                    map.put("footer_content", t.getFooterContent() != null ? t.getFooterContent() : "");
                    return map;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("templates", data));
    }

    /**
     * Get recipients for bulk send
     */
    @GetMapping("/recipients")
    public ResponseEntity<Map<String, Object>> getRecipients(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<User> usersPage;

        if ("with_phone".equals(filter)) {
            usersPage = userRepository.findByClient_Id(currentUser.getClientId(), pageable);
            // Filter to only users with phone numbers
            List<User> filtered = usersPage.getContent().stream()
                    .filter(u -> u.getPhone() != null && !u.getPhone().isEmpty())
                    .toList();
        } else {
            usersPage = userRepository.findByClient_Id(currentUser.getClientId(), pageable);
        }

        List<Map<String, Object>> data = usersPage.getContent().stream()
                .filter(u -> u.getPhone() != null && !u.getPhone().isEmpty())
                .map(u -> Map.<String, Object>of(
                        "id", u.getId(),
                        "name", u.getFullName(),
                        "phone", u.getPhone(),
                        "email", u.getEmail() != null ? u.getEmail() : ""
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "recipients", data,
                "total", usersPage.getTotalElements()
        ));
    }

    /**
     * Preview template message
     */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(
            @RequestBody PreviewRequest request) {

        MessageTemplate template = messageTemplateRepository.findById(request.templateId())
                .orElseThrow(() -> new ResourceNotFoundException("MessageTemplate", request.templateId()));

        // Build preview message with variables replaced
        String previewContent = buildTemplateContent(template, request.variables());

        return ResponseEntity.ok(Map.of(
                "template_name", template.getName(),
                "preview", previewContent,
                "language", template.getLanguage() != null ? template.getLanguage().getLanguageCode() : "es"
        ));
    }

    /**
     * Start bulk send job
     */
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> startBulkSend(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody BulkSendRequest request) {

        MessageTemplate template = messageTemplateRepository.findById(request.templateId())
                .orElseThrow(() -> new ResourceNotFoundException("MessageTemplate", request.templateId()));

        if (template.getTemplateWhatsappStatus() != TemplateWhatsAppStatus.APPROVED) {
            throw new BusinessException("Template must be approved to send");
        }

        if (request.recipientIds() == null || request.recipientIds().isEmpty()) {
            throw new BusinessException("At least one recipient is required");
        }

        // Create job
        String jobId = UUID.randomUUID().toString();
        BulkSendJob job = new BulkSendJob(
                jobId,
                template.getId(),
                request.recipientIds(),
                request.variables(),
                currentUser.getId(),
                currentUser.getClientId()
        );
        activeJobs.put(jobId, job);

        // Start async processing
        processBulkSendAsync(job);

        log.info("Started bulk send job {} for {} recipients", jobId, request.recipientIds().size());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "job_id", jobId,
                "total_recipients", request.recipientIds().size(),
                "message", "Bulk send started. Use job_id to check progress."
        ));
    }

    /**
     * Get bulk send job status
     */
    @GetMapping("/status/{jobId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String jobId) {
        BulkSendJob job = activeJobs.get(jobId);

        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
                "job_id", jobId,
                "status", job.status,
                "total", job.totalRecipients,
                "sent", job.sentCount,
                "failed", job.failedCount,
                "progress_percent", job.getProgressPercent(),
                "started_at", job.startedAt,
                "completed_at", job.completedAt != null ? job.completedAt : "",
                "errors", job.errors
        ));
    }

    /**
     * List recent bulk send jobs
     */
    @GetMapping("/jobs")
    public ResponseEntity<Map<String, Object>> listJobs(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        List<Map<String, Object>> jobs = activeJobs.values().stream()
                .filter(j -> j.clientId.equals(currentUser.getClientId()))
                .sorted((a, b) -> b.startedAt.compareTo(a.startedAt))
                .limit(20)
                .map(j -> Map.<String, Object>of(
                        "job_id", j.jobId,
                        "status", j.status,
                        "total", j.totalRecipients,
                        "sent", j.sentCount,
                        "failed", j.failedCount,
                        "started_at", j.startedAt
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("jobs", jobs));
    }

    /**
     * Cancel bulk send job
     */
    @PostMapping("/cancel/{jobId}")
    public ResponseEntity<Map<String, Object>> cancelJob(@PathVariable String jobId) {
        BulkSendJob job = activeJobs.get(jobId);

        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        job.cancelled = true;
        job.status = "CANCELLED";

        log.info("Cancelled bulk send job {}", jobId);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", "Job cancelled"
        ));
    }

    @Async
    protected void processBulkSendAsync(BulkSendJob job) {
        job.status = "PROCESSING";

        MessageTemplate template = messageTemplateRepository.findById(job.templateId).orElse(null);
        if (template == null) {
            job.status = "FAILED";
            job.errors.add("Template not found");
            return;
        }

        Client client = clientRepository.findById(job.clientId).orElse(null);
        if (client == null) {
            job.status = "FAILED";
            job.errors.add("Client not found");
            return;
        }

        String languageCode = template.getLanguage() != null ? template.getLanguage().getLanguageCode() : "es";

        for (Long recipientId : job.recipientIds) {
            if (job.cancelled) {
                break;
            }

            try {
                User recipient = userRepository.findById(recipientId).orElse(null);
                if (recipient == null || recipient.getPhone() == null) {
                    job.failedCount++;
                    job.errors.add("Recipient " + recipientId + " not found or has no phone");
                    continue;
                }

                // Send template message via WhatsApp
                whatsAppClient.sendTemplateMessage(
                        client,
                        recipient.getPhone(),
                        template.getName(),
                        languageCode,
                        buildTemplateComponents(template, job.variables, recipient)
                ).block();

                job.sentCount++;

                // Small delay to avoid rate limiting
                Thread.sleep(100);

            } catch (Exception e) {
                job.failedCount++;
                job.errors.add("Failed for recipient " + recipientId + ": " + e.getMessage());
                log.error("Bulk send error for recipient {}: {}", recipientId, e.getMessage());
            }
        }

        job.status = job.cancelled ? "CANCELLED" : "COMPLETED";
        job.completedAt = LocalDateTime.now();

        log.info("Bulk send job {} completed: {} sent, {} failed",
                job.jobId, job.sentCount, job.failedCount);
    }

    private String buildTemplateContent(MessageTemplate template, Map<String, String> variables) {
        StringBuilder content = new StringBuilder();

        if (template.getHeaderContent() != null && !template.getHeaderContent().isEmpty()) {
            content.append("[Header] ").append(template.getHeaderContent()).append("\n");
        }
        if (template.getBodyContent() != null && !template.getBodyContent().isEmpty()) {
            content.append(template.getBodyContent());
        }
        if (template.getFooterContent() != null && !template.getFooterContent().isEmpty()) {
            content.append("\n[Footer] ").append(template.getFooterContent());
        }

        String result = content.toString();
        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
        }
        return result;
    }

    private List<Map<String, Object>> buildTemplateComponents(
            MessageTemplate template,
            Map<String, String> variables,
            User recipient) {

        List<Map<String, Object>> components = new ArrayList<>();

        // Replace user-specific variables
        Map<String, String> resolvedVars = new HashMap<>(variables != null ? variables : Map.of());
        resolvedVars.put("first_name", recipient.getFirstName() != null ? recipient.getFirstName() : "");
        resolvedVars.put("last_name", recipient.getLastName() != null ? recipient.getLastName() : "");
        resolvedVars.put("full_name", recipient.getFullName() != null ? recipient.getFullName() : "");

        // Build body component with parameters
        if (!resolvedVars.isEmpty()) {
            List<Map<String, Object>> parameters = resolvedVars.entrySet().stream()
                    .map(e -> Map.<String, Object>of(
                            "type", "text",
                            "text", e.getValue()
                    ))
                    .collect(Collectors.toList());

            components.add(Map.of(
                    "type", "body",
                    "parameters", parameters
            ));
        }

        return components;
    }

    // Inner classes for job tracking
    private static class BulkSendJob {
        String jobId;
        Long templateId;
        List<Long> recipientIds;
        Map<String, String> variables;
        Long userId;
        Long clientId;
        String status = "PENDING";
        int totalRecipients;
        int sentCount = 0;
        int failedCount = 0;
        LocalDateTime startedAt;
        LocalDateTime completedAt;
        List<String> errors = new ArrayList<>();
        volatile boolean cancelled = false;

        BulkSendJob(String jobId, Long templateId, List<Long> recipientIds,
                    Map<String, String> variables, Long userId, Long clientId) {
            this.jobId = jobId;
            this.templateId = templateId;
            this.recipientIds = recipientIds;
            this.variables = variables;
            this.userId = userId;
            this.clientId = clientId;
            this.totalRecipients = recipientIds.size();
            this.startedAt = LocalDateTime.now();
        }

        int getProgressPercent() {
            if (totalRecipients == 0) return 100;
            return (int) ((sentCount + failedCount) * 100.0 / totalRecipients);
        }
    }

    public record PreviewRequest(Long templateId, Map<String, String> variables) {}
    public record BulkSendRequest(Long templateId, List<Long> recipientIds, Map<String, String> variables) {}
}
