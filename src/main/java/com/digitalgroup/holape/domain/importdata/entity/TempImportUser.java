package com.digitalgroup.holape.domain.importdata.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * TempImportUser Entity
 * Equivalent to Rails TempImportUser model
 * Staging table for user imports before validation and creation
 */
@Entity
@Table(name = "temp_import_users", indexes = {
        @Index(name = "index_temp_import_users_on_processed", columnList = "processed"),
        @Index(name = "index_temp_import_users_on_phone", columnList = "phone")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TempImportUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_import_id")
    private Import userImport;

    @Column(name = "codigo")
    private String codigo;

    @Column(name = "email")
    private String email;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "phone_code")
    private String phoneCode;

    @Column(name = "phone")
    private String phone;

    @Column(name = "role")
    private String role;

    @Column(name = "manager_email")
    private String managerEmail;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_fields", columnDefinition = "jsonb")
    private Map<String, Object> customFields;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "crm_fields", columnDefinition = "jsonb")
    private Map<String, String> crmFields;

    @Column(name = "processed")
    @Builder.Default
    private Boolean processed = false;

    @Column(name = "phone_order")
    private Integer phoneOrder;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (processed == null) processed = false;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Get full name
     */
    public String getFullName() {
        if (firstName == null && lastName == null) return "";
        if (firstName == null) return lastName;
        if (lastName == null) return firstName;
        return firstName + " " + lastName;
    }

    /**
     * Check if has errors
     */
    public boolean hasError() {
        return errorMessage != null && !errorMessage.isEmpty();
    }

    /**
     * Add error message
     */
    public void addError(String error) {
        if (this.errorMessage == null || this.errorMessage.isEmpty()) {
            this.errorMessage = error;
        } else {
            this.errorMessage += "; " + error;
        }
    }

    /**
     * Get normalized phone (remove leading zeros, add country code if needed)
     */
    public String getNormalizedPhone() {
        if (phone == null) return null;

        String normalized = phone.replaceAll("[^0-9]", "");

        // Remove leading zeros
        while (normalized.startsWith("0")) {
            normalized = normalized.substring(1);
        }

        // Add phone code if provided and phone doesn't start with it
        if (phoneCode != null && !phoneCode.isEmpty()) {
            String code = phoneCode.replaceAll("[^0-9]", "");
            if (!normalized.startsWith(code)) {
                normalized = code + normalized;
            }
        }

        return normalized;
    }
}
