package com.digitalgroup.holape.domain.message.entity;

import com.digitalgroup.holape.domain.common.enums.Status;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "message_template_params", indexes = {
    @Index(name = "index_message_template_params_on_message_template_id", columnList = "message_template_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageTemplateParam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_template_id", nullable = false)
    private MessageTemplate messageTemplate;

    @Column(name = "component", columnDefinition = "integer default 0")
    @Builder.Default
    private Integer component = 0; // 0=header, 1=body, 2=footer

    @Column(nullable = false)
    private Integer position;

    @Column(name = "data_field")
    private String dataField;

    @Column(name = "default_value")
    private String defaultValue;

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
