package com.digitalgroup.holape.domain.common.entity;

import com.digitalgroup.holape.domain.common.enums.Status;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Setting Entity
 * Equivalent to Rails Setting model
 * Global system configuration values
 */
@Entity
@Table(name = "settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Setting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "localized_name", nullable = false)
    private String localizedName;

    @Column(name = "internal")
    @Builder.Default
    private Boolean internal = false;

    @Column(name = "data_type", nullable = false)
    private Integer dataType;

    @Column(name = "string_value")
    private String stringValue;

    @Column(name = "integer_value")
    private Integer integerValue;

    @Column(name = "float_value")
    private Double floatValue;

    @Column(name = "datetime_value")
    private LocalDateTime datetimeValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hash_value", columnDefinition = "jsonb")
    private Map<String, Object> hashValue;

    @Column(name = "boolean_value")
    private Boolean booleanValue;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "status")
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (internal == null) internal = false;
        if (status == null) status = Status.ACTIVE;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Data type constants matching Rails
     */
    public static final int DATA_TYPE_STRING = 0;
    public static final int DATA_TYPE_INTEGER = 1;
    public static final int DATA_TYPE_FLOAT = 2;
    public static final int DATA_TYPE_DATETIME = 3;
    public static final int DATA_TYPE_HASH = 4;
    public static final int DATA_TYPE_BOOLEAN = 5;

    /**
     * Get the value based on data_type
     */
    public Object getValue() {
        if (dataType == null) return stringValue;

        return switch (dataType) {
            case DATA_TYPE_STRING -> stringValue;
            case DATA_TYPE_INTEGER -> integerValue;
            case DATA_TYPE_FLOAT -> floatValue;
            case DATA_TYPE_DATETIME -> datetimeValue;
            case DATA_TYPE_HASH -> hashValue;
            case DATA_TYPE_BOOLEAN -> booleanValue;
            default -> stringValue;
        };
    }

    /**
     * Set value and update data_type accordingly
     */
    public void setValue(Object value) {
        if (value == null) return;

        if (value instanceof String) {
            this.stringValue = (String) value;
            this.dataType = DATA_TYPE_STRING;
        } else if (value instanceof Integer) {
            this.integerValue = (Integer) value;
            this.dataType = DATA_TYPE_INTEGER;
        } else if (value instanceof Double || value instanceof Float) {
            this.floatValue = ((Number) value).doubleValue();
            this.dataType = DATA_TYPE_FLOAT;
        } else if (value instanceof Boolean) {
            this.booleanValue = (Boolean) value;
            this.dataType = DATA_TYPE_BOOLEAN;
        } else if (value instanceof LocalDateTime) {
            this.datetimeValue = (LocalDateTime) value;
            this.dataType = DATA_TYPE_DATETIME;
        } else if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapValue = (Map<String, Object>) value;
            this.hashValue = mapValue;
            this.dataType = DATA_TYPE_HASH;
        }
    }
}
