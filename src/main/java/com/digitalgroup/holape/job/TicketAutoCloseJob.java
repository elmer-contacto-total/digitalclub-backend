package com.digitalgroup.holape.job;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.repository.ClientRepository;
import com.digitalgroup.holape.domain.client.service.ClientService;
import com.digitalgroup.holape.domain.common.enums.MessageDirection;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import com.digitalgroup.holape.domain.ticket.repository.TicketRepository;
import com.digitalgroup.holape.domain.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Ticket Auto Close Job
 * Equivalent to Rails Ticket.close_expired_tickets cron job
 * Automatically closes tickets that have been inactive for the configured time
 *
 * Aligned with actual entity structure:
 * - Uses ClientService to get settings by name
 * - Iterates over active clients
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketAutoCloseJob {

    private final TicketRepository ticketRepository;
    private final TicketService ticketService;
    private final ClientRepository clientRepository;
    private final ClientService clientService;
    private final MessageRepository messageRepository;

    /**
     * Auto-close expired tickets
     * PARIDAD RAILS: close_expired_tickets in ticket.rb
     * Runs every hour — single scheduled entry point (removed duplicate from TicketService)
     */
    // DISABLED: Delegated to Rails
    // @Scheduled(cron = "0 0 * * * *") // Every hour at minute 0
    @Transactional
    public void closeExpiredTickets() {
        log.info("Starting auto-close job for expired tickets");

        List<Client> activeClients = clientService.findAllActive();
        int totalClosed = 0;

        for (Client client : activeClients) {
            try {
                Integer autoCloseHours = clientService.getTicketAutoCloseHours(client.getId());
                if (autoCloseHours == null || autoCloseHours <= 0) {
                    continue;
                }

                LocalDateTime cutoffTime = LocalDateTime.now().minusHours(autoCloseHours);

                // PARIDAD RAILS: client.tickets.open.each do |ticket|
                List<Ticket> openTickets = ticketRepository.findOpenTicketsByClient(client.getId());

                for (Ticket ticket : openTickets) {
                    try {
                        if (shouldAutoClose(ticket, cutoffTime)) {
                            ticketService.closeTicketAutoClose(ticket.getId());
                            totalClosed++;
                        }
                    } catch (Exception e) {
                        log.error("Failed to auto-close ticket {}", ticket.getId(), e);
                    }
                }
            } catch (Exception e) {
                log.error("Error processing auto-close for client {}", client.getId(), e);
            }
        }

        log.info("Auto-close job completed. Total closed: {}", totalClosed);
    }

    /**
     * PARIDAD RAILS: close_expired_tickets inner logic
     * - Checks last_message.sent_at < cutoffTime
     * - If last message is incoming, verifies it was responded to
     */
    private boolean shouldAutoClose(Ticket ticket, LocalDateTime cutoffTime) {
        // PARIDAD RAILS: last_message = ticket.messages.order(:sent_at, :id).last
        Message lastMessage = messageRepository.findLastByTicketId(ticket.getId()).orElse(null);

        if (lastMessage == null || lastMessage.getSentAt() == null) {
            return false;
        }

        // PARIDAD RAILS: if last_message.sent_at < hours_to_close.hours.ago
        if (!lastMessage.getSentAt().isBefore(cutoffTime)) {
            return false;
        }

        // PARIDAD RAILS: if last_message.direction == 'incoming' → check responded
        if (lastMessage.getDirection() == MessageDirection.INCOMING) {
            Optional<Message> anyOutgoing = messageRepository
                    .findFirstByTicketIdAndDirectionOrderByCreatedAtAsc(
                            ticket.getId(), MessageDirection.OUTGOING);
            if (anyOutgoing.isEmpty()) {
                log.debug("Ticket {} not closed: last incoming not responded to", ticket.getId());
                return false;
            }
        }

        return true;
    }

    /**
     * Send warnings for tickets about to expire
     * Runs every 30 minutes
     */
    // DISABLED: Delegated to Rails
    // @Scheduled(cron = "0 */30 * * * *") // Every 30 minutes
    @Transactional
    public void sendExpirationWarnings() {
        log.debug("Checking for tickets about to expire");

        List<Client> activeClients = clientService.findAllActive();

        for (Client client : activeClients) {
            try {
                Integer autoCloseHours = clientService.getTicketAutoCloseHours(client.getId());
                if (autoCloseHours == null || autoCloseHours <= 0) {
                    continue;
                }

                // Warn 2 hours before expiration
                int warningHours = Math.max(autoCloseHours - 2, 0);
                LocalDateTime warningThreshold = LocalDateTime.now().minusHours(warningHours);
                LocalDateTime closeThreshold = LocalDateTime.now().minusHours(autoCloseHours);

                List<Ticket> aboutToExpire = ticketRepository.findTicketsAboutToExpire(
                        client.getId(), warningThreshold, closeThreshold);

                for (Ticket ticket : aboutToExpire) {
                    // Send warning notification
                    // Could use WebSocket or push notification
                    log.debug("Ticket {} about to expire", ticket.getId());
                }

            } catch (Exception e) {
                log.error("Error checking expiration warnings for client {}", client.getId(), e);
            }
        }
    }
}
