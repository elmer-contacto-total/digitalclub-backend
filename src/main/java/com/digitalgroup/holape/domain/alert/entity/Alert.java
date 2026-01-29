package com.digitalgroup.holape.domain.alert.entity;

import com.digitalgroup.holape.domain.common.enums.AlertSeverity;
import com.digitalgroup.holape.domain.common.enums.AlertType;
import com.digitalgroup.holape.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Alert entity matching Rails alerts table schema exactly.
 */
@Entity
@Table(name = "alerts", indexes = {
    @Index(name = "index_alerts_on_user_id", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "alert_type")
    @Builder.Default
    private AlertType alertType = AlertType.CONVERSATION_RESPONSE_OVERDUE;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "severity")
    @Builder.Default
    private AlertSeverity severity = AlertSeverity.INFO;

    @Column
    private String title;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @Column(name = "read")
    @Builder.Default
    private Boolean read = false;

    @Column(name = "url")
    private String url;

    @Column(name = "message_id")
    private String messageId;

    @Column(name = "sender_id")
    private String senderId;

    @Column(name = "recipient_id")
    private String recipientId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ==================== HELPER METHODS ====================

    public void markAsRead() {
        this.read = true;
    }

    /**
     * Alias for alertType for compatibility
     */
    public AlertType getType() {
        return alertType;
    }

    public void setType(AlertType type) {
        this.alertType = type;
    }

    /**
     * Static factory method for creating alerts (Rails compatible)
     */
    public static Alert forRequireResponse(User user, String title, String body) {
        return Alert.builder()
                .user(user)
                .alertType(AlertType.CONVERSATION_RESPONSE_OVERDUE)
                .severity(AlertSeverity.WARNING)
                .title(title)
                .body(body)
                .read(false)
                .build();
    }
}
