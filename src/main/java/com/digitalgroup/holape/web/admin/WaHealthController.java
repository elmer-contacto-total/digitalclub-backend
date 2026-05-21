package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;

/**
 * WhatsApp DOM health probe reports.
 * Agents POST their probe snapshot; SUPER_ADMIN reads all snapshots.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WaHealthController {

    private final ConcurrentHashMap<Long, Map<String, Object>> reports = new ConcurrentHashMap<>();

    @PostMapping("/app/wa_health_report")
    public ResponseEntity<Map<String, String>> report(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody Map<String, Object> body) {

        body.put("userId", currentUser.getId());
        body.put("userName", currentUser.getFullName());
        body.put("userEmail", currentUser.getEmail());
        reports.put(currentUser.getId(), body);
        log.debug("[WaHealth] Reporte recibido de userId={}", currentUser.getId());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/app/wa_health_reports")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> index() {
        return ResponseEntity.ok(new ArrayList<>(reports.values()));
    }
}
