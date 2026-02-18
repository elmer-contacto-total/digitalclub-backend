package com.digitalgroup.holape.domain.importdata.entity;

import com.digitalgroup.holape.domain.audit.annotation.Auditable;
import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.common.enums.ImportStatus;
import com.digitalgroup.holape.domain.common.enums.ImportType;
import com.digitalgroup.holape.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Import entity matching Rails imports table schema exactly.
 *
 * Rails schema:
 * - user_id: bigint (FK to users)
 * - client_id: bigint (FK to clients)
 * - import_type: integer (enum: users=0)
 * - import_file_data: text (Shrine attachment data)
 * - tot_records: integer
 * - status: integer (enum)
 * - progress: integer
 * - errors_text: text
 */
@Auditable
@Entity
@Table(name = "imports", indexes = {
    @Index(name = "index_imports_on_user_id", columnList = "user_id"),
    @Index(name = "index_imports_on_client_id", columnList = "client_id")
})
@Getter
@Setter
@NoArgsConstructor
public class Import {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "import_type", nullable = false)
    private ImportType importType = ImportType.USERS;

    @Column(name = "import_file_data", columnDefinition = "text")
    private String importFileData;

    @Column(name = "tot_records")
    private Integer totRecords;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "status")
    private ImportStatus status = ImportStatus.STATUS_NEW;

    @Column(name = "progress")
    private Integer progress = 0;

    @Column(name = "errors_text", columnDefinition = "text")
    private String errorsText;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Helper methods
    public void setProgress(int value) {
        this.progress = value;
    }

    public int calculateProgress() {
        if (totRecords == null || totRecords == 0) return 0;
        return (progress * 100) / totRecords;
    }

    public boolean isComplete() {
        return status == ImportStatus.STATUS_COMPLETED || status == ImportStatus.STATUS_ERROR;
    }

    public boolean isValid() {
        return status == ImportStatus.STATUS_VALID;
    }

    public boolean isProcessing() {
        return status == ImportStatus.STATUS_PROCESSING;
    }

    public boolean isValidating() {
        return status == ImportStatus.STATUS_VALIDATING;
    }
}
