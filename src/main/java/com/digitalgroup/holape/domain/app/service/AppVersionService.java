package com.digitalgroup.holape.domain.app.service;

import com.digitalgroup.holape.domain.app.entity.AppVersion;
import com.digitalgroup.holape.domain.app.repository.AppVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Service for managing app versions and update checks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppVersionService {

    private final AppVersionRepository appVersionRepository;

    /**
     * Get the latest active version for a platform.
     *
     * @param platform The platform (windows, mac, linux)
     * @return Optional containing the latest version if found
     */
    public Optional<AppVersion> getLatestVersion(String platform) {
        return appVersionRepository.findFirstByPlatformAndActiveOrderByPublishedAtDesc(
            platform.toLowerCase(),
            true
        );
    }

    /**
     * Compare two semantic version strings.
     *
     * @param v1 First version (e.g., "1.0.0")
     * @param v2 Second version (e.g., "1.0.1")
     * @return Negative if v1 < v2, positive if v1 > v2, zero if equal
     */
    public int compareVersions(String v1, String v2) {
        if (v1 == null || v2 == null) {
            throw new IllegalArgumentException("Version strings cannot be null");
        }

        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");

        int length = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < length; i++) {
            int num1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int num2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;

            if (num1 != num2) {
                return num1 - num2;
            }
        }

        return 0;
    }

    /**
     * Check if a newer version is available.
     *
     * @param currentVersion The current version of the app
     * @param platform The platform to check
     * @return true if a newer version is available
     */
    public boolean isUpdateAvailable(String currentVersion, String platform) {
        Optional<AppVersion> latest = getLatestVersion(platform);

        if (latest.isEmpty()) {
            return false;
        }

        try {
            return compareVersions(currentVersion, latest.get().getVersion()) < 0;
        } catch (Exception e) {
            log.warn("Error comparing versions: {} vs {}", currentVersion, latest.get().getVersion(), e);
            return false;
        }
    }

    /**
     * Parse a version part, handling non-numeric suffixes like "1-beta".
     */
    private int parseVersionPart(String part) {
        // Remove any non-numeric suffix (e.g., "-beta", "-rc1")
        String numericPart = part.replaceAll("[^0-9].*", "");
        if (numericPart.isEmpty()) {
            return 0;
        }
        return Integer.parseInt(numericPart);
    }
}
