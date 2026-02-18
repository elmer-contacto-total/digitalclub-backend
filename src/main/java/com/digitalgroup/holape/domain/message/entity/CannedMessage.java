package com.digitalgroup.holape.domain.message.entity;

import com.digitalgroup.holape.domain.audit.annotation.Auditable;
import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Canned Message entity
 * Equivalent to Rails CannedMessage model
 * Predefined response messages for quick agent replies
 *
 * PARIDAD RAILS: schema.rb líneas 67-77
 * Campos: client_id, user_id, message, client_global, status, created_at, updated_at
 */
@Auditable
@Entity
@Table(name = "canned_messages", indexes = {
        @Index(name = "index_canned_messages_on_client_id", columnList = "client_id"),
        @Index(name = "index_canned_messages_on_user_id", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
public class CannedMessage {

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

    /**
     * Check if message is active
     */
    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    /**
     * Check if message is global (visible to all users in client)
     */
    public boolean isGlobal() {
        return Boolean.TRUE.equals(clientGlobal);
    }
}
