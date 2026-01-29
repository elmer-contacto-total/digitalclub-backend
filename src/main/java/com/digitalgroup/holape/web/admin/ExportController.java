package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.export.service.ExportService;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Export Controller
 * Handles data export endpoints for CSV and ZIP downloads
 * Equivalent to Rails export functionality in various controllers
 */
@Slf4j
@RestController
@RequestMapping("/app/export")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2')")
public class ExportController {

    private final ExportService exportService;

    /**
     * Export users to CSV
     * GET /app/export/users.csv
     */
    @GetMapping(value = "/users.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportUsers(@AuthenticationPrincipal CustomUserDetails currentUser) {
        log.info("User {} exporting users CSV", currentUser.getId());

        byte[] content = exportService.exportUsersCsv(currentUser.getClientId());
        String filename = exportService.generateFilename("users", "csv");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(content);
    }

    /**
     * Export tickets to CSV
     * GET /app/export/tickets.csv
     */
    @GetMapping(value = "/tickets.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportTickets(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : LocalDate.now().minusMonths(1).atStartOfDay();
        LocalDateTime end = endDate != null ? endDate.atTime(LocalTime.MAX) : LocalDate.now().atTime(LocalTime.MAX);

        log.info("User {} exporting tickets CSV from {} to {}", currentUser.getId(), start, end);

        byte[] content = exportService.exportTicketsCsv(currentUser.getClientId(), start, end);
        String filename = exportService.generateFilename("tickets", "csv");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(content);
    }

    /**
     * Export KPIs to CSV
     * GET /app/export/kpis.csv
     */
    @GetMapping(value = "/kpis.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportKpis(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : LocalDate.now().minusMonths(1).atStartOfDay();
        LocalDateTime end = endDate != null ? endDate.atTime(LocalTime.MAX) : LocalDate.now().atTime(LocalTime.MAX);

        log.info("User {} exporting KPIs CSV from {} to {}", currentUser.getId(), start, end);

        byte[] content = exportService.exportKpisCsv(currentUser.getClientId(), start, end);
        String filename = exportService.generateFilename("kpis", "csv");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(content);
    }

    /**
     * Export messages to CSV
     * GET /app/export/messages.csv
     */
    @GetMapping(value = "/messages.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportMessages(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : LocalDate.now().minusDays(7).atStartOfDay();
        LocalDateTime end = endDate != null ? endDate.atTime(LocalTime.MAX) : LocalDate.now().atTime(LocalTime.MAX);

        log.info("User {} exporting messages CSV from {} to {}", currentUser.getId(), start, end);

        byte[] content = exportService.exportMessagesCsv(currentUser.getClientId(), start, end);
        String filename = exportService.generateFilename("messages", "csv");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(content);
    }

    /**
     * Export audits to CSV
     * GET /app/export/audits.csv
     */
    @GetMapping(value = "/audits.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportAudits(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : LocalDate.now().minusMonths(1).atStartOfDay();
        LocalDateTime end = endDate != null ? endDate.atTime(LocalTime.MAX) : LocalDate.now().atTime(LocalTime.MAX);

        log.info("User {} exporting audits CSV from {} to {}", currentUser.getId(), start, end);

        byte[] content = exportService.exportAuditsCsv(currentUser.getClientId(), start, end);
        String filename = exportService.generateFilename("audits", "csv");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(content);
    }

    /**
     * Export dashboard summary to CSV
     * GET /app/export/dashboard.csv
     */
    @GetMapping(value = "/dashboard.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportDashboard(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : LocalDate.now().minusMonths(1).atStartOfDay();
        LocalDateTime end = endDate != null ? endDate.atTime(LocalTime.MAX) : LocalDate.now().atTime(LocalTime.MAX);

        log.info("User {} exporting dashboard CSV from {} to {}", currentUser.getId(), start, end);

        byte[] content = exportService.exportDashboardCsv(currentUser.getClientId(), start, end);
        String filename = exportService.generateFilename("dashboard_summary", "csv");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(content);
    }

    /**
     * Export agent performance report to CSV
     * GET /app/export/agent_performance.csv
     */
    @GetMapping(value = "/agent_performance.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportAgentPerformance(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : LocalDate.now().minusMonths(1).atStartOfDay();
        LocalDateTime end = endDate != null ? endDate.atTime(LocalTime.MAX) : LocalDate.now().atTime(LocalTime.MAX);

        log.info("User {} exporting agent performance CSV from {} to {}", currentUser.getId(), start, end);

        byte[] content = exportService.exportAgentPerformanceCsv(currentUser.getClientId(), start, end);
        String filename = exportService.generateFilename("agent_performance", "csv");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(content);
    }

    /**
     * Export all data as ZIP
     * GET /app/export/all.zip
     */
    @GetMapping(value = "/all.zip", produces = "application/zip")
    public ResponseEntity<byte[]> exportAllAsZip(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) throws IOException {

        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : LocalDate.now().minusMonths(1).atStartOfDay();
        LocalDateTime end = endDate != null ? endDate.atTime(LocalTime.MAX) : LocalDate.now().atTime(LocalTime.MAX);

        log.info("User {} exporting all data as ZIP from {} to {}", currentUser.getId(), start, end);

        byte[] content = exportService.exportAllAsZip(currentUser.getClientId(), start, end);
        String filename = exportService.generateFilename("export_complete", "zip");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(content);
    }
}
