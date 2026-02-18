package com.digitalgroup.holape.domain.client.entity;

import com.digitalgroup.holape.domain.audit.annotation.Auditable;
import com.digitalgroup.holape.domain.common.entity.Country;
import com.digitalgroup.holape.domain.common.enums.ClientType;
import com.digitalgroup.holape.domain.common.enums.DocType;
import com.digitalgroup.holape.domain.common.enums.Status;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Auditable
@Entity
@Table(name = "clients")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must not exceed 255 characters")
    @Column(nullable = false)
    private String name;

    @NotBlank(message = "Company name is required")
    @Size(max = 255, message = "Company name must not exceed 255 characters")
    @Column(name = "company_name", nullable = false)
    private String companyName;

    @NotNull(message = "Document type is required")
    @Enumerated(EnumType.ORDINAL)
    @Column(name = "doc_type", nullable = false)
    private DocType docType;

    @NotBlank(message = "Document number is required")
    @Size(max = 50, message = "Document number must not exceed 50 characters")
    @Column(name = "doc_number", nullable = false)
    private String docNumber;

    @NotNull(message = "Country is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id", nullable = false)
    private Country country;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "client_type", columnDefinition = "integer default 0")
    @Builder.Default
    private ClientType clientType = ClientType.WHATSAPP_APP;

    @Column(name = "whatsapp_access_token")
    private String whatsappAccessToken;

    @Column(name = "whatsapp_business_id")
    private String whatsappBusinessId;

    @Column(name = "whatsapp_number")
    private String whatsappNumber;

    @Column(name = "whatsapp_account_review_status")
    private String whatsappAccountReviewStatus;

    @Column(name = "whatsapp_timezone_id")
    private String whatsappTimezoneId;

    @Column(name = "whatsapp_verified_name")
    private String whatsappVerifiedName;

    @Enumerated(EnumType.ORDINAL)
    @Column(columnDefinition = "integer default 0")
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(name = "domain_url")
    private String domainUrl;

    @Column(name = "logo_url")
    private String logoUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ClientSetting> clientSettings = new ArrayList<>();

    @OneToOne(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true)
    private ClientStructure clientStructure;

    public boolean isWhatsAppBusiness() {
        return clientType == ClientType.WHATSAPP_BUSINESS;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
