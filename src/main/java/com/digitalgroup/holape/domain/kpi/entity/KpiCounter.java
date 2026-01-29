package com.digitalgroup.holape.domain.kpi.entity;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.common.enums.KpiType;
import com.digitalgroup.holape.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "kpi_counters", indexes = {
    @Index(name = "index_kpi_counters_on_client_id", columnList = "client_id"),
    @Index(name = "index_kpi_counters_on_user_id", columnList = "user_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "index_kpi_counters_on_user_id_and_kpi_type", columnNames = {"user_id", "kpi_type"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KpiCounter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "kpi_type", nullable = false)
    private KpiType kpiType;

    @Column(nullable = false)
    @Builder.Default
    private Integer count = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void increment() {
        this.count++;
    }

    public void decrement() {
        if (this.count > 0) {
            this.count--;
        }
    }
}
