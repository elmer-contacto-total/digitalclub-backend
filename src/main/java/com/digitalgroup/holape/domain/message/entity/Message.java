package com.digitalgroup.holape.domain.message.entity;

import com.digitalgroup.holape.domain.common.enums.MessageDirection;
import com.digitalgroup.holape.domain.common.enums.MessageStatus;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import com.digitalgroup.holape.domain.user.entity.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "messages", indexes = {
    @Index(name = "index_messages_on_sender_id", columnList = "sender_id"),
    @Index(name = "index_messages_on_recipient_id", columnList = "recipient_id"),
    @Index(name = "index_messages_on_ticket_id", columnList = "ticket_id"),
    @Index(name = "index_messages_on_created_at", columnList = "created_at"),
    @Index(name = "index_messages_on_processed", columnList = "processed"),
    @Index(name = "index_messages_on_direction_and_status", columnList = "direction, status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull(message = "Sender is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @Column(name = "new_sender_phone", columnDefinition = "text")
    private String newSenderPhone;

    @NotNull(message = "Recipient is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @Column(name = "is_prospect")
    @Builder.Default
    private Boolean isProspect = false;

    @Column(name = "prospect_sender_id")
    private Long prospectSenderId;

    @Column(name = "prospect_recipient_id")
    private Long prospectRecipientId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private Ticket ticket;

    @Column(name = "device_id")
    private Long deviceId;

    @NotNull(message = "Direction is required")
    @Enumerated(EnumType.ORDINAL)
    @Column(columnDefinition = "integer default 0")
    @Builder.Default
    private MessageDirection direction = MessageDirection.INCOMING;

    @Enumerated(EnumType.ORDINAL)
    @Column(columnDefinition = "integer default 0")
    @Builder.Default
    private MessageStatus status = MessageStatus.SENT;

    @NotBlank(message = "Content is required")
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "binary_content_data", columnDefinition = "text")
    private String binaryContentData;

    @Column(name = "whatsapp_business_routed")
    @Builder.Default
    private Boolean whatsappBusinessRouted = false;

    @Column(name = "original_whatsapp_business_recipient_id")
    private Long originalWhatsappBusinessRecipientId;

    @Column(name = "is_event")
    @Builder.Default
    private Boolean isEvent = false;

    @Column(name = "processed")
    @Builder.Default
    private Boolean processed = false;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "is_template")
    @Builder.Default
    private Boolean isTemplate = false;

    @Column(name = "template_name")
    private String templateName;

    @Column(name = "historic_sender_name")
    @Builder.Default
    private String historicSenderName = "";

    @Column(name = "worker_processed_at")
    private LocalDateTime workerProcessedAt;

    // TODO: Habilitar cuando la columna exista en la BD de producción
    // @Column(name = "message_order")
    @Transient
    private Integer messageOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Helper methods
    public boolean isIncoming() {
        return direction == MessageDirection.INCOMING;
    }

    public boolean isOutgoing() {
        return direction == MessageDirection.OUTGOING;
    }

    public Long getClientId() {
        return sender != null && sender.getClient() != null ? sender.getClient().getId() : null;
    }

    public void markAsProcessed() {
        this.processed = true;
        this.workerProcessedAt = LocalDateTime.now();
    }

    /**
     * Check if message has media attachment (via binary_content_data)
     */
    public boolean hasMedia() {
        return binaryContentData != null && !binaryContentData.isEmpty();
    }

    /**
     * Check if message delivery failed
     */
    public boolean isFailed() {
        return status == MessageStatus.ERROR;
    }

    /**
     * Check if message is pending delivery
     */
    public boolean isPending() {
        return status == MessageStatus.SENT && !Boolean.TRUE.equals(processed);
    }

    /**
     * Get recipient phone (for WhatsApp delivery)
     */
    public String getRecipientPhone() {
        return recipient != null ? recipient.getPhone() : null;
    }

    /**
     * Get sender phone
     */
    public String getSenderPhone() {
        return sender != null ? sender.getPhone() : newSenderPhone;
    }

    /**
     * Get display name for sender
     */
    public String getSenderDisplayName() {
        if (historicSenderName != null && !historicSenderName.isEmpty()) {
            return historicSenderName;
        }
        return sender != null ? sender.getFullName() : "Unknown";
    }

    /**
     * Mark as read
     */
    public void markAsRead() {
        this.status = MessageStatus.READ;
    }

    /**
     * Mark as failed
     */
    public void markAsFailed() {
        this.status = MessageStatus.ERROR;
    }
}
