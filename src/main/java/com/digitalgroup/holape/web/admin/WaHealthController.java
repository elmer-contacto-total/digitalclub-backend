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

/**
 * WhatsApp DOM health probe reports.
 * Agents POST their probe snapshot; SUPER_ADMIN reads the roster + state.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WaHealthController {

    private final WaHealthService waHealthService;

    /** Tope defensivo de claves de primer nivel del body (el reporte real ronda ~7). */
    private static final int MAX_BODY_KEYS = 200;

    @PostMapping("/app/wa_health_report")
    public ResponseEntity<Map<String, String>> report(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody Map<String, Object> body) {

        if (body.size() > MAX_BODY_KEYS) {
            return ResponseEntity.badRequest().body(Map.of("status", "payload_too_large"));
        }

        body.put("userId", currentUser.getId());
        body.put("userName", currentUser.getFullName());
        body.put("userEmail", currentUser.getEmail());

        waHealthService.ingest(currentUser.getId(), currentUser.getClientId(), body);
        log.debug("[WaHealth] Reporte recibido de userId={}", currentUser.getId());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/app/wa_health_reports")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> index(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(name = "coverage", defaultValue = "false") boolean coverage) {
        return ResponseEntity.ok(waHealthService.reportsForScope(currentUser.getClientId(), coverage));
    }
}
