package com.digitalgroup.holape.domain.kpi.service;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.common.enums.KpiType;
import com.digitalgroup.holape.domain.common.enums.MessageDirection;
import com.digitalgroup.holape.domain.common.enums.TicketStatus;
import com.digitalgroup.holape.domain.kpi.entity.Kpi;
import com.digitalgroup.holape.domain.kpi.repository.KpiRepository;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import com.digitalgroup.holape.domain.ticket.repository.TicketRepository;
import com.digitalgroup.holape.domain.common.enums.UserRole;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * KPI Service
 * Handles KPI calculations and aggregations
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KpiService {

    private final KpiRepository kpiRepository;
    private final MessageRepository messageRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;

    /**
     * Calculates KPIs for a client within a date range
     */
    @Transactional(readOnly = true)
    public Map<String, Object> calculateKpis(Long clientId, LocalDate fromDate, LocalDate toDate) {
        LocalDateTime startDate = fromDate.atStartOfDay();
        LocalDateTime endDate = toDate.atTime(LocalTime.MAX);

        Map<String, Object> kpis = new HashMap<>();

        // New clients
        Long newClients = kpiRepository.sumValueByClientKpiTypeAndDateRange(
                clientId, KpiType.NEW_CLIENT, startDate, endDate);
        kpis.put("new_clients", newClients != null ? newClients : 0);

        // New tickets
        Long newTickets = kpiRepository.sumValueByClientKpiTypeAndDateRange(
                clientId, KpiType.NEW_TICKET, startDate, endDate);
        kpis.put("new_tickets", newTickets != null ? newTickets : 0);

        // Closed tickets
        Long closedTickets = kpiRepository.sumValueByClientKpiTypeAndDateRange(
                clientId, KpiType.CLOSED_TICKET, startDate, endDate);
        kpis.put("closed_tickets", closedTickets != null ? closedTickets : 0);

        // Auto-closed tickets
        Long autoClosedTickets = kpiRepository.sumValueByClientKpiTypeAndDateRange(
                clientId, KpiType.AUTO_CLOSED_TICKET, startDate, endDate);
        kpis.put("auto_closed_tickets", autoClosedTickets != null ? autoClosedTickets : 0);

        // Responded to client
        Long respondedToClient = kpiRepository.sumValueByClientKpiTypeAndDateRange(
                clientId, KpiType.RESPONDED_TO_CLIENT, startDate, endDate);
        kpis.put("responded_to_client", respondedToClient != null ? respondedToClient : 0);

        // Sent messages
        Long sentMessages = kpiRepository.sumValueByClientKpiTypeAndDateRange(
                clientId, KpiType.SENT_MESSAGE, startDate, endDate);
        kpis.put("sent_messages", sentMessages != null ? sentMessages : 0);

        // Average first response time (minutes)
        Double avgFirstResponseTime = kpiRepository.avgValueByClientKpiTypeAndDateRange(
                clientId, KpiType.FIRST_RESPONSE_TIME, startDate, endDate);
        kpis.put("avg_first_response_time", avgFirstResponseTime != null ? (long) avgFirstResponseTime.doubleValue() : 0);

        // Average TMO (minutes)
        Double avgTmo = kpiRepository.avgValueByClientKpiTypeAndDateRange(
                clientId, KpiType.TMO, startDate, endDate);
        kpis.put("avg_tmo", avgTmo != null ? (long) avgTmo.doubleValue() : 0);

        // Pending responses
        long pendingResponses = kpiRepository.countByClientKpiTypeAndDateRange(
                clientId, KpiType.REQUIRE_RESPONSE, startDate, endDate);
        kpis.put("pending_responses", pendingResponses);

        // Closed with agreement
        Long closedConAcuerdo = kpiRepository.sumValueByClientKpiTypeAndDateRange(
                clientId, KpiType.CLOSED_CON_ACUERDO, startDate, endDate);
        kpis.put("closed_con_acuerdo", closedConAcuerdo != null ? closedConAcuerdo : 0);

        // Closed without agreement
        Long closedSinAcuerdo = kpiRepository.sumValueByClientKpiTypeAndDateRange(
                clientId, KpiType.CLOSED_SIN_ACUERDO, startDate, endDate);
        kpis.put("closed_sin_acuerdo", closedSinAcuerdo != null ? closedSinAcuerdo : 0);

        return kpis;
    }

    // ==================== RAILS PARITY METHODS ====================

    /**
     * Calculate overall KPIs with values and percentages (Rails parity)
     * Equivalent to Rails Admin::DashboardController#calculate_overall_kpis_for_sectoristas
     * Accepts pre-converted UTC LocalDateTime (timezone conversion done in controller)
     */
    @Transactional(readOnly = true)
    public Map<String, Object> calculateOverallKpisWithPercentages(
            Long clientId,
            List<Long> agentIds,
            LocalDateTime startDate,
            LocalDateTime endDate) {

        try {
            long daysDiff = java.time.temporal.ChronoUnit.DAYS.between(startDate.toLocalDate(), endDate.toLocalDate());
            if (daysDiff == 0) daysDiff = 1;

            // Previous period for comparison
            LocalDateTime previousStartDate = startDate.minusDays(daysDiff);
            LocalDateTime previousEndDate = startDate;

            // Current period values
            Map<String, Object> currentValues = calculateOverallKpisForAgents(clientId, agentIds, startDate, endDate);

            // Previous period values
            Map<String, Object> previousValues = calculateOverallKpisForAgents(clientId, agentIds, previousStartDate, previousEndDate);

            // Calculate percentages
            Map<String, Object> percentages = new HashMap<>();
            for (String key : currentValues.keySet()) {
                Object currentObj = currentValues.get(key);
                Object previousObj = previousValues.get(key);

                double current = currentObj instanceof Number ? ((Number) currentObj).doubleValue() : 0;
                double previous = previousObj instanceof Number ? ((Number) previousObj).doubleValue() : 0;

                double percentage = previous != 0 ? ((current - previous) / previous) * 100 : 0;
                percentages.put(key, Math.round(percentage * 10.0) / 10.0);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("values", currentValues);
            result.put("percentages", percentages);
            return result;
        } catch (Exception e) {
            log.error("Error calculating overall KPIs with percentages: {}", e.getMessage(), e);
            // Return empty structure on error
            Map<String, Object> result = new HashMap<>();
            result.put("values", new HashMap<>());
            result.put("percentages", new HashMap<>());
            return result;
        }
    }

    /**
     * Calculate overall KPIs for a list of agents
     */
    @Transactional(readOnly = true)
    public Map<String, Object> calculateOverallKpisForAgents(
            Long clientId,
            List<Long> agentIds,
            LocalDateTime startDate,
            LocalDateTime endDate) {

        Map<String, Object> kpis = new HashMap<>();

        // Initialize with defaults
        kpis.put("unique_clients", 0L);
        kpis.put("new_cases_period", 0L);
        kpis.put("open_cases", 0L);
        kpis.put("first_response_time", 0L);
        kpis.put("tmo", 0L);
        kpis.put("users_created", 0L);
        kpis.put("unique_clients_contacted", 0L);
        kpis.put("contact_ratio", 0.0);

        boolean hasAgents = agentIds != null && !agentIds.isEmpty();

        try {
            // Unique clients (new_client KPIs)
            if (hasAgents) {
                long uniqueClients = kpiRepository.countByUsersKpiTypeAndDateRange(agentIds, KpiType.NEW_CLIENT, startDate, endDate);
                kpis.put("unique_clients", uniqueClients);
            }

            // New cases/tickets
            if (hasAgents) {
                long newCases = kpiRepository.countByUsersKpiTypeAndDateRange(agentIds, KpiType.NEW_TICKET, startDate, endDate);
                kpis.put("new_cases_period", newCases);
            }

            // Open cases (tickets with status OPEN, filtered by date range - Rails parity)
            if (hasAgents) {
                long openCases = ticketRepository.countByAgentIdInAndStatusAndCreatedAtBetween(
                        agentIds, TicketStatus.OPEN, startDate, endDate);
                kpis.put("open_cases", openCases);
            }

            // First response time (average)
            if (hasAgents) {
                Double avgFrt = kpiRepository.avgValueByUsersKpiTypeAndDateRange(agentIds, KpiType.FIRST_RESPONSE_TIME, startDate, endDate);
                kpis.put("first_response_time", avgFrt != null ? (long) avgFrt.doubleValue() : 0L);
            }

            // TMO (average)
            if (hasAgents) {
                Double avgTmo = kpiRepository.avgValueByUsersKpiTypeAndDateRange(agentIds, KpiType.TMO, startDate, endDate);
                kpis.put("tmo", avgTmo != null ? (long) avgTmo.doubleValue() : 0L);
            }

            // Users created in period (for contact ratio) - Rails: distinct.count(:codigo)
            if (clientId != null) {
                long usersCreated = userRepository.countDistinctCodigoByClientIdAndCreatedAtBetween(clientId, startDate, endDate);
                kpis.put("users_created", usersCreated);

                // Unique clients contacted (Rails: KPIs new_client where user also created in range)
                long uniqueClientsContacted = hasAgents ?
                        kpiRepository.countByUsersKpiTypeAndDateRangeWithUserCreatedInRange(
                                agentIds, KpiType.NEW_CLIENT, clientId, startDate, endDate) : 0L;
                kpis.put("unique_clients_contacted", uniqueClientsContacted);

                // Contact ratio
                double contactRatio = usersCreated > 0 ?
                        Math.round((uniqueClientsContacted * 100.0 / usersCreated) * 100.0) / 100.0 : 0.0;
                kpis.put("contact_ratio", contactRatio);
            }
        } catch (Exception e) {
            log.error("Error calculating overall KPIs for agents: {}", e.getMessage(), e);
        }

        return kpis;
    }

    /**
     * Calculate individual KPIs for each agent (Rails parity)
     * Equivalent to Rails Admin::DashboardController#calculate_individual_kpis_for_sectoristas
     */
    @Transactional(readOnly = true)
    public Map<Long, Map<String, Object>> calculateIndividualKpisForAgents(
            List<Long> agentIds,
            LocalDateTime startDate,
            LocalDateTime endDate) {

        Map<Long, Map<String, Object>> result = new HashMap<>();

        if (agentIds == null || agentIds.isEmpty()) {
            return result;
        }

        for (Long agentId : agentIds) {
            if (agentId == null) continue;

            try {
                Map<String, Object> kpis = new HashMap<>();

                // New cases
                long newCases = kpiRepository.countByUserKpiTypeAndDateRange(agentId, KpiType.NEW_TICKET, startDate, endDate);
                kpis.put("new_cases", newCases);

                // Open cases in period
                long openCasesInPeriod = ticketRepository.countByAgentIdAndStatusAndCreatedAtBetween(
                        agentId, TicketStatus.OPEN, startDate, endDate);
                kpis.put("client_cases_to_close_period", openCasesInPeriod);

                // Closed with agreement
                long closedConAcuerdo = kpiRepository.countByUserKpiTypeAndDateRange(
                        agentId, KpiType.CLOSED_CON_ACUERDO, startDate, endDate);
                kpis.put("closed_con_acuerdo_cases", closedConAcuerdo);

                // Closed without agreement
                long closedSinAcuerdo = kpiRepository.countByUserKpiTypeAndDateRange(
                        agentId, KpiType.CLOSED_SIN_ACUERDO, startDate, endDate);
                kpis.put("closed_sin_acuerdo_cases", closedSinAcuerdo);

                // Unique responded to
                long uniqueRespondedTo = kpiRepository.countByUserKpiTypeAndDateRange(
                        agentId, KpiType.UNIQUE_RESPONDED_TO_CLIENT, startDate, endDate);
                kpis.put("client_unique_responded_to", uniqueRespondedTo);

                // Require response (pending)
                long requireResponse = kpiRepository.countByUserKpiTypeAndDateRange(
                        agentId, KpiType.REQUIRE_RESPONSE, startDate, endDate);
                kpis.put("total_require_response", requireResponse);

                // Clients to respond to
                long clientsToRespondTo = Math.max(requireResponse - uniqueRespondedTo, 0);
                kpis.put("clients_to_respond_to", clientsToRespondTo);

                // Response rate
                int respondedRate = requireResponse > 0 ?
                        Math.min((int) ((uniqueRespondedTo * 100.0) / requireResponse), 100) : 0;
                kpis.put("client_responded_rate", respondedRate);

                // Total sent messages
                long sentMessages = kpiRepository.countByUserKpiTypeAndDateRange(
                        agentId, KpiType.SENT_MESSAGE, startDate, endDate);
                kpis.put("client_total_sent_messages", sentMessages);

                result.put(agentId, kpis);
            } catch (Exception e) {
                log.error("Error calculating individual KPIs for agent {}: {}", agentId, e.getMessage());
                // Continue with next agent
            }
        }

        return result;
    }

    /**
     * Get dropdown options for agent selection (Rails parity)
     */
    @Transactional(readOnly = true)
    public List<Object[]> getAgentDropdownOptions(Long clientId, UserRole role) {
        List<User> agents = userRepository.findByClient_IdAndRole(clientId, role);
        List<Object[]> options = new ArrayList<>();

        // Add "Todos" option first
        options.add(new Object[]{"Todos", "", "Todos"});

        for (User agent : agents) {
            options.add(new Object[]{
                    agent.getFirstName(),
                    agent.getLastName(),
                    agent.getId()
            });
        }

        return options;
    }

    /**
     * Calculates KPIs for a specific user within a date range
     */
    @Transactional(readOnly = true)
    public Map<String, Object> calculateUserKpis(Long userId, LocalDate fromDate, LocalDate toDate) {
        LocalDateTime startDate = fromDate.atStartOfDay();
        LocalDateTime endDate = toDate.atTime(LocalTime.MAX);

        Map<String, Object> kpis = new HashMap<>();

        // Closed tickets by user
        List<Kpi> closedTickets = kpiRepository.findByUserKpiTypeAndDateRange(
                userId, KpiType.CLOSED_TICKET, startDate, endDate);
        kpis.put("closed_tickets", closedTickets.size());

        // Responses by user
        List<Kpi> responses = kpiRepository.findByUserKpiTypeAndDateRange(
                userId, KpiType.RESPONDED_TO_CLIENT, startDate, endDate);
        kpis.put("responses", responses.size());

        // Average first response time for user
        List<Kpi> firstResponseTimes = kpiRepository.findByUserKpiTypeAndDateRange(
                userId, KpiType.FIRST_RESPONSE_TIME, startDate, endDate);
        double avgFirstResponse = firstResponseTimes.stream()
                .mapToInt(Kpi::getValue)
                .average()
                .orElse(0);
        kpis.put("avg_first_response_time", (long) avgFirstResponse);

        // Sent messages by user
        List<Kpi> sentMessages = kpiRepository.findByUserKpiTypeAndDateRange(
                userId, KpiType.SENT_MESSAGE, startDate, endDate);
        kpis.put("sent_messages", sentMessages.size());

        return kpis;
    }

    /**
     * Creates a new KPI record
     */
    @Transactional
    public Kpi createKpi(Kpi kpi) {
        return kpiRepository.save(kpi);
    }

    /**
     * Calculate summary statistics for KPIs
     */
    @Transactional(readOnly = true)
    public Map<String, Object> calculateSummary(Long clientId, LocalDateTime startDate, LocalDateTime endDate) {
        Map<String, Object> summary = new HashMap<>();

        // New clients
        Long newClients = kpiRepository.sumValueByClientKpiTypeAndDateRange(
                clientId, KpiType.NEW_CLIENT, startDate, endDate);
        summary.put("new_clients", newClients != null ? newClients : 0);

        // New tickets
        Long newTickets = kpiRepository.sumValueByClientKpiTypeAndDateRange(
                clientId, KpiType.NEW_TICKET, startDate, endDate);
        summary.put("new_tickets", newTickets != null ? newTickets : 0);

        // Closed tickets
        Long closedTickets = kpiRepository.sumValueByClientKpiTypeAndDateRange(
                clientId, KpiType.CLOSED_TICKET, startDate, endDate);
        summary.put("closed_tickets", closedTickets != null ? closedTickets : 0);

        // Responses
        Long responses = kpiRepository.sumValueByClientKpiTypeAndDateRange(
                clientId, KpiType.RESPONDED_TO_CLIENT, startDate, endDate);
        summary.put("responses", responses != null ? responses : 0);

        // Sent messages
        Long sentMessages = kpiRepository.sumValueByClientKpiTypeAndDateRange(
                clientId, KpiType.SENT_MESSAGE, startDate, endDate);
        summary.put("sent_messages", sentMessages != null ? sentMessages : 0);

        // Average first response time
        Double avgFrt = kpiRepository.avgValueByClientKpiTypeAndDateRange(
                clientId, KpiType.FIRST_RESPONSE_TIME, startDate, endDate);
        summary.put("avg_first_response_time_minutes", avgFrt != null ? (long) avgFrt.doubleValue() : 0);

        // Average TMO
        Double avgTmo = kpiRepository.avgValueByClientKpiTypeAndDateRange(
                clientId, KpiType.TMO, startDate, endDate);
        summary.put("avg_tmo_minutes", avgTmo != null ? (long) avgTmo.doubleValue() : 0);

        return summary;
    }

    /**
     * Get KPI counts grouped by type for a user
     */
    @Transactional(readOnly = true)
    public Map<String, Long> getUserKpiCounts(Long userId, LocalDateTime startDate, LocalDateTime endDate) {
        Map<String, Long> counts = new HashMap<>();

        List<Object[]> results = kpiRepository.countByUserGroupedByType(userId, startDate, endDate);
        for (Object[] row : results) {
            String kpiType = row[0] != null ? row[0].toString() : "unknown";
            Long count = row[1] != null ? ((Number) row[1]).longValue() : 0L;
            counts.put(kpiType, count);
        }

        return counts;
    }

    /**
     * Get agent ranking based on KPI performance
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAgentRanking(Long clientId, LocalDateTime startDate, LocalDateTime endDate, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        List<Object[]> results = kpiRepository.findAgentRanking(clientId, startDate, endDate, pageable);

        List<Map<String, Object>> ranking = new ArrayList<>();
        int rank = 1;

        for (Object[] row : results) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("rank", rank++);
            entry.put("user_id", row[0]);
            entry.put("first_name", row[1]);
            entry.put("last_name", row[2]);
            entry.put("full_name", (row[1] != null ? row[1] : "") + " " + (row[2] != null ? row[2] : ""));
            entry.put("total_kpis", row[3] != null ? ((Number) row[3]).longValue() : 0L);
            entry.put("closed_tickets", row[4] != null ? ((Number) row[4]).longValue() : 0L);
            ranking.add(entry);
        }

        return ranking;
    }

    /**
     * Log a KPI event
     */
    @Transactional
    public void logKpi(Long clientId, Long userId, Long ticketId, String kpiType, Integer value, Integer timeValue) {
        Kpi kpi = new Kpi();
        kpi.setKpiType(KpiType.valueOf(kpiType));
        kpi.setValue(value != null ? value : 1);
        // Store timeValue in dataHash if provided (Rails uses data_hash for extra data)
        if (timeValue != null) {
            kpi.getDataHash().put("time_value", timeValue);
        }
        // Note: client, user, and ticket should be set via entity references
        // This is a simplified version; the actual implementation should use entity lookups
        kpiRepository.save(kpi);
        log.info("Logged KPI: type={}, userId={}, value={}", kpiType, userId, value);
    }

    // ==================== KPI RECONSTRUCTION METHODS ====================

    /**
     * Reconstruct all KPIs for a ticket
     * Equivalent to Rails Kpi.reconstruct_for_ticket(ticket)
     *
     * This recalculates:
     * - first_response_time
     * - sent_message count
     * - responded_to_client count
     * - tmo (tiempo medio operacional)
     */
    @Transactional
    public ReconstructionResult reconstructKpisForTicket(Long ticketId) {
        Optional<Ticket> ticketOpt = ticketRepository.findById(ticketId);
        if (ticketOpt.isEmpty()) {
            log.warn("Cannot reconstruct KPIs: ticket {} not found", ticketId);
            return new ReconstructionResult(0, 0, 0);
        }

        Ticket ticket = ticketOpt.get();
        Client client = ticket.getAgent() != null ? ticket.getAgent().getClient() : null;
        User agent = ticket.getAgent();

        if (client == null || agent == null) {
            log.warn("Cannot reconstruct KPIs: ticket {} missing client or agent", ticketId);
            return new ReconstructionResult(0, 0, 0);
        }

        // Delete existing KPIs for this ticket
        int deleted = 0;
        List<Kpi> existingKpis = kpiRepository.findByTicketId(ticketId);
        if (!existingKpis.isEmpty()) {
            kpiRepository.deleteAll(existingKpis);
            deleted = existingKpis.size();
        }

        List<Message> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
        int created = 0;

        // Calculate first_response_time
        Optional<Message> firstIncoming = messages.stream()
                .filter(m -> m.getDirection() == MessageDirection.INCOMING)
                .findFirst();
        Optional<Message> firstOutgoing = messages.stream()
                .filter(m -> m.getDirection() == MessageDirection.OUTGOING)
                .findFirst();

        if (firstIncoming.isPresent() && firstOutgoing.isPresent()) {
            LocalDateTime incomingTime = firstIncoming.get().getCreatedAt();
            LocalDateTime outgoingTime = firstOutgoing.get().getCreatedAt();

            if (outgoingTime.isAfter(incomingTime)) {
                int responseTimeMinutes = (int) ChronoUnit.MINUTES.between(incomingTime, outgoingTime);
                Kpi frtKpi = Kpi.builder()
                        .client(client)
                        .user(agent)
                        .ticket(ticket)
                        .kpiType(KpiType.FIRST_RESPONSE_TIME)
                        .value(responseTimeMinutes)
                        .build();
                kpiRepository.save(frtKpi);
                created++;
            }
        }

        // Count outgoing messages (sent_message KPI)
        long sentCount = messages.stream()
                .filter(m -> m.getDirection() == MessageDirection.OUTGOING)
                .count();

        if (sentCount > 0) {
            Kpi sentKpi = Kpi.builder()
                    .client(client)
                    .user(agent)
                    .ticket(ticket)
                    .kpiType(KpiType.SENT_MESSAGE)
                    .value((int) sentCount)
                    .build();
            kpiRepository.save(sentKpi);
            created++;
        }

        // Count responded_to_client (incoming followed by outgoing)
        int responseCount = countResponsesToClient(messages);
        if (responseCount > 0) {
            Kpi responseKpi = Kpi.builder()
                    .client(client)
                    .user(agent)
                    .ticket(ticket)
                    .kpiType(KpiType.RESPONDED_TO_CLIENT)
                    .value(responseCount)
                    .build();
            kpiRepository.save(responseKpi);
            created++;
        }

        // Calculate TMO (average response time)
        Integer tmo = calculateTmo(messages);
        if (tmo != null) {
            Kpi tmoKpi = Kpi.builder()
                    .client(client)
                    .user(agent)
                    .ticket(ticket)
                    .kpiType(KpiType.TMO)
                    .value(tmo)
                    .build();
            kpiRepository.save(tmoKpi);
            created++;
        }

        log.info("Reconstructed KPIs for ticket {}: deleted={}, created={}", ticketId, deleted, created);
        return new ReconstructionResult(created, deleted, messages.size());
    }

    /**
     * Reconstruct KPIs for all tickets of a user
     */
    @Transactional
    public ReconstructionResult reconstructKpisForUser(Long userId) {
        List<Ticket> tickets = ticketRepository.findByAgentId(userId);
        int totalCreated = 0;
        int totalDeleted = 0;
        int totalMessages = 0;

        for (Ticket ticket : tickets) {
            ReconstructionResult result = reconstructKpisForTicket(ticket.getId());
            totalCreated += result.created();
            totalDeleted += result.deleted();
            totalMessages += result.messagesProcessed();
        }

        log.info("Reconstructed KPIs for user {}: {} tickets, created={}, deleted={}",
                userId, tickets.size(), totalCreated, totalDeleted);
        return new ReconstructionResult(totalCreated, totalDeleted, totalMessages);
    }

    /**
     * Reconstruct KPIs for all tickets in a date range
     */
    @Transactional
    public ReconstructionResult reconstructKpisForDateRange(Long clientId, LocalDate fromDate, LocalDate toDate) {
        LocalDateTime startDate = fromDate.atStartOfDay();
        LocalDateTime endDate = toDate.atTime(LocalTime.MAX);

        List<Ticket> tickets = ticketRepository.findForExport(clientId, startDate, endDate);
        int totalCreated = 0;
        int totalDeleted = 0;
        int totalMessages = 0;

        for (Ticket ticket : tickets) {
            ReconstructionResult result = reconstructKpisForTicket(ticket.getId());
            totalCreated += result.created();
            totalDeleted += result.deleted();
            totalMessages += result.messagesProcessed();
        }

        log.info("Reconstructed KPIs for client {} in date range {}-{}: {} tickets, created={}, deleted={}",
                clientId, fromDate, toDate, tickets.size(), totalCreated, totalDeleted);
        return new ReconstructionResult(totalCreated, totalDeleted, totalMessages);
    }

    /**
     * Create first_response_time KPI for a ticket if not exists
     */
    @Transactional
    public void createFirstResponseTimeKpi(Ticket ticket, Message firstResponse) {
        if (ticket == null || firstResponse == null) {
            return;
        }

        // Check if FRT KPI already exists
        List<Kpi> existingFrt = kpiRepository.findByTicketId(ticket.getId()).stream()
                .filter(k -> k.getKpiType() == KpiType.FIRST_RESPONSE_TIME)
                .toList();

        if (!existingFrt.isEmpty()) {
            log.debug("First response time KPI already exists for ticket {}", ticket.getId());
            return;
        }

        // Find first incoming message
        Optional<Message> firstIncoming = messageRepository.findFirstByTicketIdAndDirectionOrderByCreatedAtAsc(
                ticket.getId(), MessageDirection.INCOMING);

        if (firstIncoming.isEmpty()) {
            log.debug("No incoming message found for ticket {}", ticket.getId());
            return;
        }

        int responseTimeMinutes = (int) ChronoUnit.MINUTES.between(
                firstIncoming.get().getCreatedAt(),
                firstResponse.getCreatedAt()
        );

        if (responseTimeMinutes < 0) {
            responseTimeMinutes = 0;
        }

        Client client = ticket.getAgent() != null ? ticket.getAgent().getClient() : null;
        User agent = ticket.getAgent();

        Kpi frtKpi = Kpi.builder()
                .client(client)
                .user(agent)
                .ticket(ticket)
                .kpiType(KpiType.FIRST_RESPONSE_TIME)
                .value(responseTimeMinutes)
                .build();

        kpiRepository.save(frtKpi);
        log.info("Created first_response_time KPI for ticket {}: {} minutes", ticket.getId(), responseTimeMinutes);
    }

    /**
     * Create new_client KPI
     */
    @Transactional
    public void createNewClientKpi(User newClient, User agent) {
        if (newClient == null || agent == null) {
            return;
        }

        Client client = agent.getClient();
        if (client == null) {
            return;
        }

        Kpi kpi = Kpi.builder()
                .client(client)
                .user(agent)
                .kpiType(KpiType.NEW_CLIENT)
                .value(1)
                .build();

        Map<String, Object> dataHash = new HashMap<>();
        dataHash.put("new_client_id", newClient.getId());
        dataHash.put("new_client_name", newClient.getFullName());
        kpi.setDataHash(dataHash);

        kpiRepository.save(kpi);
        log.info("Created new_client KPI for agent {} (client: {})", agent.getId(), newClient.getId());
    }

    /**
     * Create new_ticket KPI
     */
    @Transactional
    public void createNewTicketKpi(Ticket ticket) {
        if (ticket == null || ticket.getAgent() == null) {
            return;
        }

        Client client = ticket.getAgent().getClient();
        if (client == null) {
            return;
        }

        Kpi kpi = Kpi.builder()
                .client(client)
                .user(ticket.getAgent())
                .ticket(ticket)
                .kpiType(KpiType.NEW_TICKET)
                .value(1)
                .build();

        kpiRepository.save(kpi);
        log.info("Created new_ticket KPI for ticket {}", ticket.getId());
    }

    /**
     * Create closed_ticket KPI
     */
    @Transactional
    public void createClosedTicketKpi(Ticket ticket, boolean autoClose) {
        if (ticket == null || ticket.getAgent() == null) {
            return;
        }

        Client client = ticket.getAgent().getClient();
        if (client == null) {
            return;
        }

        KpiType kpiType = autoClose ? KpiType.AUTO_CLOSED_TICKET : KpiType.CLOSED_TICKET;

        Kpi kpi = Kpi.builder()
                .client(client)
                .user(ticket.getAgent())
                .ticket(ticket)
                .kpiType(kpiType)
                .value(1)
                .build();

        // Calculate TMO at ticket close
        List<Message> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId());
        Integer tmo = calculateTmo(messages);
        if (tmo != null) {
            Map<String, Object> dataHash = new HashMap<>();
            dataHash.put("tmo_minutes", tmo);
            kpi.setDataHash(dataHash);
        }

        kpiRepository.save(kpi);
        log.info("Created {} KPI for ticket {}", kpiType, ticket.getId());
    }

    /**
     * Create require_response KPI
     */
    @Transactional
    public void createRequireResponseKpi(Message message, User sender, User recipient) {
        if (message == null || sender == null || recipient == null) {
            return;
        }

        Client client = recipient.getClient() != null ? recipient.getClient() : sender.getClient();
        if (client == null) {
            return;
        }

        Map<String, Object> dataHash = new HashMap<>();
        dataHash.put("sender_id", sender.getId());
        dataHash.put("sender_name", sender.getFullName());
        dataHash.put("message_id", message.getId());

        Kpi kpi = Kpi.builder()
                .client(client)
                .user(recipient)
                .kpiType(KpiType.REQUIRE_RESPONSE)
                .value(1)
                .dataHash(dataHash)
                .build();

        kpiRepository.save(kpi);
        log.info("Created require_response KPI for message {} (recipient: {})", message.getId(), recipient.getId());
    }

    /**
     * Create sent_message KPI
     */
    @Transactional
    public void createSentMessageKpi(Message message, User sender) {
        if (message == null || sender == null) {
            return;
        }

        Client client = sender.getClient();
        if (client == null) {
            return;
        }

        Map<String, Object> dataHash = new HashMap<>();
        dataHash.put("message_id", message.getId());

        Kpi kpi = Kpi.builder()
                .client(client)
                .user(sender)
                .ticket(message.getTicket())
                .kpiType(KpiType.SENT_MESSAGE)
                .value(1)
                .dataHash(dataHash)
                .build();

        kpiRepository.save(kpi);
        log.debug("Created sent_message KPI for message {} (sender: {})", message.getId(), sender.getId());
    }

    /**
     * Create responded_to_client KPI
     */
    @Transactional
    public void createRespondedToClientKpi(Message responseMessage, User agent) {
        if (responseMessage == null || agent == null) {
            return;
        }

        Client client = agent.getClient();
        if (client == null) {
            return;
        }

        Map<String, Object> dataHash = new HashMap<>();
        dataHash.put("message_id", responseMessage.getId());

        Kpi kpi = Kpi.builder()
                .client(client)
                .user(agent)
                .ticket(responseMessage.getTicket())
                .kpiType(KpiType.RESPONDED_TO_CLIENT)
                .value(1)
                .dataHash(dataHash)
                .build();

        kpiRepository.save(kpi);
        log.debug("Created responded_to_client KPI for message {} (agent: {})", responseMessage.getId(), agent.getId());
    }

    // ==================== HELPER METHODS ====================

    /**
     * Count number of responses to client messages
     * (outgoing messages that follow incoming messages)
     */
    private int countResponsesToClient(List<Message> messages) {
        int count = 0;
        boolean awaitingResponse = false;

        for (Message message : messages) {
            if (message.getDirection() == MessageDirection.INCOMING) {
                awaitingResponse = true;
            } else if (message.getDirection() == MessageDirection.OUTGOING && awaitingResponse) {
                count++;
                awaitingResponse = false;
            }
        }

        return count;
    }

    /**
     * Calculate TMO (average time to respond to incoming messages)
     */
    private Integer calculateTmo(List<Message> messages) {
        if (messages.isEmpty()) {
            return null;
        }

        List<Long> responseTimes = new ArrayList<>();
        LocalDateTime lastIncomingTime = null;

        for (Message message : messages) {
            if (message.getDirection() == MessageDirection.INCOMING) {
                lastIncomingTime = message.getCreatedAt();
            } else if (message.getDirection() == MessageDirection.OUTGOING && lastIncomingTime != null) {
                long minutes = ChronoUnit.MINUTES.between(lastIncomingTime, message.getCreatedAt());
                if (minutes >= 0) {
                    responseTimes.add(minutes);
                }
                lastIncomingTime = null;
            }
        }

        if (responseTimes.isEmpty()) {
            return null;
        }

        return (int) responseTimes.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0);
    }

    /**
     * Result of KPI reconstruction
     */
    public record ReconstructionResult(int created, int deleted, int messagesProcessed) {
        public int netChange() {
            return created - deleted;
        }
    }

    // ========== Scheduled KPI Calculation Methods ==========

    /**
     * Calculate daily KPIs for a client
     * Called by KpiCalculationJob
     */
    @Transactional
    public void calculateDailyKpis(Long clientId, LocalDate date) {
        log.info("Calculating daily KPIs for client {} on {}", clientId, date);
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(23, 59, 59);

        // Aggregate KPIs for the day - counts by type
        List<Object[]> results = kpiRepository.countGroupedByKpiType(clientId, startOfDay, endOfDay);
        Map<String, Long> dailyTotals = results.stream()
                .collect(java.util.stream.Collectors.toMap(
                        r -> r[0].toString(),
                        r -> (Long) r[1]
                ));

        log.debug("Daily KPI totals for client {}: {}", clientId, dailyTotals);
    }

    /**
     * Calculate weekly KPIs for a client
     * Called by KpiCalculationJob
     */
    @Transactional
    public void calculateWeeklyKpis(Long clientId, LocalDate weekStart, LocalDate weekEnd) {
        log.info("Calculating weekly KPIs for client {} from {} to {}", clientId, weekStart, weekEnd);
        LocalDateTime start = weekStart.atStartOfDay();
        LocalDateTime end = weekEnd.atTime(23, 59, 59);

        // Aggregate KPIs for the week
        List<Object[]> results = kpiRepository.countGroupedByKpiType(clientId, start, end);
        Map<String, Long> weeklyTotals = results.stream()
                .collect(java.util.stream.Collectors.toMap(
                        r -> r[0].toString(),
                        r -> (Long) r[1]
                ));

        log.debug("Weekly KPI totals for client {}: {}", clientId, weeklyTotals);
    }

    /**
     * Calculate monthly KPIs for a client
     * Called by KpiCalculationJob
     */
    @Transactional
    public void calculateMonthlyKpis(Long clientId, LocalDate monthStart, LocalDate monthEnd) {
        log.info("Calculating monthly KPIs for client {} from {} to {}", clientId, monthStart, monthEnd);
        LocalDateTime start = monthStart.atStartOfDay();
        LocalDateTime end = monthEnd.atTime(23, 59, 59);

        // Aggregate KPIs for the month
        List<Object[]> results = kpiRepository.countGroupedByKpiType(clientId, start, end);
        Map<String, Long> monthlyTotals = results.stream()
                .collect(java.util.stream.Collectors.toMap(
                        r -> r[0].toString(),
                        r -> (Long) r[1]
                ));

        log.debug("Monthly KPI totals for client {}: {}", clientId, monthlyTotals);
    }

    /**
     * Update realtime KPIs for dashboard display
     * Called by KpiCalculationJob every 5 minutes during business hours
     */
    @Transactional
    public void updateRealtimeKpis(Long clientId) {
        log.debug("Updating realtime KPIs for client {}", clientId);
        LocalDateTime today = LocalDate.now().atStartOfDay();
        LocalDateTime now = LocalDateTime.now();

        // Calculate today's real-time metrics
        long newTicketsToday = kpiRepository.countByClientIdAndKpiTypeAndCreatedAtBetween(
                clientId, KpiType.NEW_TICKET, today, now);
        long closedTicketsToday = kpiRepository.countByClientIdAndKpiTypeAndCreatedAtBetween(
                clientId, KpiType.CLOSED_TICKET, today, now);
        long messagesReceivedToday = kpiRepository.countByClientIdAndKpiTypeAndCreatedAtBetween(
                clientId, KpiType.REQUIRE_RESPONSE, today, now);
        long messagesSentToday = kpiRepository.countByClientIdAndKpiTypeAndCreatedAtBetween(
                clientId, KpiType.SENT_MESSAGE, today, now);

        log.debug("Realtime KPIs for client {}: tickets={}/{}, messages=in:{}/out:{}",
                clientId, newTicketsToday, closedTicketsToday, messagesReceivedToday, messagesSentToday);
    }
}
