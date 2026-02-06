package com.digitalgroup.holape.domain.app.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * AppVersion entity for tracking application versions and updates.
 * Used by Electron app to check for new versions.
 */
@Entity
@Table(name = "app_versions", indexes = {
    @Index(name = "idx_app_versions_platform_active", columnList = "platform, active")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_app_versions_version_platform", columnNames = {"version", "platform"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String version;

    @Column(name = "download_url", nullable = false, length = 500)
    private String downloadUrl;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String platform = "windows";

    @Column(name = "release_notes", columnDefinition = "text")
    private String releaseNotes;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "sha256_hash", length = 64)
    private String sha256Hash;

    @Column(name = "s3_key", length = 500)
    private String s3Key;

    @Column(nullable = false)
    @Builder.Default
    private Boolean mandatory = false;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
