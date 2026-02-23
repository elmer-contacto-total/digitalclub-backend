package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.alert.entity.Alert;
import com.digitalgroup.holape.domain.alert.service.AlertService;
import com.digitalgroup.holape.domain.common.enums.AlertType;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Alert Admin Controller
 * Equivalent to Rails Admin::AlertsController
 * Manages alerts for require response, escalations, etc.
 */
@Slf4j
@RestController
@RequestMapping("/app/alerts")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AlertAdminController {

    private final AlertService alertService;

    /**
     * List alerts
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> index(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) String type,
            @RequestParam(required = false, defaultValue = "false") boolean acknowledged,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        Page<Alert> alertsPage;
        if (type != null && !type.isEmpty()) {
            AlertType alertType = AlertType.valueOf(type.toUpperCase());
            alertsPage = alertService.findByClientAndType(
                    currentUser.getClientId(), alertType, acknowledged, pageable);
        } else {
            alertsPage = alertService.findByClient(
                    currentUser.getClientId(), acknowledged, pageable);
        }

        List<Map<String, Object>> data = alertsPage.getContent().stream()
                .map(this::mapAlertToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "alerts", data,
                "total", alertsPage.getTotalElements(),
                "page", page,
                "totalPages", alertsPage.getTotalPages()
        ));
    }

    /**
     * Get alert by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> show(@PathVariable Long id) {
        Alert alert = alertService.findById(id);
        return ResponseEntity.ok(mapAlertToResponse(alert));
    }

    /**
     * Get unacknowledged alert count
     */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> count(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        long count = alertService.countUnacknowledged(currentUser.getClientId());

        return ResponseEntity.ok(Map.of(
                "count", count
        ));
    }

    /**
     * Acknowledge alert
     */
    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledge(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        Alert alert = alertService.acknowledgeAlert(id, currentUser.getId());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "alert", mapAlertToResponse(alert)
        ));
    }

    /**
     * Acknowledge multiple alerts
     */
    @PostMapping("/acknowledge_bulk")
    public ResponseEntity<Map<String, Object>> acknowledgeBulk(
            @RequestBody AcknowledgeBulkRequest request,
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        int count = alertService.acknowledgeAlerts(request.alertIds(), currentUser.getId());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "acknowledged_count", count
        ));
    }

    /**
     * Get alerts for specific ticket
     */
    @GetMapping("/ticket/{ticketId}")
    public ResponseEntity<List<Map<String, Object>>> getByTicket(@PathVariable Long ticketId) {
        List<Alert> alerts = alertService.findByTicket(ticketId);

        List<Map<String, Object>> data = alerts.stream()
                .map(this::mapAlertToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(data);
    }

    /**
     * Get alerts for specific user (agent)
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<Map<String, Object>> getByUser(
            @PathVariable Long userId,
            @RequestParam(required = false, defaultValue = "false") boolean acknowledged,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Alert> alertsPage = alertService.findByUser(userId, acknowledged, pageable);

        List<Map<String, Object>> data = alertsPage.getContent().stream()
                .map(this::mapAlertToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "alerts", data,
                "total", alertsPage.getTotalElements(),
                "page", page,
                "totalPages", alertsPage.getTotalPages()
        ));
    }

    /**
     * Map Alert to response
     * PARIDAD RAILS: body en lugar de message, read en lugar de acknowledged,
     * url almacena ticket reference, no hay acknowledgedBy ni acknowledgedAt
     */
    private Map<String, Object> mapAlertToResponse(Alert alert) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", alert.getId());
        map.put("type", alert.getType().name().toLowerCase());
        map.put("severity", alert.getSeverity() != null ?
                alert.getSeverity().name().toLowerCase() : null);
        map.put("title", alert.getTitle());
        map.put("message", alert.getBody());  // PARIDAD: body en lugar de message
        map.put("acknowledged", alert.getRead());  // PARIDAD: read en lugar de acknowledged
        map.put("acknowledged_at", null);  // PARIDAD: no existe acknowledgedAt
        map.put("created_at", alert.getCreatedAt());

        // PARIDAD: ticket no existe como relación, extraer de URL si existe
        if (alert.getUrl() != null && alert.getUrl().startsWith("/tickets/")) {
            try {
                Long ticketId = Long.parseLong(alert.getUrl().substring("/tickets/".length()));
                map.put("ticket_id", ticketId);
            } catch (NumberFormatException e) {
                map.put("ticket_url", alert.getUrl());
            }
        }
        if (alert.getUser() != null) {
            map.put("user_id", alert.getUser().getId());
            map.put("user_name", alert.getUser().getFullName());
        }
        // PARIDAD: acknowledgedBy no existe

        return map;
    }

    public record AcknowledgeBulkRequest(List<Long> alertIds) {}
}
