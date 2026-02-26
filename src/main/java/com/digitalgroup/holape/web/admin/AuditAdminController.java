package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.audit.entity.Audit;
import com.digitalgroup.holape.domain.audit.service.AuditService;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Audit Admin Controller
 * Equivalent to Rails Admin::AuditsController
 * Provides audit trail viewing and export
 */
@Slf4j
@RestController
@RequestMapping("/app/audits")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
@Transactional(readOnly = true)
public class AuditAdminController {

    private final AuditService auditService;

    /**
     * List audits with search/filter support.
     * PARIDAD RAILS: Rails uses DataTables client-side search; this provides server-side equivalent.
     *
     * @param search        Free text search across username, auditable_type, action, auditable_id, audited_changes
     * @param auditableType Filter by entity type (e.g. "User", "Ticket", "Client")
     * @param action        Filter by action: "create", "update", "destroy"
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> index(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String auditableType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("created_at").descending());

        // Default date range: last 30 days
        LocalDateTime start = startDate != null ?
                startDate.atStartOfDay() :
                LocalDateTime.now().minusDays(30);
        LocalDateTime end = endDate != null ?
                endDate.atTime(LocalTime.MAX) :
                LocalDateTime.now();

        Long clientId = currentUser.getClientId();

        Page<Audit> auditsPage = auditService.searchAudits(
                start, end, clientId, auditableType, action, search, pageable);

        List<Map<String, Object>> data = auditsPage.getContent().stream()
                .map(this::mapAuditToResponse)
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("audits", data);
        response.put("total", auditsPage.getTotalElements());
        response.put("page", page);
        response.put("totalPages", auditsPage.getTotalPages());

        return ResponseEntity.ok(response);
    }

    /**
     * Get single audit
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> show(@PathVariable Long id) {
        // Would need to implement findById in service
        return ResponseEntity.ok(Map.of());
    }

    /**
     * Get audits for specific entity
     */
    @GetMapping("/entity/{type}/{id}")
    public ResponseEntity<Map<String, Object>> byEntity(
            @PathVariable String type,
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Audit> auditsPage = auditService.findByEntity(type, id, pageable);

        List<Map<String, Object>> data = auditsPage.getContent().stream()
                .map(this::mapAuditToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "audits", data,
                "total", auditsPage.getTotalElements()
        ));
    }

    /**
     * Export audits to CSV (supports same search filters as index)
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportAudits(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String auditableType,
            @RequestParam(required = false) String action) {

        LocalDateTime start = startDate != null ?
                startDate.atStartOfDay() :
                LocalDateTime.now().minusDays(30);
        LocalDateTime end = endDate != null ?
                endDate.atTime(LocalTime.MAX) :
                LocalDateTime.now();

        Pageable pageable = PageRequest.of(0, 10000, Sort.by("created_at").descending());
        Long clientId = currentUser.getClientId();

        Page<Audit> auditsPage = auditService.searchAudits(
                start, end, clientId, auditableType, action, search, pageable);

        // Generate CSV
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // Add BOM for Excel
        try {
            baos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

            PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));

            // Header
            writer.println("Cliente,ID,Fecha,Usuario,Acción,Tipo,ID Entidad,Cambios");

            // Data
            for (Audit audit : auditsPage.getContent()) {
                String clientName = "";
                String userName = audit.getUsername() != null ? audit.getUsername() : "";
                if (audit.getUser() != null) {
                    userName = audit.getUser().getFullName();
                    if (audit.getUser().getClient() != null) {
                        clientName = audit.getUser().getClient().getName();
                    }
                }
                writer.printf("\"%s\",%d,%s,%s,%s,%s,%s,\"%s\"%n",
                        clientName.replace("\"", "'"),
                        audit.getId(),
                        audit.getCreatedAt(),
                        userName,
                        audit.getAction(),
                        audit.getAuditableType(),
                        audit.getAuditableId() != null ? String.valueOf(audit.getAuditableId()) : "",
                        audit.getAuditedChanges() != null ?
                                audit.getAuditedChanges().toString().replace("\"", "'") : ""
                );
            }

            writer.flush();
            writer.close();
        } catch (Exception e) {
            log.error("Error generating CSV", e);
            return ResponseEntity.internalServerError().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.setContentDispositionFormData("attachment",
                String.format("audits_%s_%s.csv",
                        start.toLocalDate(),
                        end.toLocalDate()));

        return ResponseEntity.ok()
                .headers(headers)
                .body(baos.toByteArray());
    }

    /**
     * Get available auditable types
     */
    @GetMapping("/types")
    public ResponseEntity<Map<String, Object>> getTypes() {
        List<String> types = auditService.getAuditableTypes();
        return ResponseEntity.ok(Map.of("types", types));
    }

    private Map<String, Object> mapAuditToResponse(Audit audit) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", audit.getId());
        map.put("auditable_id", audit.getAuditableId());
        map.put("auditable_type", audit.getAuditableType());
        map.put("action", audit.getAction());
        map.put("username", audit.getUsername());
        map.put("audited_changes", audit.getAuditedChanges());
        map.put("version", audit.getVersion());
        map.put("created_at", audit.getCreatedAt());

        if (audit.getUser() != null) {
            map.put("user_id", audit.getUser().getId());
            map.put("user_name", audit.getUser().getFullName());
            if (audit.getUser().getClient() != null) {
                map.put("client_name", audit.getUser().getClient().getName());
            }
        }

        return map;
    }
}
