package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.message.entity.MessageTemplate;
import com.digitalgroup.holape.domain.message.entity.MessageTemplateParam;
import com.digitalgroup.holape.domain.message.service.MessageTemplateService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Message Template Admin Controller
 * Equivalent to Rails Admin::MessageTemplatesController
 * Manages WhatsApp message templates
 *
 * Aligned with actual MessageTemplate entity:
 * - language is Language entity
 * - category and headerMediaType are Integers
 * - templateWhatsappStatus is the status enum
 * - totButtons is Integer
 */
@Slf4j
@RestController
@RequestMapping("/app/message_templates")
@RequiredArgsConstructor
public class MessageTemplateAdminController {

    private final MessageTemplateService messageTemplateService;

    /**
     * List all message templates
     */
    @Transactional(readOnly = true)
    @GetMapping
    public ResponseEntity<Map<String, Object>> index(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());

        Page<MessageTemplate> templatesPage;
        if (search != null && !search.isBlank()) {
            templatesPage = messageTemplateService.searchByClient(
                    currentUser.getClientId(), search.trim(), pageable);
        } else {
            templatesPage = messageTemplateService.findByClient(
                    currentUser.getClientId(), pageable);
        }

        List<Map<String, Object>> data = templatesPage.getContent().stream()
                .map(this::mapTemplateToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "templates", data,
                "total", templatesPage.getTotalElements(),
                "page", page,
                "totalPages", templatesPage.getTotalPages()
        ));
    }

    /**
     * Get template by ID
     */
    @Transactional(readOnly = true)
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> show(@PathVariable Long id) {
        MessageTemplate template = messageTemplateService.findById(id);
        return ResponseEntity.ok(mapTemplateToResponse(template));
    }

    /**
     * Create new message template
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody CreateTemplateRequest request) {

        MessageTemplate template = messageTemplateService.createTemplate(
                currentUser.getClientId(),
                currentUser.getId(),
                request.name(),
                request.language(),
                request.category(),
                request.headerMediaType(),
                request.headerContent(),
                request.bodyContent(),
                request.footerContent(),
                request.totButtons()
        );

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "template", mapTemplateToResponse(template)
        ));
    }

    /**
     * Update message template
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody UpdateTemplateRequest request) {

        MessageTemplate template = messageTemplateService.updateTemplate(
                id,
                request.headerContent(),
                request.bodyContent(),
                request.footerContent(),
                request.totButtons()
        );

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "template", mapTemplateToResponse(template)
        ));
    }

    /**
     * Update template parameters (dataField, defaultValue)
     * Equivalent to Rails Admin::MessageTemplateParamsController#update
     */
    @PutMapping("/{id}/params")
    public ResponseEntity<Map<String, Object>> updateParams(
            @PathVariable Long id,
            @RequestBody List<UpdateParamRequest> params) {

        List<MessageTemplateService.ParamUpdate> updates = params.stream()
                .map(p -> new MessageTemplateService.ParamUpdate(p.id(), p.dataField(), p.defaultValue()))
                .collect(Collectors.toList());

        MessageTemplate template = messageTemplateService.updateTemplateParams(id, updates);

        List<Map<String, Object>> updatedParams = template.getParams().stream()
                .map(this::mapParamToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "params", updatedParams
        ));
    }

    public record UpdateParamRequest(Long id, String dataField, String defaultValue) {}

    /**
     * Delete message template
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        messageTemplateService.deleteTemplate(id);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", "Template deleted successfully"
        ));
    }

    /**
     * Sync templates with WhatsApp Cloud API
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncWithCloudApi(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        int syncedCount = messageTemplateService.syncWithCloudApi(currentUser.getClientId());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "synced_count", syncedCount,
                "message", String.format("Synced %d templates from WhatsApp Cloud API", syncedCount)
        ));
    }

    /**
     * Submit template for approval
     */
    @PostMapping("/{id}/submit")
    public ResponseEntity<Map<String, Object>> submitForApproval(@PathVariable Long id) {
        MessageTemplate template = messageTemplateService.submitForApproval(id);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "template", mapTemplateToResponse(template),
                "message", "Template submitted for WhatsApp approval"
        ));
    }

    private Map<String, Object> mapTemplateToResponse(MessageTemplate template) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", template.getId());
        map.put("name", template.getName());
        map.put("language", template.getLanguage() != null ? template.getLanguage().getLanguageCode() : null);
        map.put("languageName", template.getLanguage() != null ? template.getLanguage().getName() : null);
        map.put("category", template.getCategory());
        map.put("templateWhatsappType", template.getTemplateWhatsappType());
        map.put("status", template.getTemplateWhatsappStatus() != null ?
                template.getTemplateWhatsappStatus().name().toLowerCase() : null);
        map.put("headerMediaType", template.getHeaderMediaType());
        map.put("headerContent", template.getHeaderContent());
        map.put("bodyContent", template.getBodyContent());
        map.put("footerContent", template.getFooterContent());
        map.put("totButtons", template.getTotButtons());
        map.put("closesTicket", template.getClosesTicket());
        map.put("visibility", template.getVisibility());
        map.put("createdAt", template.getCreatedAt());
        map.put("updatedAt", template.getUpdatedAt());

        // Include parameters if any
        if (template.getParams() != null && !template.getParams().isEmpty()) {
            List<Map<String, Object>> paramsList = template.getParams().stream()
                    .map(this::mapParamToResponse)
                    .collect(Collectors.toList());
            map.put("params", paramsList);

            // Compute paramsStatus: "active" if all params ACTIVE, "pending" otherwise
            boolean allActive = template.getParams().stream()
                    .allMatch(p -> p.getStatus() != null && p.getStatus().isActive());
            map.put("paramsStatus", allActive ? "active" : "pending");
        } else {
            map.put("paramsStatus", "");
        }

        return map;
    }

    private Map<String, Object> mapParamToResponse(MessageTemplateParam param) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", param.getId());
        map.put("component", param.getComponent());
        map.put("position", param.getPosition());
        map.put("dataField", param.getDataField());
        map.put("defaultValue", param.getDefaultValue());
        return map;
    }

    public record CreateTemplateRequest(
            String name,
            String language,
            Integer category,
            Integer headerMediaType,
            String headerContent,
            String bodyContent,
            String footerContent,
            Integer totButtons
    ) {}

    public record UpdateTemplateRequest(
            String headerContent,
            String bodyContent,
            String footerContent,
            Integer totButtons
    ) {}
}
