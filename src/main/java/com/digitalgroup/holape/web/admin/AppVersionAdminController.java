package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.api.v1.dto.AppVersionDto;
import com.digitalgroup.holape.domain.app.entity.AppVersion;
import com.digitalgroup.holape.domain.app.repository.AppVersionRepository;
import com.digitalgroup.holape.domain.app.service.AppVersionService;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import com.digitalgroup.holape.web.dto.PagedResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin controller for managing app versions.
 * Only accessible by SUPER_ADMIN role.
 */
@Slf4j
@RestController
@RequestMapping("/app/app_versions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AppVersionAdminController {

    private final AppVersionRepository appVersionRepository;
    private final AppVersionService appVersionService;

    /**
     * List all app versions with pagination
     */
    @GetMapping
    public ResponseEntity<PagedResponse<Map<String, Object>>> index(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String platform) {

        log.info("Listing app versions - page: {}, pageSize: {}, platform: {}", page, pageSize, platform);

        PageRequest pageable = PageRequest.of(
                Math.max(0, page - 1),
                pageSize,
                Sort.by(Sort.Direction.DESC, "publishedAt")
        );

        Page<AppVersion> versionsPage;
        if (platform != null && !platform.isBlank()) {
            versionsPage = appVersionRepository.findAll(pageable);
            // Filter by platform (could be optimized with a custom query)
            List<AppVersion> filtered = versionsPage.getContent().stream()
                    .filter(v -> v.getPlatform().equalsIgnoreCase(platform))
                    .toList();
            versionsPage = new org.springframework.data.domain.PageImpl<>(
                    filtered, pageable, versionsPage.getTotalElements());
        } else {
            versionsPage = appVersionRepository.findAll(pageable);
        }

        List<Map<String, Object>> data = versionsPage.getContent().stream()
                .map(this::mapVersionToResponse)
                .toList();

        return ResponseEntity.ok(PagedResponse.of(
                data,
                versionsPage.getTotalElements(),
                page,
                pageSize
        ));
    }

    /**
     * Get a single app version by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> show(@PathVariable Long id) {
        log.info("Getting app version: {}", id);

        AppVersion version = appVersionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AppVersion", id));

        return ResponseEntity.ok(mapVersionToResponse(version));
    }

    /**
     * Create a new app version
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateAppVersionRequest request) {
        log.info("Creating app version: {} for platform: {}", request.version(), request.platform());

        // Validate required fields
        if (request.version() == null || request.version().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Version is required"));
        }
        if (request.downloadUrl() == null || request.downloadUrl().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Download URL is required"));
        }

        // Check if version already exists for this platform
        String platform = request.platform() != null ? request.platform() : "windows";
        if (appVersionRepository.findByVersionAndPlatform(request.version(), platform).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Version " + request.version() + " already exists for platform " + platform
            ));
        }

        AppVersion version = AppVersion.builder()
                .version(request.version())
                .downloadUrl(request.downloadUrl())
                .platform(platform)
                .releaseNotes(request.releaseNotes())
                .fileSize(request.fileSize())
                .sha256Hash(request.sha256Hash())
                .mandatory(request.mandatory() != null ? request.mandatory() : false)
                .active(request.active() != null ? request.active() : true)
                .publishedAt(request.publishedAt() != null ? request.publishedAt() : LocalDateTime.now())
                .build();

        version = appVersionRepository.save(version);
        log.info("Created app version: {} with ID: {}", version.getVersion(), version.getId());

        return ResponseEntity.ok(mapVersionToResponse(version));
    }

    /**
     * Update an existing app version
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody UpdateAppVersionRequest request) {

        log.info("Updating app version: {}", id);

        AppVersion version = appVersionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AppVersion", id));

        // Update fields if provided
        if (request.version() != null && !request.version().isBlank()) {
            version.setVersion(request.version());
        }
        if (request.downloadUrl() != null && !request.downloadUrl().isBlank()) {
            version.setDownloadUrl(request.downloadUrl());
        }
        if (request.platform() != null && !request.platform().isBlank()) {
            version.setPlatform(request.platform());
        }
        if (request.releaseNotes() != null) {
            version.setReleaseNotes(request.releaseNotes());
        }
        if (request.fileSize() != null) {
            version.setFileSize(request.fileSize());
        }
        if (request.sha256Hash() != null) {
            version.setSha256Hash(request.sha256Hash());
        }
        if (request.mandatory() != null) {
            version.setMandatory(request.mandatory());
        }
        if (request.active() != null) {
            version.setActive(request.active());
        }
        if (request.publishedAt() != null) {
            version.setPublishedAt(request.publishedAt());
        }

        version = appVersionRepository.save(version);
        log.info("Updated app version: {}", version.getId());

        return ResponseEntity.ok(mapVersionToResponse(version));
    }

    /**
     * Delete an app version
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long id) {
        log.info("Deleting app version: {}", id);

        AppVersion version = appVersionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AppVersion", id));

        appVersionRepository.delete(version);
        log.info("Deleted app version: {} ({})", version.getVersion(), id);

        return ResponseEntity.ok(Map.of("message", "Version deleted successfully"));
    }

    /**
     * Toggle active status of a version
     */
    @PostMapping("/{id}/toggle_active")
    public ResponseEntity<Map<String, Object>> toggleActive(@PathVariable Long id) {
        log.info("Toggling active status for app version: {}", id);

        AppVersion version = appVersionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AppVersion", id));

        version.setActive(!Boolean.TRUE.equals(version.getActive()));
        version = appVersionRepository.save(version);

        log.info("App version {} is now {}", id, version.getActive() ? "active" : "inactive");

        return ResponseEntity.ok(mapVersionToResponse(version));
    }

    // ==================== HELPER METHODS ====================

    private Map<String, Object> mapVersionToResponse(AppVersion version) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", version.getId());
        map.put("version", version.getVersion());
        map.put("downloadUrl", version.getDownloadUrl());
        map.put("platform", version.getPlatform());
        map.put("releaseNotes", version.getReleaseNotes());
        map.put("fileSize", version.getFileSize());
        map.put("sha256Hash", version.getSha256Hash());
        map.put("mandatory", version.getMandatory());
        map.put("active", version.getActive());
        map.put("publishedAt", version.getPublishedAt());
        map.put("createdAt", version.getCreatedAt());
        return map;
    }

    // ==================== REQUEST RECORDS ====================

    public record CreateAppVersionRequest(
            String version,
            String downloadUrl,
            String platform,
            String releaseNotes,
            Long fileSize,
            String sha256Hash,
            Boolean mandatory,
            Boolean active,
            LocalDateTime publishedAt
    ) {}

    public record UpdateAppVersionRequest(
            String version,
            String downloadUrl,
            String platform,
            String releaseNotes,
            Long fileSize,
            String sha256Hash,
            Boolean mandatory,
            Boolean active,
            LocalDateTime publishedAt
    ) {}
}
