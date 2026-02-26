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
import java.util.stream.Collectors;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        // Capture request context before @Async call (unavailable in async thread)
        String remoteAddress = getRemoteAddress();
        String requestUuid = getRequestUuid();
        User agent = ticket.getAgent();
        auditService.logTicketClose(ticket.getId(), ticket.getUser().getId(),
                agent != null ? agent.getId() : null,
                agent != null ? agent.getNameOrEmail() : null,
                closeType, notes, remoteAddress, requestUuid);

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
        String autoRemoteAddress = getRemoteAddress();
        String autoRequestUuid = getRequestUuid();
        User autoAgent = ticket.getAgent();
        auditService.logTicketClose(ticket.getId(), ticket.getUser().getId(),
                autoAgent != null ? autoAgent.getId() : null,
                autoAgent != null ? autoAgent.getNameOrEmail() : null,
                "auto_closed", null, autoRemoteAddress, autoRequestUuid);

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
     * Export ticket transcripts grouped by agent (Rails parity).
     * Returns a list of files: 1 CSV summary + 1 TXT per agent.
     * Equivalent to Rails: Admin::TicketsController#export_ticket_transcripts
     */
    @Transactional(readOnly = true)
    public List<ExportFile> exportTicketTranscripts(List<Long> ticketIds, LocalDate dateFrom, LocalDate dateTo) {
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy HH:mm:ss", Locale.ENGLISH);

        // Load all tickets with user+agent eagerly
        List<Ticket> tickets = new ArrayList<>(ticketRepository.findAllByIdWithUserAndAgent(ticketIds));

        // Sort by agent_id, then created_at (Rails: .order(:agent_id, :created_at))
        tickets.sort(Comparator
                .comparing((Ticket t) -> t.getAgent() != null ? t.getAgent().getId() : Long.MAX_VALUE)
                .thenComparing(Ticket::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));

        // Load all messages with sender eagerly, sorted by sentAt
        Map<Long, List<Message>> messagesByTicket = messageRepository
                .findByTicketIdInWithSender(ticketIds)
                .stream()
                .collect(Collectors.groupingBy(m -> m.getTicket().getId()));
        messagesByTicket.values().forEach(msgs ->
                msgs.sort(Comparator.comparing(Message::getSentAt, Comparator.nullsLast(Comparator.naturalOrder()))));

        List<ExportFile> files = new ArrayList<>();

        // 1. CSV summary
        String csvFilename = String.format("tickets_%s_to_%s.csv",
                dateFrom != null ? dateFrom : LocalDate.now(),
                dateTo != null ? dateTo : LocalDate.now());
        files.add(new ExportFile(csvFilename, generateCsv(tickets, messagesByTicket, dateFormatter)));

        // 2. One TXT per agent
        Map<User, List<Ticket>> ticketsByAgent = new LinkedHashMap<>();
        for (Ticket ticket : tickets) {
            ticketsByAgent.computeIfAbsent(ticket.getAgent(), k -> new ArrayList<>()).add(ticket);
        }
        for (Map.Entry<User, List<Ticket>> entry : ticketsByAgent.entrySet()) {
            User agent = entry.getKey();
            String agentName = agent != null ? agent.getFullName() : "Unknown Agent";
            files.add(new ExportFile(agentName + ".txt",
                    generateAgentFile(agentName, entry.getValue(), messagesByTicket, dateFormatter)));
        }

        return files;
    }

    private String generateCsv(List<Ticket> tickets, Map<Long, List<Message>> messagesByTicket,
                                DateTimeFormatter dateFormatter) {
        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF'); // UTF-8 BOM
        csv.append("ID,Agent Name,Client Name,Transcript,Created Time,Closed,Closed Time,Notes\n");

        for (Ticket ticket : tickets) {
            csv.append(ticket.getId()).append(',');
            csv.append(csvEscape(ticket.getAgent() != null ? ticket.getAgent().getFullName() : "")).append(',');
            csv.append(csvEscape(ticket.getUser() != null ? ticket.getUser().getFullName() : "")).append(',');
            csv.append(csvEscape(buildTranscript(ticket, messagesByTicket, dateFormatter))).append(',');
            csv.append(csvEscape(ticket.getCreatedAt() != null ? ticket.getCreatedAt().format(dateFormatter) : "")).append(',');
            csv.append(ticket.getClosedAt() != null ? "Yes" : "No").append(',');
            csv.append(csvEscape(ticket.getClosedAt() != null ? ticket.getClosedAt().format(dateFormatter) : "")).append(',');
            csv.append(csvEscape(ticket.getNotes() != null ? ticket.getNotes() : ""));
            csv.append('\n');
        }

        return csv.toString();
    }

    private String buildTranscript(Ticket ticket, Map<Long, List<Message>> messagesByTicket,
                                    DateTimeFormatter dateFormatter) {
        List<Message> messages = messagesByTicket.getOrDefault(ticket.getId(), List.of());
        StringBuilder content = new StringBuilder();
        for (Message message : messages) {
            if (message.getSentAt() != null) {
                content.append(message.getSentAt().format(dateFormatter));
            }
            content.append(" - ");
            String senderName = message.getSenderDisplayName();
            content.append(senderName != null ? senderName : "");
            content.append(": ");
            content.append(message.getContent() != null ? message.getContent() : "");
            content.append('\n');
        }
        return content.toString().strip();
    }

    private String generateAgentFile(String agentName, List<Ticket> tickets,
                                      Map<Long, List<Message>> messagesByTicket,
                                      DateTimeFormatter dateFormatter) {
        StringBuilder content = new StringBuilder();
        content.append("Agent Name: ").append(agentName).append('\n');
        content.append("Number of Tickets: ").append(tickets.size()).append("\n\n");

        for (Ticket ticket : tickets) {
            content.append("Ticket ID: ").append(ticket.getId()).append('\n');
            content.append("Subject: ").append(ticket.getSubject() != null ? ticket.getSubject() : "").append('\n');
            content.append("Created At: ");
            if (ticket.getCreatedAt() != null) {
                content.append(ticket.getCreatedAt().format(dateFormatter));
            }
            content.append("\n\n");

            List<Message> messages = messagesByTicket.getOrDefault(ticket.getId(), List.of());
            for (Message message : messages) {
                if (message.getSentAt() != null) {
                    content.append(message.getSentAt().format(dateFormatter));
                }
                content.append(" - ");
                String senderName = message.getSenderDisplayName();
                content.append(senderName != null ? senderName : "");
                content.append(": ");
                content.append(message.getContent() != null ? message.getContent() : "");
                content.append('\n');
            }
            content.append('\n');
        }

        return content.toString();
    }

    private static String csvEscape(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    public record ExportFile(String filename, String content) {}

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
        return ticketRepository.findTicketIdsForExport(clientId, ticketStatus, agentId, startDate, endDate);
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

    /**
     * Get remote IP address from current request context.
     * Must be called BEFORE @Async methods (request context is unavailable in async threads).
     */
    private String getRemoteAddress() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest().getRemoteAddr() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get request UUID from current request context.
     * Must be called BEFORE @Async methods (request context is unavailable in async threads).
     */
    private String getRequestUuid() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                String requestId = attrs.getRequest().getHeader("X-Request-ID");
                return requestId != null ? requestId : UUID.randomUUID().toString();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
