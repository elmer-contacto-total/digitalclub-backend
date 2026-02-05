package com.digitalgroup.holape.api.v1.dto;

/**
 * DTO for app version information returned by the API.
 */
public record AppVersionDto(
    String version,
    String downloadUrl,
    String platform,
    String releaseNotes,
    Long fileSize,
    boolean mandatory,
    String publishedAt
) {
    /**
     * Factory method to create from entity.
     */
    public static AppVersionDto from(com.digitalgroup.holape.domain.app.entity.AppVersion entity) {
        return new AppVersionDto(
            entity.getVersion(),
            entity.getDownloadUrl(),
            entity.getPlatform(),
            entity.getReleaseNotes(),
            entity.getFileSize(),
            Boolean.TRUE.equals(entity.getMandatory()),
            entity.getPublishedAt() != null ? entity.getPublishedAt().toString() : null
        );
    }
}
