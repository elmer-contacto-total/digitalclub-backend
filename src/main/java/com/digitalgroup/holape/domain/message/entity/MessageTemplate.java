package com.digitalgroup.holape.domain.message.entity;

import com.digitalgroup.holape.domain.audit.annotation.Auditable;
import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.common.entity.Language;
import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.common.enums.TemplateWhatsAppStatus;
import com.digitalgroup.holape.domain.user.entity.User;
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
@Table(name = "message_templates", indexes = {
    @Index(name = "index_message_templates_on_client_id", columnList = "client_id"),
    @Index(name = "index_message_templates_on_user_id", columnList = "user_id"),
    @Index(name = "index_message_templates_on_language_id", columnList = "language_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull(message = "User is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @NotNull(message = "Client is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @NotBlank(message = "Template name is required")
    @Size(max = 100, message = "Template name must not exceed 100 characters")
    @Column(nullable = false)
    private String name;

    @Column(name = "category", columnDefinition = "integer default 0")
    @Builder.Default
    private Integer category = 0;

    @Column(name = "template_whatsapp_type", columnDefinition = "integer default 0")
    @Builder.Default
    private Integer templateWhatsappType = 0;

    @NotNull(message = "Language is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "language_id", nullable = false)
    private Language language;

    @Column(name = "header_media_type", columnDefinition = "integer default 0")
    @Builder.Default
    private Integer headerMediaType = 0;

    @Column(name = "header_content")
    private String headerContent;

    @Column(name = "header_binary_data")
    private String headerBinaryData;

    @Column(name = "body_content")
    private String bodyContent;

    @Column(name = "footer_content")
    private String footerContent;

    @Column(name = "tot_buttons", columnDefinition = "integer default 0")
    @Builder.Default
    private Integer totButtons = 0;

    @Column(name = "closes_ticket")
    @Builder.Default
    private Boolean closesTicket = false;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "template_whatsapp_status", columnDefinition = "integer default 0")
    @Builder.Default
    private TemplateWhatsAppStatus templateWhatsappStatus = TemplateWhatsAppStatus.DRAFT;

    @Column(name = "visibility", columnDefinition = "integer default 0")
    @Builder.Default
    private Integer visibility = 0;

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

    @OneToMany(mappedBy = "messageTemplate", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<MessageTemplateParam> params = new ArrayList<>();

    public boolean isApproved() {
        return templateWhatsappStatus == TemplateWhatsAppStatus.APPROVED;
    }

    public boolean canSend() {
        return isApproved() && status == Status.ACTIVE;
    }
}
