package com.digitalgroup.holape.domain.crm.entity;

import com.digitalgroup.holape.domain.client.entity.Client;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * CRM Info Setting entity
 * Equivalent to Rails CrmInfoSetting model
 * Defines custom CRM fields per client
 */
@Entity
@Table(name = "crm_info_settings")
@Getter
@Setter
@NoArgsConstructor
public class CrmInfoSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    @Column(name = "column_label")
    private String columnLabel;

    @Column(name = "column_position")
    private Integer columnPosition;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "column_type")
    private ColumnType columnType = ColumnType.TEXT;

    @Column(name = "column_visible")
    private Boolean columnVisible = true;

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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum ColumnType {
        TEXT,    // 0
        NUMBER,  // 1
        DATE,    // 2
        BOOLEAN  // 3
    }

    public enum Status {
        ACTIVE,   // 0
        INACTIVE  // 1
    }
}
