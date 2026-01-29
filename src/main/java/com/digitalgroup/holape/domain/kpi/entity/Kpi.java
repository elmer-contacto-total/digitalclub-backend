package com.digitalgroup.holape.domain.kpi.entity;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.common.enums.KpiType;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import com.digitalgroup.holape.domain.user.entity.User;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "kpis", indexes = {
    @Index(name = "index_kpis_on_client_id", columnList = "client_id"),
    @Index(name = "index_kpis_on_user_id", columnList = "user_id"),
    @Index(name = "index_kpis_on_kpi_type", columnList = "kpi_type"),
    @Index(name = "index_kpis_on_ticket_id", columnList = "ticket_id"),
    @Index(name = "index_kpis_on_created_at", columnList = "created_at"),
    @Index(name = "index_kpis_on_client_id_and_kpi_type_and_created_at", columnList = "client_id, kpi_type, created_at"),
    @Index(name = "index_kpis_on_user_id_and_kpi_type_and_created_at", columnList = "user_id, kpi_type, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Kpi {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "kpi_type", columnDefinition = "integer default 0")
    @Builder.Default
    private KpiType kpiType = KpiType.NEW_CLIENT;

    @Column(name = "value")
    @Builder.Default
    private Integer value = 1;

    @Type(JsonType.class)
    @Column(name = "data_hash", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> dataHash = new HashMap<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private Ticket ticket;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
