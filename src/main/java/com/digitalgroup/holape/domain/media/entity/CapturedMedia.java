package com.digitalgroup.holape.domain.media.entity;

import com.digitalgroup.holape.domain.media.enums.CapturedMediaType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Entity representing captured media from Electron app
 */
@Entity
@Table(name = "captured_media", indexes = {
    @Index(name = "idx_captured_media_chat_phone", columnList = "chat_phone"),
    @Index(name = "idx_captured_media_user_fingerprint", columnList = "user_fingerprint"),
    @Index(name = "idx_captured_media_captured_at", columnList = "captured_at"),
    @Index(name = "idx_captured_media_sha256", columnList = "sha256_hash")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapturedMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "media_uuid", length = 36, nullable = false, unique = true)
    private String mediaUuid;

    @Column(name = "user_fingerprint", length = 64, nullable = false)
    private String userFingerprint;

    @Column(name = "chat_phone", length = 20)
    private String chatPhone;

    @Column(name = "chat_name", length = 100)
    private String chatName;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", length = 20, nullable = false)
    private CapturedMediaType mediaType;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "file_path", length = 500)
    private String filePath;

    @Column(name = "public_url", length = 1000)
    private String publicUrl;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "sha256_hash", length = 64)
    private String sha256Hash;

    @Column(name = "whatsapp_message_id", length = 100)
    private String whatsappMessageId;

    @Column(name = "capture_source", length = 20)
    private String captureSource;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (capturedAt == null) {
            capturedAt = LocalDateTime.now();
        }
    }
}
