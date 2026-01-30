package com.digitalgroup.holape.api.v1.dto.media;

import com.digitalgroup.holape.domain.media.entity.CapturedMedia;
import com.digitalgroup.holape.domain.media.enums.CapturedMediaType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for captured media
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapturedMediaResponse {

    private Long id;
    private String mediaUuid;
    private String userFingerprint;
    private String chatPhone;
    private String chatName;
    private CapturedMediaType mediaType;
    private String mimeType;
    private String filePath;
    private String publicUrl;
    private Long sizeBytes;
    private Integer durationSeconds;
    private String captureSource;
    private LocalDateTime capturedAt;
    private LocalDateTime createdAt;

    public static CapturedMediaResponse fromEntity(CapturedMedia media) {
        return CapturedMediaResponse.builder()
                .id(media.getId())
                .mediaUuid(media.getMediaUuid())
                .userFingerprint(media.getUserFingerprint())
                .chatPhone(media.getChatPhone())
                .chatName(media.getChatName())
                .mediaType(media.getMediaType())
                .mimeType(media.getMimeType())
                .filePath(media.getFilePath())
                .publicUrl(media.getPublicUrl())
                .sizeBytes(media.getSizeBytes())
                .durationSeconds(media.getDurationSeconds())
                .captureSource(media.getCaptureSource())
                .capturedAt(media.getCapturedAt())
                .createdAt(media.getCreatedAt())
                .build();
    }
}
