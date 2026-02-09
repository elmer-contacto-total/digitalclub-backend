package com.digitalgroup.holape.domain.bulksend.entity;

import com.digitalgroup.holape.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * BulkSendRecipient Entity
 * Tracks individual recipient status within a bulk send
 */
@Entity
@Table(name = "bulk_send_recipients", indexes = {
        @Index(name = "idx_bsr_bulk_send", columnList = "bulk_send_id"),
        @Index(name = "idx_bsr_status", columnList = "bulk_send_id, status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkSendRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bulk_send_id", nullable = false)
    private BulkSend bulkSend;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "recipient_name", length = 255)
    private String recipientName;

    @Column(name = "custom_variables", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> customVariables;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

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

    /**
     * Resolve message template with recipient variables.
     * Replaces [name], [phone], and any custom [var] placeholders.
     */
    public String getResolvedContent(String template) {
        if (template == null) return "";
        String result = template;
        result = result.replace("[name]", recipientName != null ? recipientName : "");
        result = result.replace("[phone]", phone != null ? phone : "");
        if (customVariables != null) {
            for (Map.Entry<String, String> entry : customVariables.entrySet()) {
                result = result.replace("[" + entry.getKey() + "]", entry.getValue() != null ? entry.getValue() : "");
            }
        }
        return result;
    }
}
