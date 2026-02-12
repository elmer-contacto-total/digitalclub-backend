package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.client.entity.ClientSetting;
import com.digitalgroup.holape.domain.client.repository.ClientSettingRepository;
import com.digitalgroup.holape.domain.common.enums.TicketStatus;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import com.digitalgroup.holape.domain.ticket.service.TicketService;
import com.digitalgroup.holape.security.CustomUserDetails;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.digitalgroup.holape.web.dto.PagedResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Ticket Admin Controller
 * Equivalent to Rails Admin::TicketsController
 */
@Slf4j
@RestController
@RequestMapping("/app/tickets")
@RequiredArgsConstructor
public class TicketAdminController {

    private final TicketService ticketService;
    private final ClientSettingRepository clientSettingRepository;

    /**
     * List tickets with standard REST pagination and advanced filters
     */
    @GetMapping
    public ResponseEntity<PagedResponse<Map<String, Object>>> index(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false, defaultValue = "open") String status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "10") int pageSize,
            @RequestParam(name = "agent_id", required = false) Long agentId,
            @RequestParam(name = "client_id", required = false) Long clientId,
            @RequestParam(required = false) String search,
            @RequestParam(name = "date_from", required = false) String dateFrom,
            @RequestParam(name = "date_to", required = false) String dateTo) {

        TicketStatus ticketStatus = "closed".equalsIgnoreCase(status)
                ? TicketStatus.CLOSED
                : TicketStatus.OPEN;

        Pageable pageable = PageRequest.of(page - 1, pageSize, Sort.by("createdAt").descending());

        boolean hasAdvancedFilters = agentId != null || search != null || dateFrom != null || dateTo != null;

        Page<Ticket> ticketsPage;
        if (currentUser.getRole().equals("SUPER_ADMIN") ||
            currentUser.getRole().equals("ADMIN") ||
            currentUser.getRole().equals("STAFF")) {
            Long effectiveClientId = clientId != null ? clientId : currentUser.getClientId();
            if (hasAdvancedFilters) {
                LocalDateTime startDate = dateFrom != null ? LocalDate.parse(dateFrom).atStartOfDay() : null;
                LocalDateTime endDate = dateTo != null ? LocalDate.parse(dateTo).atTime(23, 59, 59) : null;
                ticketsPage = ticketService.findTicketsFiltered(effectiveClientId, ticketStatus, agentId, search, startDate, endDate, pageable);
            } else {
                ticketsPage = ticketService.findByClient(effectiveClientId, ticketStatus, pageable);
            }
        } else {
            ticketsPage = ticketService.findByAgent(currentUser.getId(), ticketStatus, pageable);
        }

        List<Map<String, Object>> data = ticketsPage.getContent().stream()
                .map(this::mapTicketToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(PagedResponse.of(data, ticketsPage.getTotalElements(), page, pageSize));
    }

    /**
     * Get ticket close types for the current client
     * Equivalent to Rails: close_types from client_settings
     */
    @GetMapping("/close_types")
    public ResponseEntity<Map<String, Object>> getCloseTypes(
            @AuthenticationPrincipal CustomUserDetails currentUser) {
        List<Map<String, String>> closeTypes = fetchCloseTypes(currentUser.getClientId());
        return ResponseEntity.ok(Map.of("close_types", closeTypes));
    }

    /**
     * Get ticket by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> show(@PathVariable Long id) {
        Ticket ticket = ticketService.findById(id);
        Map<String, Object> response = mapTicketToResponse(ticket);

        // Include messages
        List<Message> messages = ticketService.getTicketMessages(id);
        response.put("messages", messages.stream()
                .map(this::mapMessageToResponse)
                .collect(Collectors.toList()));

        return ResponseEntity.ok(response);
    }

    /**
     * Close ticket
     * Only agents, managers, and admins can close tickets
     */
    @PostMapping("/close")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4', 'AGENT')")
    public ResponseEntity<Map<String, Object>> closeTicket(
            @RequestBody CloseTicketRequest request) {

        Ticket ticket = ticketService.closeTicket(request.ticketId(), request.closeType(), request.notes());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "ticket", mapTicketToResponse(ticket)
        ));
    }

    /**
     * Close ticket by path ID
     * Only agents, managers, and admins can close tickets
     */
    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4', 'AGENT')")
    public ResponseEntity<Map<String, Object>> closeTicketById(
            @PathVariable Long id,
            @RequestBody CloseTicketByIdRequest request) {

        String closeType = request.closeType() != null ? request.closeType() : "manual";
        Ticket ticket = ticketService.closeTicket(id, closeType, request.notes());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "ticket", mapTicketToResponse(ticket)
        ));
    }

    /**
     * Reassign ticket to another agent
     * Only managers and admins can reassign tickets
     */
    @PostMapping("/{id}/reassign")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4')")
    public ResponseEntity<Map<String, Object>> reassignTicket(
            @PathVariable Long id,
            @RequestBody ReassignTicketRequest request) {

        Ticket ticket = ticketService.reassignTicket(id, request.newAgentId());

        log.info("Ticket {} reassigned to agent {}", id, request.newAgentId());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "ticket", mapTicketToResponse(ticket)
        ));
    }

    public record ReassignTicketRequest(
            @JsonProperty("new_agent_id") Long newAgentId
    ) {}

    private Map<String, Object> mapTicketToResponse(Ticket ticket) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", ticket.getId());
        map.put("status", ticket.getStatus().name().toLowerCase());
        map.put("subject", ticket.getSubject());
        map.put("notes", ticket.getNotes());
        map.put("close_type", ticket.getCloseType());
        map.put("created_at", ticket.getCreatedAt());
        map.put("updated_at", ticket.getUpdatedAt());
        map.put("closed_at", ticket.getClosedAt());

        if (ticket.getUser() != null) {
            map.put("user_id", ticket.getUser().getId());
            map.put("user_name", ticket.getUser().getFullName());
            map.put("user_phone", ticket.getUser().getPhone());
        }
        if (ticket.getAgent() != null) {
            map.put("agent_id", ticket.getAgent().getId());
            map.put("agent_name", ticket.getAgent().getFullName());
        }

        return map;
    }

    private Map<String, Object> mapMessageToResponse(Message message) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", message.getId());
        map.put("content", message.getContent());
        map.put("direction", message.getDirection().name().toLowerCase());
        map.put("sent_at", message.getSentAt());
        map.put("sender_name", message.getHistoricSenderName());
        return map;
    }

    /**
     * Fetch ticket close types from client_settings
     * PARIDAD RAILS: client_settings.find_by(name: 'ticket_close_types').hash_value
     */
    private List<Map<String, String>> fetchCloseTypes(Long clientId) {
        List<Map<String, Object>> closeTypes = new ArrayList<>();
        Optional<ClientSetting> closeTypesSetting = clientSettingRepository.findByClientIdAndName(
                clientId, "ticket_close_types");
        if (closeTypesSetting.isPresent() && closeTypesSetting.get().getHashValue() != null) {
            Object hashValue = closeTypesSetting.get().getHashValue();
            if (hashValue instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> typesList = (List<Map<String, Object>>) list;
                closeTypes = typesList;
            } else if (hashValue instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typesMap = (Map<String, Object>) map;
                if (typesMap.containsKey("types") && typesMap.get("types") instanceof List<?>) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> typesList = (List<Map<String, Object>>) typesMap.get("types");
                    closeTypes = typesList;
                }
            }
        }

        return closeTypes.stream()
                .map(ct -> {
                    Map<String, String> m = new HashMap<>();
                    m.put("name", String.valueOf(ct.get("name")));
                    m.put("kpiName", String.valueOf(ct.get("kpi_name")));
                    return m;
                })
                .collect(Collectors.toList());
    }

    public record CloseTicketRequest(
            @JsonProperty("ticket_id") Long ticketId,
            @JsonProperty("close_type") String closeType,
            @JsonProperty("notes") String notes
    ) {}

    public record CloseTicketByIdRequest(
            @JsonProperty("close_type") String closeType,
            @JsonProperty("notes") String notes
    ) {}

    /**
     * Export ticket transcripts to ZIP file
     * Equivalent to Rails: Admin::TicketsController#export_ticket_transcripts
     * Creates a ZIP file containing text transcripts of each ticket
     * Supports both explicit ticketIds and filter-based export
     */
    @PostMapping("/export_transcripts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4')")
    public ResponseEntity<byte[]> exportTicketTranscripts(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody ExportTranscriptsRequest request) {

        // Resolve ticket IDs: use explicit list or query by filters
        List<Long> ticketIds = request.ticketIds();
        if (ticketIds == null || ticketIds.isEmpty()) {
            Long effectiveClientId = request.clientId() != null ? request.clientId() : currentUser.getClientId();
            LocalDateTime startDate = request.dateFrom() != null ? LocalDate.parse(request.dateFrom()).atStartOfDay() : null;
            LocalDateTime endDate = request.dateTo() != null ? LocalDate.parse(request.dateTo()).atTime(23, 59, 59) : null;
            ticketIds = ticketService.findTicketsForExport(effectiveClientId, request.status(), request.agentId(), startDate, endDate);
        }

        if (ticketIds.isEmpty()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("{\"error\": \"No tickets found for the given filters\"}".getBytes(StandardCharsets.UTF_8));
        }

        List<TicketService.TicketTranscript> transcripts = ticketService.exportTicketTranscripts(ticketIds);

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ZipOutputStream zos = new ZipOutputStream(baos);

            for (TicketService.TicketTranscript transcript : transcripts) {
                ZipEntry entry = new ZipEntry(transcript.filename());
                zos.putNextEntry(entry);
                zos.write(transcript.content().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            zos.close();
            byte[] zipBytes = baos.toByteArray();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/zip"));
            headers.setContentDispositionFormData("attachment",
                    "ticket_transcripts_" + LocalDate.now() + ".zip");

            log.info("Exported {} ticket transcripts to ZIP", transcripts.size());

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(zipBytes);

        } catch (IOException e) {
            log.error("Failed to create ZIP file: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Alternative GET endpoint for export with query params
     */
    @GetMapping("/export_transcripts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4')")
    public ResponseEntity<byte[]> exportTicketTranscriptsGet(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam List<Long> ticketIds) {
        return exportTicketTranscripts(currentUser, new ExportTranscriptsRequest(ticketIds, null, null, null, null, null));
    }

    public record ExportTranscriptsRequest(
            @JsonProperty("ticket_ids") List<Long> ticketIds,
            @JsonProperty("status") String status,
            @JsonProperty("agent_id") Long agentId,
            @JsonProperty("client_id") Long clientId,
            @JsonProperty("date_from") String dateFrom,
            @JsonProperty("date_to") String dateTo
    ) {}
}
