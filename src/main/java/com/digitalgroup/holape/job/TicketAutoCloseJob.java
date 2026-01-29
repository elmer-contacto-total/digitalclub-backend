package com.digitalgroup.holape.job;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.repository.ClientRepository;
import com.digitalgroup.holape.domain.client.service.ClientService;
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

    private static final int DEFAULT_AUTO_CLOSE_HOURS = 24;

    /**
     * Auto-close expired tickets
     * Runs every hour
     */
    @Scheduled(cron = "0 0 * * * *") // Every hour at minute 0
    @Transactional
    public void closeExpiredTickets() {
        log.info("Starting auto-close job for expired tickets");

        // Get all active clients
        List<Client> activeClients = clientService.findAllActive();

        int totalClosed = 0;

        for (Client client : activeClients) {
            try {
                // Get the auto-close hours setting for this client
                Integer autoCloseHours = clientService.getTicketAutoCloseHours(client.getId());
                if (autoCloseHours == null) {
                    autoCloseHours = DEFAULT_AUTO_CLOSE_HOURS;
                }

                LocalDateTime threshold = LocalDateTime.now().minusHours(autoCloseHours);

                List<Ticket> expiredTickets = ticketRepository.findExpiredTickets(
                        client.getId(), threshold);

                for (Ticket ticket : expiredTickets) {
                    try {
                        ticketService.closeTicket(ticket.getId(), "auto_inactivity");
                        totalClosed++;
                    } catch (Exception e) {
                        log.error("Failed to auto-close ticket {}", ticket.getId(), e);
                    }
                }

                if (!expiredTickets.isEmpty()) {
                    log.info("Auto-closed {} tickets for client {}",
                            expiredTickets.size(), client.getId());
                }

            } catch (Exception e) {
                log.error("Error processing auto-close for client {}", client.getId(), e);
            }
        }

        log.info("Auto-close job completed. Total closed: {}", totalClosed);
    }

    /**
     * Send warnings for tickets about to expire
     * Runs every 30 minutes
     */
    @Scheduled(cron = "0 */30 * * * *") // Every 30 minutes
    @Transactional
    public void sendExpirationWarnings() {
        log.debug("Checking for tickets about to expire");

        List<Client> activeClients = clientService.findAllActive();

        for (Client client : activeClients) {
            try {
                Integer autoCloseHours = clientService.getTicketAutoCloseHours(client.getId());
                if (autoCloseHours == null) {
                    autoCloseHours = DEFAULT_AUTO_CLOSE_HOURS;
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
