package com.digitalgroup.holape.domain.media.service;

import com.digitalgroup.holape.api.v1.dto.media.LogMediaAuditRequest;
import com.digitalgroup.holape.domain.media.entity.MediaAuditLog;
import com.digitalgroup.holape.domain.media.enums.MediaAuditAction;
import com.digitalgroup.holape.domain.media.repository.MediaAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaAuditLogService {

    private final MediaAuditLogRepository auditRepository;

    /**
     * Log a media audit event
     *
     * @param request the audit log request
     * @return the saved audit log
     */
    @Transactional
    public MediaAuditLog logEvent(LogMediaAuditRequest request) {
        try {
            // Parse action
            MediaAuditAction action;
            try {
                action = MediaAuditAction.valueOf(request.getAction().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("[MediaAuditService] Unknown action: {}, defaulting to MEDIA_CAPTURED", request.getAction());
                action = MediaAuditAction.MEDIA_CAPTURED;
            }

            // Parse timestamp
            LocalDateTime eventTimestamp;
            try {
                eventTimestamp = LocalDateTime.parse(request.getTimestamp(), DateTimeFormatter.ISO_DATE_TIME);
            } catch (Exception e) {
                eventTimestamp = LocalDateTime.now();
            }

            MediaAuditLog auditLog = MediaAuditLog.builder()
                    .userFingerprint(request.getUserId())
                    .action(action)
                    .description(request.getDescription())
                    .chatPhone(request.getChatPhone())
                    .fileType(request.getMimeType())
                    .fileName(request.getFilename())
                    .originalUrl(request.getUrl())
                    .sizeBytes(request.getSize())
                    .clientIp(request.getClientIp())
                    .extraMetadata(request.getMetadata())
                    .eventTimestamp(eventTimestamp)
                    .build();

            MediaAuditLog saved = auditRepository.save(auditLog);
            log.info("[MediaAuditService] Audit event logged: {} - {}", action, request.getUserId());

            return saved;

        } catch (Exception e) {
            log.error("[MediaAuditService] Error logging audit event: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to log audit event", e);
        }
    }

    public Page<MediaAuditLog> findAll(Pageable pageable) {
        return auditRepository.findAllByOrderByEventTimestampDesc(pageable);
    }

    public Page<MediaAuditLog> findByUserFingerprint(String fingerprint, Pageable pageable) {
        return auditRepository.findByUserFingerprintOrderByEventTimestampDesc(fingerprint, pageable);
    }

    public Page<MediaAuditLog> findByAction(MediaAuditAction action, Pageable pageable) {
        return auditRepository.findByActionOrderByEventTimestampDesc(action, pageable);
    }

    public Page<MediaAuditLog> findByUserFingerprintAndAction(String fingerprint, MediaAuditAction action, Pageable pageable) {
        return auditRepository.findByUserFingerprintAndActionOrderByEventTimestampDesc(fingerprint, action, pageable);
    }

    public Page<MediaAuditLog> findByDateRange(LocalDateTime from, LocalDateTime to, Pageable pageable) {
        return auditRepository.findByEventTimestampBetweenOrderByEventTimestampDesc(from, to, pageable);
    }

    public long countTotal() {
        return auditRepository.count();
    }

    public long countByAction(MediaAuditAction action) {
        return auditRepository.countByAction(action);
    }
}
