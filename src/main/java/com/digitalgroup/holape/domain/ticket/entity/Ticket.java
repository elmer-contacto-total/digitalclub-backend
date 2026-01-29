package com.digitalgroup.holape.domain.ticket.entity;

import com.digitalgroup.holape.domain.common.enums.TicketStatus;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.user.entity.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tickets", indexes = {
    @Index(name = "index_tickets_on_user_id", columnList = "user_id"),
    @Index(name = "index_tickets_on_agent_id", columnList = "agent_id"),
    @Index(name = "index_tickets_on_agent_id_and_status_and_created_at", columnList = "agent_id, status, created_at"),
    @Index(name = "index_tickets_on_user_id_and_status_and_created_at", columnList = "user_id, status, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull(message = "User is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @NotNull(message = "Agent is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private User agent;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "subject")
    private String subject;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Enumerated(EnumType.ORDINAL)
    @Column(columnDefinition = "integer default 0")
    @Builder.Default
    private TicketStatus status = TicketStatus.OPEN;

    @Column(name = "close_type")
    private String closeType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL)
    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    // Helper methods
    public boolean isOpen() {
        return status == TicketStatus.OPEN;
    }

    public boolean isClosed() {
        return status == TicketStatus.CLOSED;
    }

    public void close(String closeType) {
        this.status = TicketStatus.CLOSED;
        this.closedAt = LocalDateTime.now();
        this.closeType = closeType;
    }

    public Long getClientId() {
        return user != null && user.getClient() != null ? user.getClient().getId() : null;
    }

    /**
     * Get the client (from agent, as agent is always an internal user)
     */
    public com.digitalgroup.holape.domain.client.entity.Client getClient() {
        return agent != null ? agent.getClient() : (user != null ? user.getClient() : null);
    }

    /**
     * Get count of messages in this ticket
     */
    public Integer getMessageCount() {
        return messages != null ? messages.size() : 0;
    }

    /**
     * Get duration in minutes (time ticket was open)
     */
    public Long getDurationMinutes() {
        if (createdAt == null) return 0L;
        LocalDateTime endTime = closedAt != null ? closedAt : LocalDateTime.now();
        return java.time.Duration.between(createdAt, endTime).toMinutes();
    }
}
