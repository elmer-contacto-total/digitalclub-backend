package com.digitalgroup.holape.domain.campaign.entity;

import com.digitalgroup.holape.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * BulkCampaignRecipient Entity
 * Tracks individual recipient status within a campaign
 */
@Entity
@Table(name = "bulk_campaign_recipients", indexes = {
        @Index(name = "idx_bcr_campaign", columnList = "campaign_id"),
        @Index(name = "idx_bcr_status", columnList = "campaign_id, status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkCampaignRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private BulkCampaign campaign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING"; // PENDING, SENT, FAILED, SKIPPED

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) status = "PENDING";
    }

    public void markSent() {
        this.status = "SENT";
        this.sentAt = LocalDateTime.now();
    }

    public void markFailed(String error) {
        this.status = "FAILED";
        this.errorMessage = error != null && error.length() > 500 ? error.substring(0, 500) : error;
    }

    public void markSkipped(String reason) {
        this.status = "SKIPPED";
        this.errorMessage = reason;
    }
}
