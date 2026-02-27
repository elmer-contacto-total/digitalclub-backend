package com.digitalgroup.holape.api.v1;

import com.digitalgroup.holape.api.v1.dto.media.CapturedMediaResponse;
import com.digitalgroup.holape.api.v1.dto.media.LogMediaAuditRequest;
import com.digitalgroup.holape.api.v1.dto.media.SaveCapturedMediaRequest;
import com.digitalgroup.holape.domain.media.entity.CapturedMedia;
import com.digitalgroup.holape.domain.media.entity.MediaAuditLog;
import com.digitalgroup.holape.domain.media.enums.CapturedMediaType;
import com.digitalgroup.holape.domain.media.enums.MediaAuditAction;
import com.digitalgroup.holape.domain.media.service.CapturedMediaService;
import com.digitalgroup.holape.domain.media.service.MediaAuditLogService;
import com.digitalgroup.holape.security.CustomUserDetails;
import com.digitalgroup.holape.websocket.WebSocketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST Controller for captured media operations.
 * Migrated from ms-media microservice.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/media")
@RequiredArgsConstructor
// CORS ya está configurado globalmente en CorsConfig.java
public class CapturedMediaController {

    private final CapturedMediaService mediaService;
    private final MediaAuditLogService auditService;
    private final WebSocketService webSocketService;

    /**
     * Save a captured media from Electron app
     */
    @PostMapping
    public ResponseEntity<?> saveMedia(@Valid @RequestBody SaveCapturedMediaRequest request) {
        log.info("[MediaController] Saving media: {} from user: {}", request.getMediaId(), request.getUserId());

        Optional<CapturedMedia> result = mediaService.saveMedia(request);

        if (result.isPresent()) {
            CapturedMediaResponse response = CapturedMediaResponse.fromEntity(result.get());

            // Notify agent via WebSocket (non-blocking: failure must not break the save)
            try {
                webSocketService.sendCapturedMediaUpdate(response);
            } catch (Exception e) {
                log.warn("[MediaController] WebSocket broadcast failed for media {}: {}", request.getMediaId(), e.getMessage());
            }

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(response);
        } else {
            // Either duplicate or error - return status indicating skipped
            Map<String, Object> response = new HashMap<>();
            response.put("status", "skipped");
            response.put("message", "Media already exists or could not be saved");
            response.put("mediaId", request.getMediaId());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * Mark a captured media as deleted (original WhatsApp message was deleted)
     */
    @PostMapping("/mark-deleted")
    public ResponseEntity<?> markDeleted(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody Map<String, String> request) {
        String whatsappMessageId = request.get("whatsappMessageId");
        if (whatsappMessageId == null || whatsappMessageId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "whatsappMessageId is required"
            ));
        }

        // Ownership check: verify media belongs to same organization
        if (currentUser != null && !currentUser.isAdmin()) {
            List<CapturedMedia> mediaList = mediaService.findAllByWhatsappMessageId(whatsappMessageId);
            if (!mediaList.isEmpty()) {
                CapturedMedia sample = mediaList.get(0);
                if (sample.getAgent() != null && sample.getAgent().getClientId() != null
                        && !sample.getAgent().getClientId().equals(currentUser.getClientId())) {
                    log.warn("[MediaController] Forbidden: user clientId={} tried to mark-deleted media owned by clientId={}",
                            currentUser.getClientId(), sample.getAgent().getClientId());
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                            "status", "error",
                            "message", "Not authorized to modify this media"
                    ));
                }
            }
        }

        log.info("[MediaController] Marking media as deleted: whatsappMessageId={} by userId={}",
                whatsappMessageId, currentUser != null ? currentUser.getId() : "unknown");
        Optional<CapturedMedia> updated = mediaService.markAsDeleted(whatsappMessageId);

        if (updated.isPresent()) {
            // Notify agent via WebSocket
            try {
                CapturedMediaResponse mediaResponse = CapturedMediaResponse.fromEntity(updated.get());
                webSocketService.sendCapturedMediaDeleted(mediaResponse);
            } catch (Exception e) {
                log.warn("[MediaController] WebSocket broadcast failed for deleted media: {}", e.getMessage());
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", updated.isPresent() ? "updated" : "not_found");
        response.put("whatsappMessageId", whatsappMessageId);
        return ResponseEntity.ok(response);
    }

    /**
     * Log a media audit event
     */
    @PostMapping("/audit")
    public ResponseEntity<?> logAudit(@Valid @RequestBody LogMediaAuditRequest request) {
        log.info("[MediaController] Logging audit: {} - {}", request.getAction(), request.getUserId());

        MediaAuditLog auditLog = auditService.logEvent(request);

        Map<String, Object> response = new HashMap<>();
        response.put("id", auditLog.getId());
        response.put("status", "logged");

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get media by chat phone number
     */
    @GetMapping("/chat/{phone}")
    public ResponseEntity<List<CapturedMediaResponse>> getByChat(
            @PathVariable String phone,
            @RequestParam(defaultValue = "IMAGE,AUDIO") String types,
            @RequestParam(defaultValue = "50") int limit) {

        log.info("[MediaController] Getting media for chat: {} types: {} limit: {}", phone, types, limit);

        List<CapturedMediaType> mediaTypes = Arrays.stream(types.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .map(CapturedMediaType::valueOf)
                .collect(Collectors.toList());

        List<CapturedMedia> mediaList = mediaService.findByChatPhone(phone, mediaTypes, limit);

        List<CapturedMediaResponse> response = mediaList.stream()
                .map(CapturedMediaResponse::fromEntity)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Download media file by UUID
     */
    @GetMapping("/{mediaUuid}/file")
    public ResponseEntity<Resource> downloadFile(@PathVariable String mediaUuid) {
        log.info("[MediaController] Downloading media: {}", mediaUuid);

        Optional<CapturedMedia> mediaOpt = mediaService.findByUuid(mediaUuid);

        if (mediaOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        CapturedMedia media = mediaOpt.get();

        try {
            byte[] content = mediaService.downloadMedia(media);
            ByteArrayResource resource = new ByteArrayResource(content);

            String filename = mediaUuid + "." + getExtensionFromMimeType(media.getMimeType());

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(media.getMimeType() != null ? media.getMimeType() : "application/octet-stream"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(resource);

        } catch (Exception e) {
            log.error("[MediaController] Error downloading media: {}", mediaUuid, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get audit logs with filters
     */
    @GetMapping("/audit")
    public ResponseEntity<Page<MediaAuditLog>> getAuditLogs(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        log.info("[MediaController] Getting audit logs - userId: {}, action: {}, page: {}", userId, action, page);

        PageRequest pageable = PageRequest.of(page, size);
        Page<MediaAuditLog> result;

        if (userId != null && action != null) {
            MediaAuditAction auditAction = MediaAuditAction.valueOf(action.toUpperCase());
            result = auditService.findByUserFingerprintAndAction(userId, auditAction, pageable);
        } else if (userId != null) {
            result = auditService.findByUserFingerprint(userId, pageable);
        } else if (action != null) {
            MediaAuditAction auditAction = MediaAuditAction.valueOf(action.toUpperCase());
            result = auditService.findByAction(auditAction, pageable);
        } else if (from != null && to != null) {
            LocalDateTime fromDate = LocalDateTime.parse(from, DateTimeFormatter.ISO_DATE_TIME);
            LocalDateTime toDate = LocalDateTime.parse(to, DateTimeFormatter.ISO_DATE_TIME);
            result = auditService.findByDateRange(fromDate, toDate, pageable);
        } else {
            result = auditService.findAll(pageable);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get known whatsappMessageIds for a chat phone (lightweight, for Electron cross-agent sync).
     * Returns only IDs + deleted status so the IIFE can seed its tracking sets.
     */
    @GetMapping("/chat/{phone}/known-ids")
    public ResponseEntity<List<Map<String, Object>>> getKnownMessageIds(
            @PathVariable String phone,
            @RequestParam(defaultValue = "500") int limit) {

        log.info("[MediaController] Getting known message IDs for chat: {} limit: {}", phone, limit);

        List<Object[]> rows = mediaService.findKnownMessageIdsByChatPhone(phone, limit);

        List<Map<String, Object>> response = rows.stream()
                .map(row -> {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("whatsappMessageId", row[0]);
                    entry.put("deleted", Boolean.TRUE.equals(row[1]));
                    entry.put("chatName", row[2]);
                    return entry;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "captured-media");
        health.put("timestamp", LocalDateTime.now().toString());
        health.put("mediaCount", mediaService.countTotal());
        health.put("auditCount", auditService.countTotal());

        return ResponseEntity.ok(health);
    }

    /**
     * Get media by UUID (metadata only)
     */
    @GetMapping("/{mediaUuid}")
    public ResponseEntity<CapturedMediaResponse> getByUuid(@PathVariable String mediaUuid) {
        return mediaService.findByUuid(mediaUuid)
                .map(media -> ResponseEntity.ok(CapturedMediaResponse.fromEntity(media)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Refresh public URL for a media (URLs expire after 1 hour)
     */
    @PostMapping("/{mediaUuid}/refresh-url")
    public ResponseEntity<Map<String, String>> refreshUrl(@PathVariable String mediaUuid) {
        Optional<CapturedMedia> mediaOpt = mediaService.findByUuid(mediaUuid);

        if (mediaOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String newUrl = mediaService.refreshPublicUrl(mediaOpt.get());

        Map<String, String> response = new HashMap<>();
        response.put("mediaUuid", mediaUuid);
        response.put("publicUrl", newUrl);

        return ResponseEntity.ok(response);
    }

    private String getExtensionFromMimeType(String mimeType) {
        if (mimeType == null) return "bin";
        return switch (mimeType.toLowerCase()) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "audio/ogg", "audio/ogg; codecs=opus" -> "ogg";
            case "audio/mpeg" -> "mp3";
            case "audio/mp4" -> "m4a";
            case "audio/webm" -> "webm";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "application/pdf" -> "pdf";
            default -> "bin";
        };
    }
}
