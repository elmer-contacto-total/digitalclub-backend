package com.digitalgroup.holape.domain.bulksend.entity;

import com.digitalgroup.holape.domain.client.entity.Client;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * BulkSendRule Entity
 * Supervisor-configurable rules for anti-ban and rate limiting
 * One per client (unique constraint)
 */
@Entity
@Table(name = "bulk_send_rules", uniqueConstraints = {
        @UniqueConstraint(columnNames = "client_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkSendRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(name = "max_daily_messages", nullable = false)
    @Builder.Default
    private Integer maxDailyMessages = 200;

    @Column(name = "min_delay_seconds", nullable = false)
    @Builder.Default
    private Integer minDelaySeconds = 30;

    @Column(name = "max_delay_seconds", nullable = false)
    @Builder.Default
    private Integer maxDelaySeconds = 90;

    @Column(name = "pause_after_count", nullable = false)
    @Builder.Default
    private Integer pauseAfterCount = 20;

    @Column(name = "pause_duration_minutes", nullable = false)
    @Builder.Default
    private Integer pauseDurationMinutes = 5;

    @Column(name = "send_hour_start", nullable = false)
    @Builder.Default
    private Integer sendHourStart = 8;

    @Column(name = "send_hour_end", nullable = false)
    @Builder.Default
    private Integer sendHourEnd = 20;

    @Column(name = "cloud_api_delay_ms", nullable = false)
    @Builder.Default
    private Integer cloudApiDelayMs = 100;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
