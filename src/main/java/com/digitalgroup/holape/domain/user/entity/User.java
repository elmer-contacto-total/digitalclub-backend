package com.digitalgroup.holape.domain.user.entity;

import com.digitalgroup.holape.domain.audit.annotation.Auditable;
import com.digitalgroup.holape.domain.audit.listener.AuditEntityListener;
import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.common.entity.Country;
import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.common.enums.UserRole;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Auditable(except = {"otp", "requireResponse", "requireCloseTicket", "lastMessageAt"})
@EntityListeners(AuditEntityListener.class)
@Entity
@Table(name = "users", indexes = {
    @Index(name = "index_users_on_email", columnList = "email", unique = true),
    @Index(name = "index_users_on_phone", columnList = "phone"),
    @Index(name = "index_users_on_uuid_token", columnList = "uuid_token", unique = true),
    @Index(name = "index_users_on_client_id_and_role", columnList = "client_id, role"),
    @Index(name = "index_users_on_manager_id", columnList = "manager_id"),
    @Index(name = "index_users_on_last_message_at", columnList = "last_message_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private User manager;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "encrypted_password", nullable = false)
    private String encryptedPassword;

    @Column(name = "reset_password_token")
    private String resetPasswordToken;

    @Column(name = "reset_password_sent_at")
    private LocalDateTime resetPasswordSentAt;

    @Column(name = "remember_created_at")
    private LocalDateTime rememberCreatedAt;

    @NotBlank(message = "First name is required")
    @Size(min = 1, max = 100, message = "First name must be between 1 and 100 characters")
    @Column(name = "first_name", nullable = false)
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(min = 1, max = 100, message = "Last name must be between 1 and 100 characters")
    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "username")
    private String username;

    @NotBlank(message = "Phone is required")
    @Pattern(regexp = "^[0-9+\\-\\s()]+$", message = "Invalid phone format")
    @Size(max = 20, message = "Phone must not exceed 20 characters")
    @Column(nullable = false)
    private String phone;

    @Column(name = "avatar_data", columnDefinition = "text")
    private String avatarData;

    @Column(name = "otp")
    private String otp;

    @Column(name = "fcm_push_token")
    private String fcmPushToken;

    @Column(name = "uuid_token", unique = true)
    private String uuidToken;

    @Column(name = "last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id")
    private Country country;

    @Column(name = "time_zone")
    @Builder.Default
    private String timeZone = "America/Lima";

    @NotNull(message = "Client is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    @Column(name = "locale")
    @Builder.Default
    private String locale = "es";

    @Column(name = "can_create_users")
    @Builder.Default
    private Boolean canCreateUsers = false;

    @NotNull(message = "Role is required")
    @Enumerated(EnumType.ORDINAL)
    @Column(columnDefinition = "integer default 0")
    @Builder.Default
    private UserRole role = UserRole.STANDARD;

    @Column(name = "temp_password")
    private String tempPassword;

    @Column(name = "initial_password_changed")
    @Builder.Default
    private Boolean initialPasswordChanged = false;

    @Enumerated(EnumType.ORDINAL)
    @Column(columnDefinition = "integer default 0")
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(name = "import_id")
    private Long importId;

    @Column(name = "codigo")
    private String codigo;

    @Type(JsonType.class)
    @Column(name = "custom_fields", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> customFields = new HashMap<>();

    @Column(name = "require_response")
    @Builder.Default
    private Boolean requireResponse = false;

    @Column(name = "require_close_ticket")
    @Builder.Default
    private Boolean requireCloseTicket = false;

    @Column(name = "import_string")
    private String importString;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "manager")
    @Builder.Default
    private List<User> subordinates = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<UserSetting> userSettings = new ArrayList<>();

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private UserProfile userProfile;

    // Helper methods
    public String getFullName() {
        String first = firstName != null ? firstName : "";
        String last = lastName != null ? lastName : "";
        return (first + " " + last).trim();
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public boolean isAdmin() {
        return role == UserRole.SUPER_ADMIN || role == UserRole.ADMIN;
    }

    public boolean isSuperAdmin() {
        return role == UserRole.SUPER_ADMIN;
    }

    public boolean isManager() {
        return role.isManager();
    }

    public boolean isAgent() {
        return role == UserRole.AGENT;
    }

    public boolean isStandard() {
        return role == UserRole.STANDARD;
    }

    public boolean isWhatsAppBusiness() {
        return role == UserRole.WHATSAPP_BUSINESS;
    }

    public boolean isInternal() {
        return role.isInternal();
    }

    public boolean hasTempPassword() {
        return tempPassword != null && !tempPassword.isEmpty() && !initialPasswordChanged;
    }

    public Long getClientId() {
        return client != null ? client.getId() : null;
    }

    /**
     * Get all subordinates recursively (including subordinates of subordinates)
     * Equivalent to Rails all_subordinates scope
     */
    public List<User> getAllSubordinates() {
        List<User> allSubordinates = new ArrayList<>();
        collectSubordinates(this, allSubordinates);
        return allSubordinates;
    }

    private void collectSubordinates(User user, List<User> collected) {
        if (user.getSubordinates() == null) return;
        for (User subordinate : user.getSubordinates()) {
            if (!collected.contains(subordinate)) {
                collected.add(subordinate);
                collectSubordinates(subordinate, collected);
            }
        }
    }

    /**
     * Get all managers in the hierarchy (up to root)
     * Equivalent to Rails managers_hierarchy
     */
    public List<User> getManagersHierarchy() {
        List<User> managers = new ArrayList<>();
        User current = this.manager;
        while (current != null) {
            managers.add(current);
            current = current.getManager();
        }
        return managers;
    }

    /**
     * Check if user can view another user based on hierarchy
     * Equivalent to Rails can_view? method
     */
    public boolean canView(User otherUser) {
        if (this.isSuperAdmin() || this.isAdmin()) {
            return true;
        }
        if (this.equals(otherUser)) {
            return true;
        }
        // Manager can view subordinates
        if (this.isManager() || this.isAgent()) {
            return this.getAllSubordinates().contains(otherUser);
        }
        return false;
    }

    /**
     * Get the depth of this user in the manager hierarchy
     */
    public int getHierarchyDepth() {
        int depth = 0;
        User current = this.manager;
        while (current != null) {
            depth++;
            current = current.getManager();
        }
        return depth;
    }

    /**
     * Check if this user requires a response from an agent
     */
    public boolean needsResponse() {
        return Boolean.TRUE.equals(requireResponse);
    }

    /**
     * Check if this user requires ticket closure
     */
    public boolean needsTicketClose() {
        return Boolean.TRUE.equals(requireCloseTicket);
    }

    // ==================== PARIDAD RAILS: Métodos de User.rb ====================

    /**
     * Returns name or email if name is blank
     * PARIDAD RAILS: user.rb líneas 220-226 - name_or_email
     */
    public String getNameOrEmail() {
        String name = getFullName();
        if (name == null || name.isBlank()) {
            return email;
        }
        return name;
    }

    /**
     * Returns campaign name based on manager's email
     * PARIDAD RAILS: user.rb líneas 129-138 - campaign_name
     */
    public String getCampaignName() {
        if (manager == null || manager.getEmail() == null) {
            return "";
        }
        return switch (manager.getEmail()) {
            case "jessica.bravo@somosoh.pe" -> "BIG TICKET";
            case "jesbrase08@gmail.com" -> "PREVENTIVA";
            default -> "";
        };
    }

    /**
     * Checks if this user is a subordinate of the given manager
     * PARIDAD RAILS: user.rb líneas 198-200 - is_subordinate_of?
     *
     * Returns true if passed manager is an agent OR if this user's manager_id matches the manager
     */
    public boolean isSubordinateOf(User passedManager) {
        if (passedManager == null) {
            return false;
        }
        return passedManager.isAgent() ||
               (this.manager != null && this.manager.getId().equals(passedManager.getId()));
    }

    /**
     * Checks if this user is a subordinate of a subordinate of the given manager
     * PARIDAD RAILS: user.rb líneas 202-204 - is_subordinate_of_subordinate_of?
     */
    public boolean isSubordinateOfSubordinateOf(User passedManager) {
        if (passedManager == null || this.manager == null) {
            return false;
        }
        return this.manager.getManager() != null &&
               this.manager.getManager().getId().equals(passedManager.getId());
    }
}
