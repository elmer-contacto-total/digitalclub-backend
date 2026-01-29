package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.common.enums.KpiType;
import com.digitalgroup.holape.domain.kpi.entity.Kpi;
import com.digitalgroup.holape.domain.kpi.repository.KpiRepository;
import com.digitalgroup.holape.domain.kpi.service.KpiService;
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
 * KPI Admin Controller
 * Equivalent to Rails Admin::KpisController
 * Views and exports KPI data
 */
@Slf4j
@RestController
@RequestMapping("/app/kpis")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2')")
public class KpiAdminController {

    private final KpiRepository kpiRepository;
    private final KpiService kpiService;

    /**
     * List KPIs with filtering
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> index(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String kpiType,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        LocalDateTime start = startDate != null ?
                startDate.atStartOfDay() :
                LocalDateTime.now().minusDays(30);
        LocalDateTime end = endDate != null ?
                endDate.atTime(LocalTime.MAX) :
                LocalDateTime.now();

        Page<Kpi> kpisPage;
        KpiType kpiTypeEnum = kpiType != null ? KpiType.valueOf(kpiType.toUpperCase()) : null;

        if (userId != null && kpiTypeEnum != null) {
            kpisPage = kpiRepository.findByUserIdAndKpiTypeAndCreatedAtBetween(
                    userId, kpiTypeEnum, start, end, pageable);
        } else if (userId != null) {
            kpisPage = kpiRepository.findByUserIdAndCreatedAtBetween(userId, start, end, pageable);
        } else if (kpiTypeEnum != null) {
            kpisPage = kpiRepository.findByClientIdAndKpiTypeAndCreatedAtBetween(
                    currentUser.getClientId(), kpiTypeEnum, start, end, pageable);
        } else {
            kpisPage = kpiRepository.findByClientIdAndCreatedAtBetween(
                    currentUser.getClientId(), start, end, pageable);
        }

        List<Map<String, Object>> data = kpisPage.getContent().stream()
                .map(this::mapKpiToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "kpis", data,
                "total", kpisPage.getTotalElements(),
                "page", page,
                "totalPages", kpisPage.getTotalPages()
        ));
    }

    /**
     * Get KPI summary by type
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        LocalDateTime start = startDate != null ?
                startDate.atStartOfDay() :
                LocalDateTime.now().minusDays(30);
        LocalDateTime end = endDate != null ?
                endDate.atTime(LocalTime.MAX) :
                LocalDateTime.now();

        Map<String, Object> summary = kpiService.calculateSummary(
                currentUser.getClientId(), start, end);

        return ResponseEntity.ok(summary);
    }

    /**
     * Get KPI types
     */
    @GetMapping("/types")
    public ResponseEntity<Map<String, Object>> getTypes() {
        List<String> types = List.of(
                "new_client",
                "new_ticket",
                "first_response_time",
                "responded_to_client",
                "closed_ticket",
                "sent_message",
                "require_response",
                "tmo",
                "message_received",
                "message_sent",
                "template_sent",
                "whatsapp_template_sent",
                "sms_sent"
        );

        return ResponseEntity.ok(Map.of("types", types));
    }

    /**
     * Get KPIs for a specific user
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<Map<String, Object>> userKpis(
            @PathVariable Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        LocalDateTime start = startDate != null ?
                startDate.atStartOfDay() :
                LocalDateTime.now().minusDays(30);
        LocalDateTime end = endDate != null ?
                endDate.atTime(LocalTime.MAX) :
                LocalDateTime.now();

        Map<String, Long> kpiCounts = kpiService.getUserKpiCounts(userId, start, end);

        return ResponseEntity.ok(Map.of(
                "user_id", userId,
                "start_date", start,
                "end_date", end,
                "kpis", kpiCounts
        ));
    }

    /**
     * Export KPIs to CSV
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportKpis(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String kpiType) {

        LocalDateTime start = startDate != null ?
                startDate.atStartOfDay() :
                LocalDateTime.now().minusDays(30);
        LocalDateTime end = endDate != null ?
                endDate.atTime(LocalTime.MAX) :
                LocalDateTime.now();

        Pageable pageable = PageRequest.of(0, 10000, Sort.by("createdAt").descending());

        Page<Kpi> kpisPage;
        KpiType kpiTypeEnum = kpiType != null ? KpiType.valueOf(kpiType.toUpperCase()) : null;
        if (kpiTypeEnum != null) {
            kpisPage = kpiRepository.findByClientIdAndKpiTypeAndCreatedAtBetween(
                    currentUser.getClientId(), kpiTypeEnum, start, end, pageable);
        } else {
            kpisPage = kpiRepository.findByClientIdAndCreatedAtBetween(
                    currentUser.getClientId(), start, end, pageable);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            baos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // BOM

            PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));

            // Header
            writer.println("ID,Fecha,Tipo KPI,Usuario,Ticket ID,Valor,Valor Tiempo");

            // Data - PARIDAD RAILS: kpi_value y kpi_time_value se almacenan en dataHash
            for (Kpi kpi : kpisPage.getContent()) {
                Object kpiValue = kpi.getDataHash() != null ? kpi.getDataHash().get("kpi_value") : null;
                Object kpiTimeValue = kpi.getDataHash() != null ? kpi.getDataHash().get("kpi_time_value") : null;
                writer.printf("%d,%s,%s,%s,%s,%s,%s%n",
                        kpi.getId(),
                        kpi.getCreatedAt(),
                        kpi.getKpiType(),
                        kpi.getUser() != null ? kpi.getUser().getFullName() : "",
                        kpi.getTicket() != null ? kpi.getTicket().getId() : "",
                        kpiValue != null ? kpiValue : "",
                        kpiTimeValue != null ? kpiTimeValue : ""
                );
            }

            writer.flush();
            writer.close();
        } catch (Exception e) {
            log.error("Error generating CSV", e);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.setContentDispositionFormData("attachment",
                String.format("kpis_%s_%s.csv",
                        start.toLocalDate(),
                        end.toLocalDate()));

        return ResponseEntity.ok()
                .headers(headers)
                .body(baos.toByteArray());
    }

    /**
     * Get agent performance ranking
     */
    @GetMapping("/agent_ranking")
    public ResponseEntity<Map<String, Object>> agentRanking(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false, defaultValue = "10") int limit) {

        LocalDateTime start = startDate != null ?
                startDate.atStartOfDay() :
                LocalDateTime.now().minusDays(30);
        LocalDateTime end = endDate != null ?
                endDate.atTime(LocalTime.MAX) :
                LocalDateTime.now();

        List<Map<String, Object>> ranking = kpiService.getAgentRanking(
                currentUser.getClientId(), start, end, limit);

        return ResponseEntity.ok(Map.of(
                "ranking", ranking,
                "start_date", start,
                "end_date", end
        ));
    }

    private Map<String, Object> mapKpiToResponse(Kpi kpi) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", kpi.getId());
        map.put("kpi_type", kpi.getKpiType());
        // PARIDAD RAILS: kpi_value y kpi_time_value se almacenan en dataHash
        map.put("kpi_value", kpi.getDataHash() != null ? kpi.getDataHash().get("kpi_value") : null);
        map.put("kpi_time_value", kpi.getDataHash() != null ? kpi.getDataHash().get("kpi_time_value") : null);
        map.put("created_at", kpi.getCreatedAt());

        if (kpi.getUser() != null) {
            map.put("user_id", kpi.getUser().getId());
            map.put("user_name", kpi.getUser().getFullName());
        }

        if (kpi.getTicket() != null) {
            map.put("ticket_id", kpi.getTicket().getId());
        }

        return map;
    }
}
