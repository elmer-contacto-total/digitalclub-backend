package com.digitalgroup.holape.domain.media.entity;

import com.digitalgroup.holape.domain.media.enums.MediaAuditAction;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Entity representing media security audit logs
 */
@Entity
@Table(name = "media_audit_logs", indexes = {
    @Index(name = "idx_media_audit_user", columnList = "user_fingerprint"),
    @Index(name = "idx_media_audit_action", columnList = "action"),
    @Index(name = "idx_media_audit_timestamp", columnList = "event_timestamp"),
    @Index(name = "idx_media_audit_chat", columnList = "chat_phone")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MediaAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_fingerprint", length = 64, nullable = false)
    private String userFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 30, nullable = false)
    private MediaAuditAction action;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "chat_phone", length = 20)
    private String chatPhone;

    @Column(name = "file_type", length = 100)
    private String fileType;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "original_url", length = 1000)
    private String originalUrl;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_metadata", columnDefinition = "jsonb")
    private Map<String, Object> extraMetadata;

    @Column(name = "event_timestamp", nullable = false)
    private LocalDateTime eventTimestamp;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (eventTimestamp == null) {
            eventTimestamp = LocalDateTime.now();
        }
    }
}
