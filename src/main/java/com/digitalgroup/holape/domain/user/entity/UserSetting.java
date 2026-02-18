package com.digitalgroup.holape.domain.user.entity;

import com.digitalgroup.holape.domain.audit.annotation.Auditable;
import com.digitalgroup.holape.domain.audit.listener.AuditEntityListener;
import com.digitalgroup.holape.domain.common.enums.Status;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Auditable
@EntityListeners(AuditEntityListener.class)
@Entity
@Table(name = "user_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

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
}
