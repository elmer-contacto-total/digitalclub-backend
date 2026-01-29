package com.digitalgroup.holape.domain.audit.entity;

import com.digitalgroup.holape.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Audit entity
 * Equivalent to Rails Audit model (audited gem)
 * Tracks changes to auditable entities
 */
@Entity
@Table(name = "audits")
@Getter
@Setter
@NoArgsConstructor
public class Audit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auditable_id")
    private Long auditableId;

    @Column(name = "auditable_type")
    private String auditableType;

    @Column(name = "associated_id")
    private Long associatedId;

    @Column(name = "associated_type")
    private String associatedType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "user_type")
    private String userType;

    @Column(name = "username")
    private String username;

    @Column(name = "action")
    private String action; // create, update, destroy

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audited_changes", columnDefinition = "jsonb")
    private Map<String, Object> auditedChanges;

    @Column(name = "version")
    private Integer version = 0;

    @Column(name = "comment")
    private String comment;

    @Column(name = "remote_address")
    private String remoteAddress;

    @Column(name = "request_uuid")
    private String requestUuid;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /**
     * Create audit for entity creation
     */
    public static Audit forCreate(Object entity, User user, Map<String, Object> newValues) {
        Audit audit = new Audit();
        audit.setAuditableType(entity.getClass().getSimpleName());
        audit.setAction("create");
        audit.setUser(user);
        audit.setUsername(user != null ? user.getEmail() : null);
        audit.setAuditedChanges(newValues);
        return audit;
    }

    /**
     * Create audit for entity update
     */
    public static Audit forUpdate(Object entity, Long entityId, User user,
                                   Map<String, Object> changes, Integer version) {
        Audit audit = new Audit();
        audit.setAuditableType(entity.getClass().getSimpleName());
        audit.setAuditableId(entityId);
        audit.setAction("update");
        audit.setUser(user);
        audit.setUsername(user != null ? user.getEmail() : null);
        audit.setAuditedChanges(changes);
        audit.setVersion(version);
        return audit;
    }

    /**
     * Create audit for entity deletion
     */
    public static Audit forDestroy(Object entity, Long entityId, User user,
                                    Map<String, Object> oldValues) {
        Audit audit = new Audit();
        audit.setAuditableType(entity.getClass().getSimpleName());
        audit.setAuditableId(entityId);
        audit.setAction("destroy");
        audit.setUser(user);
        audit.setUsername(user != null ? user.getEmail() : null);
        audit.setAuditedChanges(oldValues);
        return audit;
    }
}
