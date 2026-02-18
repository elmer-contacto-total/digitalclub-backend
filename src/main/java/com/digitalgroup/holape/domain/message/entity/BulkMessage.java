package com.digitalgroup.holape.domain.message.entity;

import com.digitalgroup.holape.domain.audit.annotation.Auditable;
import com.digitalgroup.holape.domain.audit.listener.AuditEntityListener;
import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Bulk Message entity
 * Equivalent to Rails BulkMessage model
 * Represents bulk message campaigns sent to multiple users
 */
@Auditable
@EntityListeners(AuditEntityListener.class)
@Entity
@Table(name = "bulk_messages", indexes = {
        @Index(name = "index_bulk_messages_on_client_id", columnList = "client_id"),
        @Index(name = "index_bulk_messages_on_user_id", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
public class BulkMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "message", nullable = false)
    private String message;

    @Column(name = "client_global")
    private Boolean clientGlobal = false;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "status")
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (clientGlobal == null) clientGlobal = false;
        if (status == null) status = Status.ACTIVE;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
