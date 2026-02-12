package com.digitalgroup.holape.domain.ticket.service;

import com.digitalgroup.holape.domain.common.enums.KpiType;
import com.digitalgroup.holape.domain.common.enums.MessageDirection;
import com.digitalgroup.holape.domain.common.enums.TicketStatus;
import com.digitalgroup.holape.domain.kpi.entity.Kpi;
import com.digitalgroup.holape.domain.kpi.repository.KpiRepository;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import com.digitalgroup.holape.domain.ticket.repository.TicketRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.audit.service.AuditService;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import com.digitalgroup.holape.util.WorkingHoursUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ticket Service
 * Contains business logic equivalent to Rails Ticket model
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {

    private final TicketRepository ticketRepository;
    private final MessageRepository messageRepository;
    private final KpiRepository kpiRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    private static final int MAX_TMO_MINUTES = 2880; // 48 hours

    @Transactional(readOnly = true)
    public Ticket findById(Long id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "id", id));
    }

    @Transactional(readOnly = true)
    public Page<Ticket> findByAgent(Long agentId, TicketStatus status, Pageable pageable) {
        return ticketRepository.findByAgentIdAndStatus(agentId, status, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Ticket> findByClient(Long clientId, TicketStatus status, Pageable pageable) {
        return ticketRepository.findByClientAndStatus(clientId, status, pageable);
    }

    @Transactional(readOnly = true)
    public List<Message> getTicketMessages(Long ticketId) {
        return messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
    }

    /**
     * Closes a ticket with the specified close type
     * Equivalent to Rails Ticket#close_ticket
     */
    @Transactional
    public Ticket closeTicket(Long ticketId, String closeType) {
        return closeTicket(ticketId, closeType, null);
    }

    /**
     * Closes a ticket with the specified close type and optional notes
     * Equivalent to Rails Ticket#close_ticket
     */
    @Transactional
    public Ticket closeTicket(Long ticketId, String closeType, String notes) {
        Ticket ticket = findById(ticketId);

        if (ticket.isClosed()) {
            log.warn("Ticket {} is already closed", ticketId);
            return ticket;
        }

        // Append notes if provided
        if (notes != null && !notes.isBlank()) {
            String existing = ticket.getNotes() != null ? ticket.getNotes() : "";
            ticket.setNotes(existing.isEmpty() ? notes : existing + "\n\n" + notes);
        }

        ticket.close(closeType);
        ticket = ticketRepository.save(ticket);

        // Create closed ticket KPI
        createKpi(ticket, KpiType.CLOSED_TICKET);

        // Create TMO KPI (time ticket was open)
        long tmoMinutes = calculateTmoMinutes(ticket);
        createKpiWithValue(ticket, KpiType.TMO, (int) tmoMinutes);

        // Create specific close type KPI (dynamic - supports any configured close type)
        if (closeType != null && !closeType.isBlank()) {
            String kpiName = closeType.startsWith("closed_") ? closeType : "closed_" + closeType;
            try {
                KpiType kpiType = KpiType.valueOf(kpiName.toUpperCase());
                createKpi(ticket, kpiType);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown KpiType for closeType '{}', skipping specific KPI", closeType);
            }
        }

        // PARIDAD RAILS: close ALL open tickets for the same user
        // Rails: Ticket.where(user_id: ticket.user_id, status: 'open').update_all(...)
        ticketRepository.closeAllOpenByUserId(ticket.getUser().getId(), closeType);

        // Update user's require_close_ticket flag
        User user = ticket.getUser();
        user.setRequireCloseTicket(false);
        userRepository.save(user);

        // Create audit record for ticket close (pass primitives to avoid detached proxy in @Async)
        User agent = ticket.getAgent();
        auditService.logTicketClose(ticket.getId(), ticket.getUser().getId(),
                agent != null ? agent.getId() : null,
                agent != null ? agent.getNameOrEmail() : null,
                closeType);

        log.info("Closed ticket {} with type {}", ticketId, closeType);

        return ticket;
    }

    /**
     * Close a ticket via auto-close (inactivity).
     * PARIDAD RAILS: close_expired_tickets in ticket.rb
     * Creates: closed_ticket + tmo + auto_closed_ticket KPIs
     * Does NOT call closeAllOpenByUserId (the job evaluates each ticket individually)
     */
    @Transactional
    public Ticket closeTicketAutoClose(Long ticketId) {
        Ticket ticket = findById(ticketId);

        if (ticket.isClosed()) {
            log.warn("Ticket {} is already closed", ticketId);
            return ticket;
        }

        // PARIDAD RAILS: previous_notes = ticket.notes; ticket.update(notes: "#{previous_notes} \n\n ...")
        String previousNotes = ticket.getNotes() != null ? ticket.getNotes() : "";
        ticket.setNotes(previousNotes + " \n\n Ticket cerrado automáticamente por inactividad");

        // Close without close_type (Rails auto-close doesn't set close_type)
        ticket.close(null);
        ticket = ticketRepository.save(ticket);

        // PARIDAD RAILS: after_commit creates closed_ticket + tmo
        createKpi(ticket, KpiType.CLOSED_TICKET);

        long tmoMinutes = calculateTmoMinutes(ticket);
        createKpiWithValue(ticket, KpiType.TMO, (int) tmoMinutes);

        // PARIDAD RAILS: Kpi.create(...kpi_type: 'auto_closed_ticket'...)
        createKpi(ticket, KpiType.AUTO_CLOSED_TICKET);

        // Update user's require_close_ticket flag
        User user = ticket.getUser();
        user.setRequireCloseTicket(false);
        userRepository.save(user);

        // Create audit record for auto-close (pass primitives to avoid detached proxy in @Async)
        User autoAgent = ticket.getAgent();
        auditService.logTicketClose(ticket.getId(), ticket.getUser().getId(),
                autoAgent != null ? autoAgent.getId() : null,
                autoAgent != null ? autoAgent.getNameOrEmail() : null,
                "auto_closed");

        log.info("Auto-closed ticket {}", ticketId);
        return ticket;
    }

    /**
     * Calculates TMO (Time ticket was open) in minutes
     * Equivalent to Rails: tmo_time_in_minutes
     * Uses first incoming message as start time and WorkingHoursUtils for business hours only
     */
    private long calculateTmoMinutes(Ticket ticket) {
        if (ticket.getClosedAt() == null) {
            return 0;
        }

        // Get first incoming message in the ticket (equivalent to Rails: messages.incoming.order(:sent_at, :id).first)
        Message firstIncoming = messageRepository
                .findFirstByTicketIdAndDirectionOrderByCreatedAtAsc(ticket.getId(), MessageDirection.INCOMING)
                .orElse(null);

        if (firstIncoming == null || firstIncoming.getSentAt() == null) {
            // Fallback to ticket creation time if no incoming message
            return Math.min(
                    WorkingHoursUtils.calculateWorkingMinutes(ticket.getCreatedAt(), ticket.getClosedAt()),
                    MAX_TMO_MINUTES
            );
        }

        // Calculate working minutes from first incoming message to ticket closed_at
        long workingMinutes = WorkingHoursUtils.calculateWorkingMinutes(
                firstIncoming.getSentAt(),
                ticket.getClosedAt()
        );

        return Math.min(workingMinutes, MAX_TMO_MINUTES);
    }

    private void createKpi(Ticket ticket, KpiType kpiType) {
        createKpiWithValue(ticket, kpiType, 1);
    }

    private void createKpiWithValue(Ticket ticket, KpiType kpiType, int value) {
        Kpi kpi = Kpi.builder()
                .client(ticket.getAgent().getClient())
                .user(ticket.getAgent())
                .ticket(ticket)
                .kpiType(kpiType)
                .value(value)
                .build();
        kpiRepository.save(kpi);
    }

    @Transactional(readOnly = true)
    public long countOpenTicketsByAgent(Long agentId) {
        return ticketRepository.countByAgentAndStatus(agentId, TicketStatus.OPEN);
    }

    /**
     * Reassign ticket to another agent
     * Equivalent to Rails: Admin::TicketsController#update (manager_id)
     */
    @Transactional
    public Ticket reassignTicket(Long ticketId, Long newAgentId) {
        Ticket ticket = findById(ticketId);
        User newAgent = userRepository.findById(newAgentId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", newAgentId));

        User oldAgent = ticket.getAgent();
        ticket.setAgent(newAgent);
        ticket = ticketRepository.save(ticket);

        log.info("Ticket {} reassigned from agent {} to agent {}",
                ticketId,
                oldAgent != null ? oldAgent.getId() : "none",
                newAgentId);

        return ticket;
    }

    /**
     * Export ticket transcripts to a structured format
     * Returns all messages for given tickets in a format suitable for ZIP export
     * Equivalent to Rails: Admin::TicketsController#export_ticket_transcripts
     */
    @Transactional(readOnly = true)
    public List<TicketTranscript> exportTicketTranscripts(List<Long> ticketIds) {
        return ticketIds.stream()
                .map(this::buildTicketTranscript)
                .toList();
    }

    private TicketTranscript buildTicketTranscript(Long ticketId) {
        Ticket ticket = findById(ticketId);
        List<Message> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);

        StringBuilder content = new StringBuilder();
        content.append("=== TICKET #").append(ticketId).append(" ===\n");
        content.append("Usuario: ").append(ticket.getUser() != null ? ticket.getUser().getFullName() : "N/A").append("\n");
        content.append("Teléfono: ").append(ticket.getUser() != null ? ticket.getUser().getPhone() : "N/A").append("\n");
        content.append("Agente: ").append(ticket.getAgent() != null ? ticket.getAgent().getFullName() : "N/A").append("\n");
        content.append("Estado: ").append(ticket.getStatus()).append("\n");
        content.append("Creado: ").append(ticket.getCreatedAt()).append("\n");
        if (ticket.getClosedAt() != null) {
            content.append("Cerrado: ").append(ticket.getClosedAt()).append("\n");
        }
        content.append("\n--- MENSAJES ---\n\n");

        for (Message message : messages) {
            String senderName = message.getHistoricSenderName() != null
                    ? message.getHistoricSenderName()
                    : (message.getSender() != null ? message.getSender().getFullName() : "Sistema");
            String direction = message.getDirection() == MessageDirection.INCOMING ? "[CLIENTE]" : "[AGENTE]";
            content.append(message.getSentAt()).append(" ").append(direction).append(" ").append(senderName).append(":\n");
            content.append(message.getContent()).append("\n\n");
        }

        String filename = String.format("ticket_%d_%s.txt",
                ticketId,
                ticket.getUser() != null ? ticket.getUser().getPhone() : "unknown");

        return new TicketTranscript(ticketId, filename, content.toString());
    }

    public record TicketTranscript(Long ticketId, String filename, String content) {}

    /**
     * Find tickets with advanced filters (for index endpoint)
     */
    @Transactional(readOnly = true)
    public Page<Ticket> findTicketsFiltered(Long clientId, TicketStatus status, Long agentId,
            String search, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return ticketRepository.findTicketsFiltered(clientId, status, agentId, search, startDate, endDate, pageable);
    }

    /**
     * Find ticket IDs for export with filters (when no explicit ticketIds provided)
     */
    @Transactional(readOnly = true)
    public List<Long> findTicketsForExport(Long clientId, String status, Long agentId,
            LocalDateTime startDate, LocalDateTime endDate) {
        TicketStatus ticketStatus = (status != null && !"all".equals(status))
                ? ("closed".equalsIgnoreCase(status) ? TicketStatus.CLOSED : TicketStatus.OPEN)
                : null;
        return ticketRepository.findTicketsForExportFiltered(clientId, ticketStatus, agentId, startDate, endDate)
                .stream().map(Ticket::getId).toList();
    }

    /**
     * Calculates TMO time in formatted string DD:HH:MM
     * Equivalent to Rails: Ticket#tmo_time
     *
     * @param ticket The ticket to calculate TMO for
     * @return Formatted string like "02:05:30" (2 days, 5 hours, 30 minutes)
     */
    public String getTmoTimeFormatted(Ticket ticket) {
        if (ticket.getClosedAt() == null) {
            return "00:00:00";
        }

        long tmoMinutes = calculateTmoMinutes(ticket);

        long days = tmoMinutes / (24 * 60);
        long hours = (tmoMinutes % (24 * 60)) / 60;
        long minutes = tmoMinutes % 60;

        return String.format("%02d:%02d:%02d", days, hours, minutes);
    }

    /**
     * Get TMO in different units
     */
    public Map<String, Object> getTmoDetails(Ticket ticket) {
        long tmoMinutes = calculateTmoMinutes(ticket);

        Map<String, Object> details = new HashMap<>();
        details.put("total_minutes", tmoMinutes);
        details.put("total_hours", tmoMinutes / 60.0);
        details.put("formatted", getTmoTimeFormatted(ticket));

        long days = tmoMinutes / (24 * 60);
        long hours = (tmoMinutes % (24 * 60)) / 60;
        long minutes = tmoMinutes % 60;

        details.put("days", days);
        details.put("hours", hours);
        details.put("minutes", minutes);

        return details;
    }
}
