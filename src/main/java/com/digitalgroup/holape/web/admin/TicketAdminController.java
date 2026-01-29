package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.common.enums.TicketStatus;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import com.digitalgroup.holape.domain.ticket.service.TicketService;
import com.digitalgroup.holape.security.CustomUserDetails;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * List tickets with standard REST pagination
     */
    @GetMapping
    public ResponseEntity<PagedResponse<Map<String, Object>>> index(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false, defaultValue = "open") String status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "10") int pageSize) {

        TicketStatus ticketStatus = "closed".equalsIgnoreCase(status)
                ? TicketStatus.CLOSED
                : TicketStatus.OPEN;

        Pageable pageable = PageRequest.of(page - 1, pageSize, Sort.by("createdAt").descending());

        Page<Ticket> ticketsPage;
        if (currentUser.getRole().equals("SUPER_ADMIN") ||
            currentUser.getRole().equals("ADMIN") ||
            currentUser.getRole().equals("STAFF")) {
            ticketsPage = ticketService.findByClient(currentUser.getClientId(), ticketStatus, pageable);
        } else {
            ticketsPage = ticketService.findByAgent(currentUser.getId(), ticketStatus, pageable);
        }

        List<Map<String, Object>> data = ticketsPage.getContent().stream()
                .map(this::mapTicketToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(PagedResponse.of(data, ticketsPage.getTotalElements(), page, pageSize));
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

        Ticket ticket = ticketService.closeTicket(request.ticketId(), request.closeType());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "ticket", mapTicketToResponse(ticket)
        ));
    }

    /**
     * Legacy close ticket endpoint
     * Only agents, managers, and admins can close tickets
     */
    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4', 'AGENT')")
    public ResponseEntity<Map<String, Object>> closeTicketById(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "manual") String closeType) {

        Ticket ticket = ticketService.closeTicket(id, closeType);

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

    public record ReassignTicketRequest(Long newAgentId) {}

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

    public record CloseTicketRequest(Long ticketId, String closeType) {}

    /**
     * Export ticket transcripts to ZIP file
     * Equivalent to Rails: Admin::TicketsController#export_ticket_transcripts
     * Creates a ZIP file containing text transcripts of each ticket
     */
    @PostMapping("/export_transcripts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4')")
    public ResponseEntity<byte[]> exportTicketTranscripts(@RequestBody ExportTranscriptsRequest request) {
        List<TicketService.TicketTranscript> transcripts = ticketService.exportTicketTranscripts(request.ticketIds());

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
    public ResponseEntity<byte[]> exportTicketTranscriptsGet(@RequestParam List<Long> ticketIds) {
        return exportTicketTranscripts(new ExportTranscriptsRequest(ticketIds));
    }

    public record ExportTranscriptsRequest(List<Long> ticketIds) {}
}
