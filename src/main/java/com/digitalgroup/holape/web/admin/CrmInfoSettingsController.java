package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.crm.entity.CrmInfoSetting;
import com.digitalgroup.holape.domain.crm.service.CrmService;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CRM Info Settings Controller
 * Equivalent to Rails Admin::CrmInfoSettingsController
 * Manages custom CRM fields per client
 */
@Slf4j
@RestController
@RequestMapping("/app/crm_info_settings")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
public class CrmInfoSettingsController {

    private final CrmService crmService;

    /**
     * List CRM settings for current client
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> index(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        List<CrmInfoSetting> settings = crmService.getSettingsByClient(currentUser.getClientId());

        List<Map<String, Object>> data = settings.stream()
                .map(this::mapSettingToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "crm_info_settings", data
        ));
    }

    /**
     * Get single CRM setting
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> show(@PathVariable Long id) {
        CrmInfoSetting setting = crmService.findSettingById(id);
        return ResponseEntity.ok(mapSettingToResponse(setting));
    }

    /**
     * Create new CRM setting
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody CreateSettingRequest request) {

        CrmInfoSetting setting = crmService.createSetting(
                currentUser.getClientId(),
                request.columnLabel(),
                request.columnType() != null ?
                        CrmInfoSetting.ColumnType.valueOf(request.columnType().toUpperCase()) : null,
                request.columnVisible()
        );

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "crm_info_setting", mapSettingToResponse(setting)
        ));
    }

    /**
     * Update CRM setting
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody UpdateSettingRequest request) {

        CrmInfoSetting setting = crmService.updateSetting(
                id,
                request.columnLabel(),
                request.columnType() != null ?
                        CrmInfoSetting.ColumnType.valueOf(request.columnType().toUpperCase()) : null,
                request.columnVisible(),
                request.status() != null ?
                        CrmInfoSetting.Status.valueOf(request.status().toUpperCase()) : null
        );

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "crm_info_setting", mapSettingToResponse(setting)
        ));
    }

    /**
     * Delete CRM setting (soft delete)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> destroy(@PathVariable Long id) {
        crmService.deleteSetting(id);

        return ResponseEntity.ok(Map.of(
                "result", "success"
        ));
    }

    /**
     * Reorder CRM settings
     */
    @PostMapping("/reorder")
    public ResponseEntity<Map<String, Object>> reorder(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody ReorderRequest request) {

        crmService.reorderSettings(currentUser.getClientId(), request.settingIds());

        return ResponseEntity.ok(Map.of(
                "result", "success"
        ));
    }

    /**
     * Get available data fields for templates
     */
    @GetMapping("/available_fields")
    public ResponseEntity<Map<String, Object>> availableFields(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        List<Map<String, String>> fields = crmService.getAvailableDataFields(currentUser.getClientId());

        return ResponseEntity.ok(Map.of(
                "fields", fields
        ));
    }

    private Map<String, Object> mapSettingToResponse(CrmInfoSetting setting) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", setting.getId());
        map.put("column_label", setting.getColumnLabel());
        map.put("column_position", setting.getColumnPosition());
        map.put("column_type", setting.getColumnType().name().toLowerCase());
        map.put("column_visible", setting.getColumnVisible());
        map.put("status", setting.getStatus().name().toLowerCase());
        map.put("created_at", setting.getCreatedAt());
        map.put("updated_at", setting.getUpdatedAt());
        return map;
    }

    public record CreateSettingRequest(
            String columnLabel,
            String columnType,
            Boolean columnVisible
    ) {}

    public record UpdateSettingRequest(
            String columnLabel,
            String columnType,
            Boolean columnVisible,
            String status
    ) {}

    public record ReorderRequest(List<Long> settingIds) {}
}
