package com.digitalgroup.holape.domain.bulksend.entity;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * BulkSend Entity
 * Tracks mass sending via Electron (CSV-based)
 */
@Entity
@Table(name = "bulk_sends", indexes = {
        @Index(name = "idx_bulk_sends_client", columnList = "client_id"),
        @Index(name = "idx_bulk_sends_user", columnList = "user_id"),
        @Index(name = "idx_bulk_sends_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkSend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "send_method", nullable = false, length = 20)
    @Builder.Default
    private String sendMethod = "ELECTRON";

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "total_recipients", nullable = false)
    @Builder.Default
    private Integer totalRecipients = 0;

    @Column(name = "sent_count", nullable = false)
    @Builder.Default
    private Integer sentCount = 0;

    @Column(name = "failed_count", nullable = false)
    @Builder.Default
    private Integer failedCount = 0;

    @Column(name = "message_content", columnDefinition = "TEXT")
    private String messageContent;

    @Column(name = "attachment_path", length = 500)
    private String attachmentPath;

    @Column(name = "attachment_type", length = 50)
    private String attachmentType;

    @Column(name = "attachment_size")
    private Long attachmentSize;

    @Column(name = "attachment_original_name", length = 255)
    private String attachmentOriginalName;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "error_summary", columnDefinition = "TEXT")
    private String errorSummary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "bulkSend", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<BulkSendRecipient> recipients = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = "PENDING";
        if (sendMethod == null) sendMethod = "ELECTRON";
        if (totalRecipients == null) totalRecipients = 0;
        if (sentCount == null) sentCount = 0;
        if (failedCount == null) failedCount = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void incrementSent() {
        this.sentCount = (this.sentCount == null ? 0 : this.sentCount) + 1;
    }

    public void incrementFailed() {
        this.failedCount = (this.failedCount == null ? 0 : this.failedCount) + 1;
    }

    public int getProgressPercent() {
        if (totalRecipients == null || totalRecipients == 0) return 100;
        int processed = (sentCount != null ? sentCount : 0) + (failedCount != null ? failedCount : 0);
        return (int) ((processed * 100.0) / totalRecipients);
    }

    public boolean isComplete() {
        if (totalRecipients == null || totalRecipients == 0) return true;
        int processed = (sentCount != null ? sentCount : 0) + (failedCount != null ? failedCount : 0);
        return processed >= totalRecipients;
    }

    public boolean hasAttachment() {
        return attachmentPath != null && !attachmentPath.isBlank();
    }
}
