package com.digitalgroup.holape.domain.client.entity;

import com.digitalgroup.holape.domain.audit.annotation.Auditable;
import com.digitalgroup.holape.domain.audit.listener.AuditEntityListener;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Auditable
@EntityListeners(AuditEntityListener.class)
@Entity
@Table(name = "client_structures")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientStructure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(name = "manager_level_1")
    private String managerLevel1;

    @Column(name = "exists_manager_level_1")
    @Builder.Default
    private Boolean existsManagerLevel1 = false;

    @Column(name = "manager_level_2")
    private String managerLevel2;

    @Column(name = "exists_manager_level_2")
    @Builder.Default
    private Boolean existsManagerLevel2 = false;

    @Column(name = "manager_level_3")
    private String managerLevel3;

    @Column(name = "exists_manager_level_3")
    @Builder.Default
    private Boolean existsManagerLevel3 = false;

    @Column(name = "manager_level_4")
    @Builder.Default
    private String managerLevel4 = "Supervisor";

    @Column(name = "exists_manager_level_4")
    @Builder.Default
    private Boolean existsManagerLevel4 = true;

    @Column(name = "agent")
    @Builder.Default
    private String agent = "Sectorista";

    @Column(name = "exists_agent")
    @Builder.Default
    private Boolean existsAgent = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
