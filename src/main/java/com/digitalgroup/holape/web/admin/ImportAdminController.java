package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.common.enums.ImportStatus;
import com.digitalgroup.holape.domain.importdata.entity.Import;
import com.digitalgroup.holape.domain.importdata.entity.ImportMappingTemplate;
import com.digitalgroup.holape.domain.importdata.entity.TempImportUser;
import com.digitalgroup.holape.domain.importdata.service.ImportService;
import com.digitalgroup.holape.security.CustomUserDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Import Admin Controller
 * Equivalent to Rails Admin::ImportsController
 * Handles CSV imports for users/prospects
 *
 * PARIDAD RAILS: All authenticated users can access imports.
 * Admin/SuperAdmin see all imports for client; others see only their own.
 */
@Slf4j
@RestController
@RequestMapping("/app/imports")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ImportAdminController {

    private final ImportService importService;
    private final ObjectMapper objectMapper;

    /**
     * List imports for the client
     * PARIDAD RAILS: Admin/SuperAdmin see all; others see only their own
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> index(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, size);

        Page<Import> importsPage;
        if (currentUser.isAdmin()) {
            // Admin and SuperAdmin see all imports for the client
            importsPage = importService.findByClient(currentUser.getClientId(), pageable);
        } else {
            // All other roles see only their own imports
            importsPage = importService.findByClientAndUser(currentUser.getClientId(), currentUser.getId(), pageable);
        }

        List<Map<String, Object>> data = importsPage.getContent().stream()
                .map(this::mapImportToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "imports", data,
                "total", importsPage.getTotalElements(),
                "page", page,
                "totalPages", importsPage.getTotalPages()
        ));
    }

    /**
     * Get import by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> show(@PathVariable Long id) {
        Import importEntity = importService.findById(id);
        return ResponseEntity.ok(mapImportToResponse(importEntity));
    }

    /**
     * Download original CSV file for an import.
     * Downloads from S3 (if enabled) or decodes from stored base64.
     * Always serves bytes directly to avoid CORS issues with S3 presigned URL redirects.
     */
    @GetMapping("/{id}/download")
    @Transactional(readOnly = true)
    public ResponseEntity<?> downloadFile(@PathVariable Long id) {
        Import importEntity = importService.findById(id);
        String fileDataStr = importEntity.getImportFileData();

        if (fileDataStr == null || fileDataStr.isBlank()) {
            return ResponseEntity.notFound().build();
        }

        try {
            // Determine filename from metadata
            String filename = "import_" + id + ".csv";
            var fileData = objectMapper.readTree(fileDataStr);
            var metadata = fileData.path("metadata");
            if (metadata.has("filename")) {
                filename = metadata.get("filename").asText();
            }

            // Serve file content (downloads from S3 or decodes base64)
            byte[] csvContent = importService.retrieveCsvContent(importEntity);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=utf-8")
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"")
                    .body(csvContent);

        } catch (Exception e) {
            log.error("Error downloading file for import {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Failed to download file"));
        }
    }

    /**
     * Create new import from CSV file.
     * Stores the file and returns headers + auto-suggestions for interactive mapping.
     * Does NOT trigger validation — user must confirm mapping via POST /{id}/confirm_mapping.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "user") String importType,
            @RequestParam(required = false) Long assignToUserId) {

        Import importEntity = importService.createImport(
                currentUser.getClientId(),
                currentUser.getId(),
                file,
                importType,
                assignToUserId
        );

        // Get headers + auto-suggestions for the mapping page
        Map<String, Object> mappingData = importService.getHeadersAndSuggestions(importEntity.getId(), false);

        Map<String, Object> response = new HashMap<>();
        response.put("result", "success");
        response.put("import", mapImportToResponse(importEntity));
        response.put("mapping", mappingData);
        response.put("message", "Import created — map columns before validating");
        return ResponseEntity.ok(response);
    }

    /**
     * Create FOH-specific import.
     * Stores the file and returns headers + auto-suggestions for interactive mapping.
     * Does NOT trigger validation — user must confirm mapping via POST /{id}/confirm_mapping.
     */
    @PostMapping("/foh")
    public ResponseEntity<Map<String, Object>> createFoh(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam("file") MultipartFile file) {

        Import importEntity = importService.createFohImport(
                currentUser.getClientId(),
                currentUser.getId(),
                file
        );

        // Get headers + auto-suggestions for the mapping page (isFoh=true)
        Map<String, Object> mappingData = importService.getHeadersAndSuggestions(importEntity.getId(), true);

        Map<String, Object> response = new HashMap<>();
        response.put("result", "success");
        response.put("import", mapImportToResponse(importEntity));
        response.put("mapping", mappingData);
        response.put("message", "FOH import created — map columns before validating");
        return ResponseEntity.ok(response);
    }

    /**
     * Get headers + suggestions for an existing import (e.g. on page reload).
     */
    @GetMapping("/{id}/mapping")
    public ResponseEntity<Map<String, Object>> getMapping(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "false") boolean isFoh) {

        Map<String, Object> mappingData = importService.getHeadersAndSuggestions(id, isFoh);
        return ResponseEntity.ok(mappingData);
    }

    /**
     * Confirm user-provided column mapping and trigger validation.
     * Body: { "columnMapping": {"0": "phone", "2": "first_name", ...}, "isFoh": false }
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/{id}/confirm_mapping")
    public ResponseEntity<Map<String, Object>> confirmMapping(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        Map<String, String> columnMapping = (Map<String, String>) body.get("columnMapping");
        boolean isFoh = Boolean.TRUE.equals(body.get("isFoh"));

        if (columnMapping == null || columnMapping.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "result", "error",
                    "message", "columnMapping is required"
            ));
        }

        importService.confirmMappingAndValidate(id, columnMapping, isFoh);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", "Mapping confirmed — validation started"
        ));
    }

    /**
     * Get import progress (more detailed than status)
     */
    @GetMapping("/{id}/progress")
    public ResponseEntity<Map<String, Object>> progress(@PathVariable Long id) {
        Map<String, Object> progress = importService.getImportProgress(id);
        return ResponseEntity.ok(progress);
    }

    /**
     * Confirm validated import and start processing
     * Equivalent to Rails: Admin::ImportsController#create_import_user
     * Phase F3: Accepts sendInvitationEmail parameter (actual sending deferred to future)
     */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {

        boolean sendInvitationEmail = false;
        if (body != null && body.containsKey("sendInvitationEmail")) {
            sendInvitationEmail = Boolean.TRUE.equals(body.get("sendInvitationEmail"));
        }

        // TODO: Pass sendInvitationEmail to processImport when email sending is implemented
        log.info("Confirming import {} with sendInvitationEmail={}", id, sendInvitationEmail);
        importService.confirmImport(id);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", "Import processing started"
        ));
    }

    /**
     * Get validated users preview before processing — with real pagination and error filter.
     * Equivalent to Rails: Admin::ImportsController#validated_import_user
     * Phase D: Includes unmatchedColumns for interactive column selection
     */
    @GetMapping("/{id}/validated_users")
    public ResponseEntity<Map<String, Object>> validatedUsers(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "all") String filter,
            @RequestParam(required = false) String search) {

        Map<String, Object> progress = importService.getImportProgress(id);

        // Real paginated query with filter + optional search
        Page<TempImportUser> pagedResult = importService.getPagedTempUsers(
                id, filter, search, PageRequest.of(page, size));

        List<Map<String, Object>> tempUsersList = pagedResult.getContent().stream()
                .map(this::mapTempUser)
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("id", id);
        response.put("validCount", progress.getOrDefault("valid_count", 0L));
        response.put("invalidCount", progress.getOrDefault("invalid_count", 0L));
        response.put("status", progress.get("status"));
        response.put("tempUsers", tempUsersList);
        response.put("totalElements", pagedResult.getTotalElements());
        response.put("totalPages", pagedResult.getTotalPages());
        response.put("currentPage", page);

        // Phase D: Include unmatched columns for interactive selection
        List<Map<String, Object>> unmatchedColumns = importService.getUnmatchedColumns(id);
        if (!unmatchedColumns.isEmpty()) {
            response.put("unmatchedColumns", unmatchedColumns);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Update a TempImportUser inline (edit fields from preview table)
     */
    @PatchMapping("/{id}/temp_users/{tempUserId}")
    public ResponseEntity<Map<String, Object>> updateTempUser(
            @PathVariable Long id,
            @PathVariable Long tempUserId,
            @RequestBody Map<String, String> fields) {

        TempImportUser updated = importService.updateTempUser(tempUserId, fields);
        return ResponseEntity.ok(mapTempUser(updated));
    }

    /**
     * Delete a TempImportUser from preview (remove erroneous record)
     */
    @DeleteMapping("/{id}/temp_users/{tempUserId}")
    public ResponseEntity<Void> deleteTempUser(
            @PathVariable Long id,
            @PathVariable Long tempUserId) {

        importService.deleteTempUser(id, tempUserId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Re-validate all TempImportUsers for an import.
     * Called after edit/delete to resolve cross-record errors (duplicate phone/email).
     */
    @PostMapping("/{id}/revalidate")
    public ResponseEntity<Map<String, Object>> revalidateImport(@PathVariable Long id) {
        Map<String, Object> result = importService.revalidateImport(id);
        return ResponseEntity.ok(result);
    }

    /**
     * Re-validate only affected TempImportUsers after an edit or delete.
     * Much faster than full revalidation — O(k) instead of O(n).
     * Body: { "tempUserId": 123 } for edits, or { "deletedPhone": "...", "deletedEmail": "..." } for deletes.
     */
    @PostMapping("/{id}/revalidate_affected")
    public ResponseEntity<Map<String, Object>> revalidateAffected(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        Long tempUserId = body.containsKey("tempUserId") && body.get("tempUserId") != null
                ? ((Number) body.get("tempUserId")).longValue() : null;
        String deletedPhone = (String) body.get("deletedPhone");
        String deletedEmail = (String) body.get("deletedEmail");

        Map<String, Object> result = importService.revalidateAffected(
                id, tempUserId, deletedPhone, deletedEmail);
        return ResponseEntity.ok(result);
    }

    /**
     * Accept selected unmatched columns — creates CrmInfoSettings and re-maps data
     * Phase D: Interactive column selection endpoint
     */
    @PostMapping("/{id}/accept_columns")
    public ResponseEntity<Map<String, Object>> acceptColumns(
            @PathVariable Long id,
            @RequestBody Map<String, List<String>> body) {

        List<String> columns = body.getOrDefault("columns", List.of());
        if (columns.isEmpty()) {
            return ResponseEntity.ok(Map.of("result", "success", "message", "No columns to accept"));
        }

        importService.acceptUnmatchedColumns(id, columns);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", columns.size() + " column(s) accepted as CRM fields"
        ));
    }

    /**
     * Download sample CSV template
     * Equivalent to Rails: Admin::ImportsController#sample_csv
     */
    @GetMapping("/sample_csv")
    public ResponseEntity<String> sampleCsv(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false, defaultValue = "user") String importType) {

        String csv = importService.generateSampleCsv(importType, currentUser.getClientId());

        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=utf-8")
                .header("Content-Disposition",
                        "attachment; filename=usuarios_muestra.csv")
                .body(csv);
    }

    /**
     * Get import status/progress
     * Uses Rails-compatible field names: tot_records, progress
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable Long id) {
        Import importEntity = importService.findById(id);

        Map<String, Object> response = new HashMap<>();
        response.put("id", importEntity.getId());
        response.put("status", importEntity.getStatus().name().toLowerCase());
        response.put("tot_records", importEntity.getTotRecords());
        response.put("progress", importEntity.getProgress());

        // Calculate progress percentage
        response.put("progress_percent", importEntity.calculateProgress());

        response.put("is_complete", importEntity.getStatus() == ImportStatus.STATUS_COMPLETED ||
                importEntity.getStatus() == ImportStatus.STATUS_ERROR);

        return ResponseEntity.ok(response);
    }

    /**
     * Cancel a pending/processing import
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long id) {
        Import importEntity = importService.cancelImport(id);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "import", mapImportToResponse(importEntity)
        ));
    }

    /**
     * Delete an import
     * PARIDAD RAILS: Admin::ImportsController#destroy
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> destroy(@PathVariable Long id) {
        importService.deleteImport(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Download error report
     * Uses Rails-compatible field: errors_text
     */
    @GetMapping("/{id}/errors")
    public ResponseEntity<Map<String, Object>> errors(@PathVariable Long id) {
        Import importEntity = importService.findById(id);

        return ResponseEntity.ok(Map.of(
                "id", importEntity.getId(),
                "errors_text", importEntity.getErrorsText() != null ?
                        importEntity.getErrorsText() : ""
        ));
    }

    // ========== Mapping Templates ==========

    /**
     * List mapping templates for the current client
     */
    @GetMapping("/mapping_templates")
    public ResponseEntity<List<Map<String, Object>>> listMappingTemplates(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        List<ImportMappingTemplate> templates = importService.getMappingTemplates(currentUser.getClientId());
        List<Map<String, Object>> result = templates.stream()
                .map(this::mapTemplateToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * Save a new mapping template
     * Body: { "name": "FOH Estándar", "isFoh": true, "columnMapping": {...}, "headers": [...] }
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/mapping_templates")
    public ResponseEntity<Map<String, Object>> saveMappingTemplate(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody Map<String, Object> body) {

        String name = (String) body.get("name");
        boolean isFoh = Boolean.TRUE.equals(body.get("isFoh"));
        Map<String, String> columnMapping = (Map<String, String>) body.get("columnMapping");
        List<String> headers = (List<String>) body.get("headers");

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("result", "error", "message", "name is required"));
        }
        if (columnMapping == null || columnMapping.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("result", "error", "message", "columnMapping is required"));
        }
        if (headers == null || headers.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("result", "error", "message", "headers is required"));
        }

        ImportMappingTemplate template = importService.saveMappingTemplate(
                currentUser.getClientId(), name, isFoh, columnMapping, headers);

        Map<String, Object> response = new HashMap<>();
        response.put("result", "success");
        response.put("template", mapTemplateToResponse(template));
        return ResponseEntity.ok(response);
    }

    /**
     * Delete a mapping template
     */
    @DeleteMapping("/mapping_templates/{templateId}")
    public ResponseEntity<Map<String, Object>> deleteMappingTemplate(@PathVariable Long templateId) {
        importService.deleteMappingTemplate(templateId);
        return ResponseEntity.ok(Map.of("result", "success", "message", "Template deleted"));
    }

    /**
     * Find matching template for given headers
     * Body: { "headers": [...], "isFoh": false }
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/mapping_templates/match")
    public ResponseEntity<Map<String, Object>> findMatchingTemplate(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody Map<String, Object> body) {

        List<String> headers = (List<String>) body.get("headers");
        boolean isFoh = Boolean.TRUE.equals(body.get("isFoh"));

        if (headers == null || headers.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("result", "error", "message", "headers is required"));
        }

        ImportMappingTemplate match = importService.findMatchingTemplate(
                currentUser.getClientId(), headers, isFoh);

        Map<String, Object> response = new HashMap<>();
        if (match != null) {
            response.put("found", true);
            response.put("template", mapTemplateToResponse(match));
        } else {
            response.put("found", false);
        }
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> mapTemplateToResponse(ImportMappingTemplate template) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", template.getId());
        map.put("name", template.getName());
        map.put("isFoh", template.getIsFoh());
        map.put("columnMapping", template.getColumnMapping());
        map.put("headers", template.getHeaders());
        map.put("createdAt", template.getCreatedAt());
        return map;
    }

    /**
     * Map TempImportUser to response map
     */
    private Map<String, Object> mapTempUser(TempImportUser t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.getId());
        m.put("codigo", t.getCodigo());
        m.put("firstName", t.getFirstName());
        m.put("lastName", t.getLastName());
        m.put("phone", t.getPhone());
        m.put("phoneCode", t.getPhoneCode());
        m.put("email", t.getEmail());
        m.put("managerEmail", t.getManagerEmail());
        m.put("crmFields", t.getCrmFields());
        m.put("errorMessage", t.getErrorMessage());
        m.put("role", t.getRole());
        return m;
    }

    /**
     * Map Import entity to response with camelCase keys for Angular frontend
     */
    private Map<String, Object> mapImportToResponse(Import importEntity) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", importEntity.getId());
        map.put("status", importEntity.getStatus().name().toLowerCase());
        map.put("importType", importEntity.getImportType().name().toLowerCase());
        map.put("totRecords", importEntity.getTotRecords());
        map.put("progress", importEntity.getProgress());
        map.put("progressPercent", importEntity.calculateProgress());
        map.put("errorsText", importEntity.getErrorsText());
        map.put("createdAt", importEntity.getCreatedAt());
        map.put("updatedAt", importEntity.getUpdatedAt());

        if (importEntity.getUser() != null) {
            map.put("userId", importEntity.getUser().getId());
            map.put("userName", importEntity.getUser().getFullName());
        }

        if (importEntity.getClient() != null) {
            map.put("clientName", importEntity.getClient().getName());
        }

        // Parse Shrine import_file_data JSON for file name and download URL
        if (importEntity.getImportFileData() != null && !importEntity.getImportFileData().isBlank()) {
            try {
                var fileData = objectMapper.readTree(importEntity.getImportFileData());
                var metadata = fileData.path("metadata");
                if (metadata.has("filename")) {
                    map.put("importFileName", metadata.get("filename").asText());
                }
                // Download URL — always available (works with both S3 and base64)
                map.put("importFileUrl", "/app/imports/" + importEntity.getId() + "/download");
            } catch (Exception e) {
                log.debug("Could not parse import_file_data for import {}: {}", importEntity.getId(), e.getMessage());
            }
        }

        return map;
    }
}
