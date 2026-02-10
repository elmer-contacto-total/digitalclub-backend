package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.common.enums.ImportStatus;
import com.digitalgroup.holape.domain.importdata.entity.Import;
import com.digitalgroup.holape.domain.importdata.service.ImportService;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    /**
     * List imports for the client
     * PARIDAD RAILS: Admin/SuperAdmin see all; others see only their own
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> index(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

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
     * Create new import from CSV file
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

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "import", mapImportToResponse(importEntity),
                "message", "Import created and processing started"
        ));
    }

    /**
     * Create FOH-specific import
     * Equivalent to Rails: Admin::ImportsController for Financiera Oh
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

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "import", mapImportToResponse(importEntity),
                "message", "FOH import created and validation started"
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
     */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@PathVariable Long id) {
        importService.confirmImport(id);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", "Import processing started"
        ));
    }

    /**
     * Get validated users preview before processing
     * Equivalent to Rails: Admin::ImportsController#validated_import_user
     */
    @GetMapping("/{id}/validated_users")
    public ResponseEntity<Map<String, Object>> validatedUsers(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {

        Map<String, Object> progress = importService.getImportProgress(id);

        return ResponseEntity.ok(Map.of(
                "id", id,
                "valid_count", progress.get("valid_count"),
                "invalid_count", progress.get("invalid_count"),
                "status", progress.get("status")
        ));
    }

    /**
     * Download sample CSV template
     * Equivalent to Rails: Admin::ImportsController#sample_csv
     */
    @GetMapping("/sample_csv")
    public ResponseEntity<String> sampleCsv(
            @RequestParam(required = false, defaultValue = "user") String importType) {

        String csv = importService.generateSampleCsv(importType);

        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=utf-8")
                .header("Content-Disposition",
                        "attachment; filename=sample_" + importType + "_import.csv")
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

        // Parse Shrine import_file_data JSON for file name
        if (importEntity.getImportFileData() != null && !importEntity.getImportFileData().isBlank()) {
            try {
                var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var fileData = objectMapper.readTree(importEntity.getImportFileData());
                var metadata = fileData.path("metadata");
                if (metadata.has("filename")) {
                    map.put("importFileName", metadata.get("filename").asText());
                }
                // Build file URL from Shrine storage id
                if (fileData.has("id")) {
                    map.put("importFileUrl", "/uploads/import/import_file/" + importEntity.getId() + "/" + fileData.get("id").asText());
                }
            } catch (Exception e) {
                log.debug("Could not parse import_file_data for import {}: {}", importEntity.getId(), e.getMessage());
            }
        }

        return map;
    }
}
