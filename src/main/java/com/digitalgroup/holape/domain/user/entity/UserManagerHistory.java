package com.digitalgroup.holape.domain.user.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * User Manager History entity
 * Equivalent to Rails UserManagerHistory model
 * Tracks manager changes for audit trail
 */
@Entity
@Table(name = "user_manager_histories", indexes = {
        @Index(name = "index_user_manager_histories_on_user_id", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
public class UserManagerHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "old_manager_id")
    private User oldManager;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "new_manager_id")
    private User newManager;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

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

    /**
     * Create a new manager history record
     */
    public static UserManagerHistory create(User user, User oldManager, User newManager, String comment) {
        UserManagerHistory history = new UserManagerHistory();
        history.setUser(user);
        history.setOldManager(oldManager);
        history.setNewManager(newManager);
        history.setComment(comment);
        return history;
    }
}
