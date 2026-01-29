package com.digitalgroup.holape.job.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Persisted scheduled job for restart recovery
 * PARIDAD RAILS: Sidekiq persiste jobs en Redis para sobrevivir reinicios
 * Spring Boot usa esta tabla para el mismo propósito
 */
@Entity
@Table(name = "scheduled_jobs", indexes = {
    @Index(name = "idx_scheduled_jobs_status", columnList = "status"),
    @Index(name = "idx_scheduled_jobs_execute_at", columnList = "execute_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduledJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_name", nullable = false)
    private String jobName;

    @Column(name = "job_type", nullable = false)
    private String jobType;

    @Column(name = "job_data", columnDefinition = "text")
    private String jobData;

    @Column(name = "execute_at", nullable = false)
    private LocalDateTime executeAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private JobStatus status = JobStatus.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    public enum JobStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED
    }

    // Common job types
    public static final String TYPE_TICKET_ASSIGNMENT = "TICKET_ASSIGNMENT";
    public static final String TYPE_REQUIRE_RESPONSE_KPI = "REQUIRE_RESPONSE_KPI";
    public static final String TYPE_REQUIRE_RESPONSE_ALERT = "REQUIRE_RESPONSE_ALERT";
    public static final String TYPE_RECONSTRUCT_USER_FLAG = "RECONSTRUCT_USER_FLAG";
}
