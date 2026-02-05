package com.digitalgroup.holape.api.v1;

import com.digitalgroup.holape.api.v1.dto.AppVersionDto;
import com.digitalgroup.holape.domain.app.entity.AppVersion;
import com.digitalgroup.holape.domain.app.service.AppVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Public API controller for app version checking.
 * Used by Electron app to check for updates.
 *
 * All endpoints in this controller are PUBLIC (no authentication required).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/app")
@RequiredArgsConstructor
public class AppVersionController {

    private final AppVersionService appVersionService;

    /**
     * Get the latest version information for a platform.
     *
     * @param platform The platform (windows, mac, linux). Defaults to windows.
     * @return Latest version info or 404 if no version found
     */
    @GetMapping("/version")
    public ResponseEntity<?> getLatestVersion(
            @RequestParam(defaultValue = "windows") String platform) {

        log.info("Version check requested for platform: {}", platform);

        Optional<AppVersion> latest = appVersionService.getLatestVersion(platform);

        if (latest.isEmpty()) {
            log.warn("No active version found for platform: {}", platform);
            return ResponseEntity.notFound().build();
        }

        AppVersionDto dto = AppVersionDto.from(latest.get());
        return ResponseEntity.ok(dto);
    }

    /**
     * Check if an update is available for the given version.
     *
     * @param currentVersion The current version of the app
     * @param platform The platform (windows, mac, linux). Defaults to windows.
     * @return Update check result with updateAvailable flag and latest version info
     */
    @GetMapping("/version/check")
    public ResponseEntity<Map<String, Object>> checkForUpdate(
            @RequestParam String currentVersion,
            @RequestParam(defaultValue = "windows") String platform) {

        log.info("Update check: currentVersion={}, platform={}", currentVersion, platform);

        Map<String, Object> response = new HashMap<>();

        Optional<AppVersion> latest = appVersionService.getLatestVersion(platform);

        if (latest.isEmpty()) {
            response.put("updateAvailable", false);
            response.put("message", "No version information available");
            return ResponseEntity.ok(response);
        }

        boolean updateAvailable = appVersionService.isUpdateAvailable(currentVersion, platform);

        response.put("updateAvailable", updateAvailable);
        response.put("currentVersion", currentVersion);

        if (updateAvailable) {
            response.put("latestVersion", AppVersionDto.from(latest.get()));
            log.info("Update available: {} -> {}", currentVersion, latest.get().getVersion());
        } else {
            response.put("message", "You are running the latest version");
        }

        return ResponseEntity.ok(response);
    }
}
