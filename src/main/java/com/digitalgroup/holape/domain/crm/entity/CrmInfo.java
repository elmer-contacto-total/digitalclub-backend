package com.digitalgroup.holape.domain.crm.entity;

import com.digitalgroup.holape.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * CRM Info entity
 * Equivalent to Rails CrmInfo model
 * Stores custom CRM field values per user
 */
@Entity
@Table(name = "crm_infos", indexes = {
        @Index(name = "index_crm_infos_on_user_id", columnList = "user_id"),
        @Index(name = "index_crm_infos_on_crm_info_setting_id", columnList = "crm_info_setting_id")
})
@Getter
@Setter
@NoArgsConstructor
public class CrmInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "crm_info_setting_id", nullable = false)
    private CrmInfoSetting crmInfoSetting;

    @Column(name = "column_position", nullable = false)
    private Integer columnPosition;

    @Column(name = "column_label", nullable = false)
    private String columnLabel;

    @Column(name = "column_visible", nullable = false)
    private Boolean columnVisible = false;

    @Column(name = "column_value")
    private String columnValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (columnVisible == null) columnVisible = false;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Validates that the column value matches the expected type
     */
    public boolean isValueValid() {
        if (columnValue == null || columnValue.isEmpty()) {
            return true; // Empty values are allowed
        }

        if (crmInfoSetting == null) {
            return true;
        }

        CrmInfoSetting.ColumnType type = crmInfoSetting.getColumnType();

        return switch (type) {
            case NUMBER -> isNumeric(columnValue);
            case DATE -> isValidDate(columnValue);
            case BOOLEAN -> isValidBoolean(columnValue);
            case TEXT -> true;
        };
    }

    private boolean isNumeric(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isValidDate(String value) {
        try {
            java.time.LocalDate.parse(value);
            return true;
        } catch (Exception e) {
            try {
                java.time.LocalDateTime.parse(value);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    private boolean isValidBoolean(String value) {
        return "true".equalsIgnoreCase(value) ||
               "false".equalsIgnoreCase(value) ||
               "1".equals(value) ||
               "0".equals(value);
    }
}
