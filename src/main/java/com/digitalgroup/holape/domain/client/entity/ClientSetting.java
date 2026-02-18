package com.digitalgroup.holape.domain.client.entity;

import com.digitalgroup.holape.domain.audit.annotation.Auditable;
import com.digitalgroup.holape.domain.audit.listener.AuditEntityListener;
import com.digitalgroup.holape.domain.common.enums.Status;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Auditable
@EntityListeners(AuditEntityListener.class)
@Entity
@Table(name = "client_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(nullable = false)
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

    @Type(JsonType.class)
    @Column(name = "hash_value", columnDefinition = "jsonb")
    private Object hashValue;

    @Column(name = "boolean_value")
    private Boolean booleanValue;

    @Enumerated(EnumType.ORDINAL)
    @Column(columnDefinition = "integer default 0")
    @Builder.Default
    private Status status = Status.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Helper methods to get value based on data_type
    public Object getValue() {
        return switch (dataType) {
            case 0 -> stringValue;
            case 1 -> integerValue;
            case 2 -> floatValue;
            case 3 -> datetimeValue;
            case 4 -> hashValue;
            case 5 -> booleanValue;
            default -> stringValue;
        };
    }
}
