package com.digitalgroup.holape.domain.ticket.service;

import com.digitalgroup.holape.domain.client.entity.ClientSetting;
import com.digitalgroup.holape.domain.client.repository.ClientSettingRepository;
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
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import com.digitalgroup.holape.util.WorkingHoursUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
    private final ClientSettingRepository clientSettingRepository;

    private static final int DEFAULT_AUTO_CLOSE_HOURS = 24;
    private static final String SETTING_TIME_FOR_TICKET_AUTOCLOSE = "time_for_ticket_autoclose";
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
        Ticket ticket = findById(ticketId);

        if (ticket.isClosed()) {
            log.warn("Ticket {} is already closed", ticketId);
            return ticket;
        }

        ticket.close(closeType);
        ticket = ticketRepository.save(ticket);

        // Create closed ticket KPI
        createKpi(ticket, KpiType.CLOSED_TICKET);

        // Create TMO KPI (time ticket was open)
        long tmoMinutes = calculateTmoMinutes(ticket);
        createKpiWithValue(ticket, KpiType.TMO, (int) tmoMinutes);

        // Create specific close type KPI
        if ("con_acuerdo".equals(closeType)) {
            createKpi(ticket, KpiType.CLOSED_CON_ACUERDO);
        } else if ("sin_acuerdo".equals(closeType)) {
            createKpi(ticket, KpiType.CLOSED_SIN_ACUERDO);
        }

        // Update user's require_close_ticket flag
        User user = ticket.getUser();
        user.setRequireCloseTicket(false);
        userRepository.save(user);

        log.info("Closed ticket {} with type {}", ticketId, closeType);

        return ticket;
    }

    /**
     * Auto-closes expired tickets
     * Equivalent to Rails Ticket.close_expired_tickets
     * Runs every hour
     *
     * Logic matches Rails:
     * 1. Read time_for_ticket_autoclose from ClientSetting per client
     * 2. Check if last message was responded (if incoming, must have outgoing response)
     * 3. Only close if conditions are met
     */
    @Scheduled(cron = "0 0 * * * *") // Every hour
    @Transactional
    public void closeExpiredTickets() {
        log.info("Checking for expired tickets to auto-close");

        // Get all open tickets and process per client (to respect per-client settings)
        List<Ticket> openTickets = ticketRepository.findByStatus(TicketStatus.OPEN);
        int closedCount = 0;

        for (Ticket ticket : openTickets) {
            try {
                if (shouldAutoCloseTicket(ticket)) {
                    ticket.close("auto");
                    ticket.setNotes("Ticket cerrado automáticamente por inactividad");
                    ticketRepository.save(ticket);

                    // Create auto-closed ticket KPI
                    createKpi(ticket, KpiType.AUTO_CLOSED_TICKET);

                    // Create TMO KPI
                    long tmoMinutes = calculateTmoMinutes(ticket);
                    createKpiWithValue(ticket, KpiType.TMO, (int) tmoMinutes);

                    log.info("Auto-closed expired ticket {}", ticket.getId());
                    closedCount++;
                }
            } catch (Exception e) {
                log.error("Failed to auto-close ticket {}: {}", ticket.getId(), e.getMessage());
            }
        }

        if (closedCount > 0) {
            log.info("Auto-closed {} expired tickets", closedCount);
        }
    }

    /**
     * Determines if a ticket should be auto-closed
     * Equivalent to Rails logic in close_expired_tickets
     */
    private boolean shouldAutoCloseTicket(Ticket ticket) {
        Long clientId = ticket.getUser() != null && ticket.getUser().getClient() != null
                ? ticket.getUser().getClient().getId()
                : null;

        if (clientId == null) {
            return false;
        }

        // Get hours_to_close from ClientSetting (equivalent to Rails: ClientSetting.find_by(..., name: 'time_for_ticket_autoclose'))
        Integer hoursToClose = clientSettingRepository
                .findIntegerValueByClientAndName(clientId, SETTING_TIME_FOR_TICKET_AUTOCLOSE)
                .orElse(null);

        // If setting not configured or is 0, don't auto-close
        if (hoursToClose == null || hoursToClose <= 0) {
            return false;
        }

        // Get last message in ticket
        Message lastMessage = messageRepository.findLastByTicketId(ticket.getId()).orElse(null);

        if (lastMessage == null || lastMessage.getSentAt() == null) {
            return false;
        }

        // Check if last message is older than hours_to_close
        LocalDateTime cutoffTime = LocalDateTime.now().minusHours(hoursToClose);
        if (!lastMessage.getSentAt().isBefore(cutoffTime)) {
            return false; // Last message is too recent
        }

        // Rails: if last_message.direction == 'incoming', verify it was responded
        if (lastMessage.getDirection() == MessageDirection.INCOMING) {
            // Check if there's any outgoing message after the last incoming
            Message lastOutgoing = messageRepository
                    .findFirstByTicketIdAndDirectionOrderByCreatedAtAsc(ticket.getId(), MessageDirection.OUTGOING)
                    .orElse(null);

            if (lastOutgoing == null) {
                // No response to last incoming message - DON'T close
                log.debug("Ticket {} not closed: last incoming message has not been responded to", ticket.getId());
                return false;
            }
        }

        return true;
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
