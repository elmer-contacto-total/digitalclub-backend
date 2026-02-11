package com.digitalgroup.holape.domain.importdata.entity;

import com.digitalgroup.holape.domain.client.entity.Client;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ImportMappingTemplate entity
 * Stores saved column mapping templates per client.
 * When a user uploads a CSV with matching headers, the saved mapping is auto-applied.
 */
@Entity
@Table(name = "import_mapping_templates",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_import_mapping_templates_client_name",
                columnNames = {"client_id", "name"}
        ),
        indexes = {
                @Index(name = "idx_import_mapping_templates_client", columnList = "client_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class ImportMappingTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "is_foh")
    private Boolean isFoh = false;

    /**
     * Column mapping: header name -> field value
     * e.g. {"PHONE1": "phone", "APE_PAT": "last_name", "SALDO": "custom_field:SALDO"}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "column_mapping", columnDefinition = "jsonb", nullable = false)
    private Map<String, String> columnMapping;

    /**
     * Ordered list of CSV headers this template expects.
     * Used for matching: if uploaded CSV has the same set of headers (order-independent), template matches.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", columnDefinition = "jsonb", nullable = false)
    private List<String> headers;

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
}
