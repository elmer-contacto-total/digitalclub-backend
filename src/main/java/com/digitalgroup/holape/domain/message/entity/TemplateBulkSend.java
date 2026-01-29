package com.digitalgroup.holape.domain.message.entity;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * TemplateBulkSend Entity
 * Equivalent to Rails TemplateBulkSend model
 * Tracks bulk template send campaigns
 *
 * PARIDAD RAILS: Solo contiene los campos que existen en schema.rb
 */
@Entity
@Table(name = "template_bulk_sends", indexes = {
        @Index(name = "index_template_bulk_sends_on_client_id", columnList = "client_id"),
        @Index(name = "index_template_bulk_sends_on_user_id", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemplateBulkSend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(name = "message_template_id", nullable = false)
    private Integer messageTemplateId;

    @Column(name = "message_template_name", nullable = false)
    private String messageTemplateName;

    @Column(name = "planned_count")
    @Builder.Default
    private Integer plannedCount = 0;

    @Column(name = "sent_count")
    @Builder.Default
    private Integer sentCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (plannedCount == null) plannedCount = 0;
        if (sentCount == null) sentCount = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Increment sent count
     */
    public void incrementSent() {
        this.sentCount = (this.sentCount == null ? 0 : this.sentCount) + 1;
    }

    /**
     * Get progress percentage
     */
    public int getProgressPercent() {
        if (plannedCount == null || plannedCount == 0) return 100;
        int processed = sentCount != null ? sentCount : 0;
        return (int) ((processed * 100.0) / plannedCount);
    }

    /**
     * Check if completed
     */
    public boolean isComplete() {
        if (plannedCount == null || plannedCount == 0) return true;
        return sentCount != null && sentCount >= plannedCount;
    }
}
