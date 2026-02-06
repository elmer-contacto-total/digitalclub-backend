package com.digitalgroup.holape.web.dto;

import com.digitalgroup.holape.domain.media.entity.MediaAuditLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaAuditLogDto {

    private Long id;
    private Long agentId;
    private String agentName;
    private Long clientUserId;
    private String clientUserName;
    private String userFingerprint;
    private String action;
    private String description;
    private String chatPhone;
    private String fileType;
    private String fileName;
    private String originalUrl;
    private Long sizeBytes;
    private String clientIp;
    private Map<String, Object> extraMetadata;
    private LocalDateTime eventTimestamp;
    private LocalDateTime createdAt;

    public static MediaAuditLogDto from(MediaAuditLog log) {
        return MediaAuditLogDto.builder()
                .id(log.getId())
                .agentId(log.getAgent() != null ? log.getAgent().getId() : null)
                .agentName(log.getAgent() != null
                        ? (log.getAgent().getFirstName() + " " + log.getAgent().getLastName()).trim()
                        : null)
                .clientUserId(log.getClientUser() != null ? log.getClientUser().getId() : null)
                .clientUserName(log.getClientUser() != null
                        ? (log.getClientUser().getFirstName() + " " + log.getClientUser().getLastName()).trim()
                        : null)
                .userFingerprint(log.getUserFingerprint())
                .action(log.getAction().name())
                .description(log.getDescription())
                .chatPhone(log.getChatPhone())
                .fileType(log.getFileType())
                .fileName(log.getFileName())
                .originalUrl(log.getOriginalUrl())
                .sizeBytes(log.getSizeBytes())
                .clientIp(log.getClientIp())
                .extraMetadata(log.getExtraMetadata())
                .eventTimestamp(log.getEventTimestamp())
                .createdAt(log.getCreatedAt())
                .build();
    }
}
