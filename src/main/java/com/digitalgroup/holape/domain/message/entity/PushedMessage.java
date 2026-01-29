package com.digitalgroup.holape.domain.message.entity;

import com.digitalgroup.holape.domain.common.enums.MessageDirection;
import com.digitalgroup.holape.domain.common.enums.MessageStatus;
import com.digitalgroup.holape.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * PushedMessage Entity
 * Equivalent to Rails PushedMessage model
 * Queue for incoming messages before processing
 */
@Entity
@Table(name = "pushed_messages", indexes = {
        @Index(name = "index_pushed_messages_on_sender_id", columnList = "sender_id"),
        @Index(name = "index_pushed_messages_on_recipient_id", columnList = "recipient_id"),
        @Index(name = "index_pushed_messages_on_processed", columnList = "processed"),
        @Index(name = "index_pushed_messages_on_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PushedMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @Column(name = "new_sender_phone", columnDefinition = "text")
    private String newSenderPhone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @Column(name = "device_id")
    private Integer deviceId;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "direction")
    @Builder.Default
    private MessageDirection direction = MessageDirection.INCOMING;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "status")
    @Builder.Default
    private MessageStatus status = MessageStatus.PENDING;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "binary_content_data", columnDefinition = "text")
    private String binaryContentData;

    @Column(name = "whatsapp_business_routed")
    @Builder.Default
    private Boolean whatsappBusinessRouted = false;

    @Column(name = "original_whatsapp_business_recipient_id")
    private Integer originalWhatsappBusinessRecipientId;

    @Column(name = "is_event")
    @Builder.Default
    private Boolean isEvent = false;

    @Column(name = "processed")
    @Builder.Default
    private Boolean processed = false;

    @Column(name = "already_ignored")
    @Builder.Default
    private Boolean alreadyIgnored = false;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (direction == null) direction = MessageDirection.INCOMING;
        if (status == null) status = MessageStatus.PENDING;
        if (whatsappBusinessRouted == null) whatsappBusinessRouted = false;
        if (isEvent == null) isEvent = false;
        if (processed == null) processed = false;
        if (alreadyIgnored == null) alreadyIgnored = false;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Mark as processed
     */
    public void markProcessed() {
        this.processed = true;
    }

    /**
     * Mark as ignored
     */
    public void markIgnored() {
        this.alreadyIgnored = true;
    }

    /**
     * Check if this is a WhatsApp Business routed message
     */
    public boolean isWhatsAppBusinessMessage() {
        return Boolean.TRUE.equals(whatsappBusinessRouted);
    }
}
