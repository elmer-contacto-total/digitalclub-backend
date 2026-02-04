package com.digitalgroup.holape.domain.media.service;

import com.digitalgroup.holape.api.v1.dto.media.LogMediaAuditRequest;
import com.digitalgroup.holape.domain.media.entity.MediaAuditLog;
import com.digitalgroup.holape.domain.media.enums.MediaAuditAction;
import com.digitalgroup.holape.domain.media.repository.MediaAuditLogRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaAuditLogService {

    private final MediaAuditLogRepository auditRepository;
    private final UserRepository userRepository;

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

            // Look up agent
            User agent = null;
            if (request.getAgentId() != null) {
                agent = userRepository.findById(request.getAgentId()).orElse(null);
            }

            // Look up client user by chat phone
            User clientUser = null;
            if (request.getChatPhone() != null && !request.getChatPhone().isEmpty()) {
                clientUser = findUserByPhone(request.getChatPhone());
            }

            MediaAuditLog auditLog = MediaAuditLog.builder()
                    .agent(agent)
                    .clientUser(clientUser)
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
            log.info("[MediaAuditService] Audit event logged: {} - {} agent={}",
                    action, request.getUserId(), agent != null ? agent.getId() : "null");

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

    /**
     * Find user by phone number, trying multiple formats
     */
    private User findUserByPhone(String phone) {
        if (phone == null || phone.isEmpty()) {
            return null;
        }

        // Normalize phone (remove non-digits)
        String normalizedPhone = phone.replaceAll("[^0-9]", "");

        // Try exact match first
        Optional<User> user = userRepository.findByPhone(normalizedPhone);
        if (user.isPresent()) {
            return user.get();
        }

        // Try without country code (last 9 digits for Peru)
        if (normalizedPhone.length() > 9) {
            String shortPhone = normalizedPhone.substring(normalizedPhone.length() - 9);
            user = userRepository.findByPhone(shortPhone);
            if (user.isPresent()) {
                return user.get();
            }

            // Try with Peru country code
            String withCountryCode = "51" + shortPhone;
            user = userRepository.findByPhone(withCountryCode);
            if (user.isPresent()) {
                return user.get();
            }
        }

        return null;
    }
}
