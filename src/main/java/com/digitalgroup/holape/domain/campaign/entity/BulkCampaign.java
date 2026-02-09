package com.digitalgroup.holape.domain.campaign.entity;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.message.entity.BulkMessage;
import com.digitalgroup.holape.domain.message.entity.MessageTemplate;
import com.digitalgroup.holape.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * BulkCampaign Entity
 * Tracks mass sending campaigns via Cloud API or Electron
 */
@Entity
@Table(name = "bulk_campaigns", indexes = {
        @Index(name = "idx_bulk_campaigns_client", columnList = "client_id"),
        @Index(name = "idx_bulk_campaigns_user", columnList = "user_id"),
        @Index(name = "idx_bulk_campaigns_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bulk_message_id")
    private BulkMessage bulkMessage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_template_id")
    private MessageTemplate messageTemplate;

    @Column(name = "send_method", nullable = false, length = 20)
    private String sendMethod; // CLOUD_API or ELECTRON

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING"; // PENDING, PROCESSING, PAUSED, COMPLETED, CANCELLED, FAILED

    @Column(name = "total_recipients", nullable = false)
    @Builder.Default
    private Integer totalRecipients = 0;

    @Column(name = "sent_count", nullable = false)
    @Builder.Default
    private Integer sentCount = 0;

    @Column(name = "failed_count", nullable = false)
    @Builder.Default
    private Integer failedCount = 0;

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

    @OneToMany(mappedBy = "campaign", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<BulkCampaignRecipient> recipients = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = "PENDING";
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

    public boolean isCloudApi() {
        return "CLOUD_API".equals(sendMethod);
    }

    public boolean isElectron() {
        return "ELECTRON".equals(sendMethod);
    }
}
