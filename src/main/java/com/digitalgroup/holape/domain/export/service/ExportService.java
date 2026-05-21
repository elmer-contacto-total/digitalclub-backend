package com.digitalgroup.holape.domain.export.service;

import com.digitalgroup.holape.domain.audit.entity.Audit;
import com.digitalgroup.holape.domain.audit.repository.AuditRepository;
import com.digitalgroup.holape.domain.kpi.entity.Kpi;
import com.digitalgroup.holape.domain.kpi.repository.KpiRepository;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import com.digitalgroup.holape.domain.ticket.repository.TicketRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Export Service
 * Handles CSV and ZIP exports for various data types
 * Equivalent to Rails export functionality in controllers
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;
    private final KpiRepository kpiRepository;
    private final AuditRepository auditRepository;
    private final MessageRepository messageRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String CSV_SEPARATOR = ",";
    private static final int BATCH_SIZE = 1000;

    /**
     * Export users to CSV
     */
    public byte[] exportUsersCsv(Long clientId) {
        log.info("Exporting users CSV for client {}", clientId);

        StringBuilder csv = new StringBuilder();
        csv.append("id,phone,email,first_name,last_name,role,status,manager_id,manager_email,created_at,last_message_at\n");

        int page = 0;
        int total = 0;
        Page<User> batch;
        do {
            batch = userRepository.findByClient_Id(clientId, PageRequest.of(page, BATCH_SIZE, Sort.by("id").ascending()));
            for (User user : batch.getContent()) {
                csv.append(escapeCsv(user.getId()))
                        .append(CSV_SEPARATOR).append(escapeCsv(user.getPhone()))
                        .append(CSV_SEPARATOR).append(escapeCsv(user.getEmail()))
                        .append(CSV_SEPARATOR).append(escapeCsv(user.getFirstName()))
                        .append(CSV_SEPARATOR).append(escapeCsv(user.getLastName()))
                        .append(CSV_SEPARATOR).append(escapeCsv(user.getRole() != null ? user.getRole().name() : ""))
                        .append(CSV_SEPARATOR).append(escapeCsv(user.getStatus() != null ? user.getStatus().name() : ""))
                        .append(CSV_SEPARATOR).append(escapeCsv(user.getManager() != null ? user.getManager().getId() : null))
                        .append(CSV_SEPARATOR).append(escapeCsv(user.getManager() != null ? user.getManager().getEmail() : ""))
                        .append(CSV_SEPARATOR).append(formatDate(user.getCreatedAt()))
                        .append(CSV_SEPARATOR).append(formatDate(user.getLastMessageAt()))
                        .append("\n");
            }
            total += batch.getNumberOfElements();
            page++;
        } while (batch.hasNext());

        log.info("Exported {} users for client {}", total, clientId);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Export tickets to CSV
     */
    public byte[] exportTicketsCsv(Long clientId, LocalDateTime startDate, LocalDateTime endDate) {
        log.info("Exporting tickets CSV for client {} from {} to {}", clientId, startDate, endDate);

        StringBuilder csv = new StringBuilder();
        csv.append("id,user_id,user_phone,user_name,agent_id,agent_name,status,close_type,created_at,closed_at,message_count\n");

        int page = 0;
        int total = 0;
        Page<Ticket> batch;
        do {
            batch = ticketRepository.findForExportPaged(clientId, startDate, endDate, PageRequest.of(page, BATCH_SIZE));
            for (Ticket ticket : batch.getContent()) {
                User user = ticket.getUser();
                User agent = ticket.getAgent();
                csv.append(escapeCsv(ticket.getId()))
                        .append(CSV_SEPARATOR).append(escapeCsv(user != null ? user.getId() : null))
                        .append(CSV_SEPARATOR).append(escapeCsv(user != null ? user.getPhone() : ""))
                        .append(CSV_SEPARATOR).append(escapeCsv(user != null ? user.getFullName() : ""))
                        .append(CSV_SEPARATOR).append(escapeCsv(agent != null ? agent.getId() : null))
                        .append(CSV_SEPARATOR).append(escapeCsv(agent != null ? agent.getFullName() : ""))
                        .append(CSV_SEPARATOR).append(escapeCsv(ticket.getStatus() != null ? ticket.getStatus().name() : ""))
                        .append(CSV_SEPARATOR).append(escapeCsv(ticket.getCloseType()))
                        .append(CSV_SEPARATOR).append(formatDate(ticket.getCreatedAt()))
                        .append(CSV_SEPARATOR).append(formatDate(ticket.getClosedAt()))
                        .append(CSV_SEPARATOR).append(ticket.getMessageCount() != null ? ticket.getMessageCount() : 0)
                        .append("\n");
            }
            total += batch.getNumberOfElements();
            page++;
        } while (batch.hasNext());

        log.info("Exported {} tickets for client {}", total, clientId);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Export KPIs to CSV
     */
    public byte[] exportKpisCsv(Long clientId, LocalDateTime startDate, LocalDateTime endDate) {
        log.info("Exporting KPIs CSV for client {} from {} to {}", clientId, startDate, endDate);

        StringBuilder csv = new StringBuilder();
        csv.append("id,kpi_type,value,user_id,user_name,ticket_id,created_at\n");

        int page = 0;
        int total = 0;
        Page<Kpi> batch;
        do {
            batch = kpiRepository.findByClientAndDateRangePaged(clientId, startDate, endDate, PageRequest.of(page, BATCH_SIZE));
            for (Kpi kpi : batch.getContent()) {
                User user = kpi.getUser();
                csv.append(escapeCsv(kpi.getId()))
                        .append(CSV_SEPARATOR).append(escapeCsv(kpi.getKpiType() != null ? kpi.getKpiType().name() : ""))
                        .append(CSV_SEPARATOR).append(kpi.getValue() != null ? kpi.getValue() : 0)
                        .append(CSV_SEPARATOR).append(escapeCsv(user != null ? user.getId() : null))
                        .append(CSV_SEPARATOR).append(escapeCsv(user != null ? user.getFullName() : ""))
                        .append(CSV_SEPARATOR).append(escapeCsv(kpi.getTicket() != null ? kpi.getTicket().getId() : null))
                        .append(CSV_SEPARATOR).append(formatDate(kpi.getCreatedAt()))
                        .append("\n");
            }
            total += batch.getNumberOfElements();
            page++;
        } while (batch.hasNext());

        log.info("Exported {} KPIs for client {}", total, clientId);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Export audits to CSV
     */
    public byte[] exportAuditsCsv(Long clientId, LocalDateTime startDate, LocalDateTime endDate) {
        log.info("Exporting audits CSV for client {} from {} to {}", clientId, startDate, endDate);

        StringBuilder csv = new StringBuilder();
        csv.append("id,action,auditable_type,auditable_id,user_id,user_name,changes,created_at\n");

        int page = 0;
        int total = 0;
        Page<Audit> batch;
        do {
            batch = auditRepository.findByClientAndDateRangePaged(clientId, startDate, endDate, PageRequest.of(page, BATCH_SIZE));
            for (Audit audit : batch.getContent()) {
                User user = audit.getUser();
                csv.append(escapeCsv(audit.getId()))
                        .append(CSV_SEPARATOR).append(escapeCsv(audit.getAction()))
                        .append(CSV_SEPARATOR).append(escapeCsv(audit.getAuditableType()))
                        .append(CSV_SEPARATOR).append(escapeCsv(audit.getAuditableId()))
                        .append(CSV_SEPARATOR).append(escapeCsv(user != null ? user.getId() : null))
                        .append(CSV_SEPARATOR).append(escapeCsv(user != null ? user.getFullName() : ""))
                        .append(CSV_SEPARATOR).append(escapeCsv(audit.getAuditedChanges() != null ? audit.getAuditedChanges().toString() : ""))
                        .append(CSV_SEPARATOR).append(formatDate(audit.getCreatedAt()))
                        .append("\n");
            }
            total += batch.getNumberOfElements();
            page++;
        } while (batch.hasNext());

        log.info("Exported {} audits for client {}", total, clientId);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Export messages to CSV
     */
    public byte[] exportMessagesCsv(Long clientId, LocalDateTime startDate, LocalDateTime endDate) {
        log.info("Exporting messages CSV for client {} from {} to {}", clientId, startDate, endDate);

        StringBuilder csv = new StringBuilder();
        csv.append("id,ticket_id,sender_id,sender_name,recipient_id,recipient_name,direction,content,message_type,status,created_at\n");

        int page = 0;
        int total = 0;
        Page<Message> batch;
        do {
            batch = messageRepository.findByClientAndDateRangePaged(clientId, startDate, endDate, PageRequest.of(page, BATCH_SIZE));
            for (Message message : batch.getContent()) {
                User sender = message.getSender();
                User recipient = message.getRecipient();
                csv.append(escapeCsv(message.getId()))
                        .append(CSV_SEPARATOR).append(escapeCsv(message.getTicket() != null ? message.getTicket().getId() : null))
                        .append(CSV_SEPARATOR).append(escapeCsv(sender != null ? sender.getId() : null))
                        .append(CSV_SEPARATOR).append(escapeCsv(sender != null ? sender.getFullName() : ""))
                        .append(CSV_SEPARATOR).append(escapeCsv(recipient != null ? recipient.getId() : null))
                        .append(CSV_SEPARATOR).append(escapeCsv(recipient != null ? recipient.getFullName() : ""))
                        .append(CSV_SEPARATOR).append(escapeCsv(message.getDirection() != null ? message.getDirection().name() : ""))
                        .append(CSV_SEPARATOR).append(escapeCsv(truncateContent(message.getContent())))
                        .append(CSV_SEPARATOR).append(escapeCsv("WHATSAPP"))
                        .append(CSV_SEPARATOR).append(escapeCsv(message.getStatus() != null ? message.getStatus().name() : ""))
                        .append(CSV_SEPARATOR).append(formatDate(message.getCreatedAt()))
                        .append("\n");
            }
            total += batch.getNumberOfElements();
            page++;
        } while (batch.hasNext());

        log.info("Exported {} messages for client {}", total, clientId);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Export KPI summary/dashboard data to CSV
     */
    public byte[] exportDashboardCsv(Long clientId, LocalDateTime startDate, LocalDateTime endDate) {
        log.info("Exporting dashboard CSV for client {} from {} to {}", clientId, startDate, endDate);

        StringBuilder csv = new StringBuilder();
        csv.append("Metric,Value\n");

        // Get aggregated KPI data
        List<Object[]> kpiSummary = kpiRepository.getKpiSummaryByClient(clientId, startDate, endDate);

        for (Object[] row : kpiSummary) {
            String kpiType = row[0] != null ? row[0].toString() : "Unknown";
            Long total = row[1] != null ? ((Number) row[1]).longValue() : 0L;

            csv.append(escapeCsv(kpiType))
                    .append(CSV_SEPARATOR).append(total)
                    .append("\n");
        }

        // Add ticket stats
        long openTickets = ticketRepository.countOpenByClient(clientId);
        csv.append("Open Tickets").append(CSV_SEPARATOR).append(openTickets).append("\n");

        log.info("Exported dashboard for client {}", clientId);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Create ZIP file with multiple exports
     */
    public byte[] exportAllAsZip(Long clientId, LocalDateTime startDate, LocalDateTime endDate) throws IOException {
        log.info("Creating ZIP export for client {} from {} to {}", clientId, startDate, endDate);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        String timestamp = LocalDateTime.now().format(FILE_DATE_FORMATTER);

        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Users CSV
            addToZip(zos, "users_" + timestamp + ".csv", exportUsersCsv(clientId));

            // Tickets CSV
            addToZip(zos, "tickets_" + timestamp + ".csv", exportTicketsCsv(clientId, startDate, endDate));

            // KPIs CSV
            addToZip(zos, "kpis_" + timestamp + ".csv", exportKpisCsv(clientId, startDate, endDate));

            // Messages CSV
            addToZip(zos, "messages_" + timestamp + ".csv", exportMessagesCsv(clientId, startDate, endDate));

            // Audits CSV
            addToZip(zos, "audits_" + timestamp + ".csv", exportAuditsCsv(clientId, startDate, endDate));

            // Dashboard summary CSV
            addToZip(zos, "dashboard_summary_" + timestamp + ".csv", exportDashboardCsv(clientId, startDate, endDate));
        }

        log.info("ZIP export created for client {}", clientId);
        return baos.toByteArray();
    }

    /**
     * Export agent performance report
     */
    public byte[] exportAgentPerformanceCsv(Long clientId, LocalDateTime startDate, LocalDateTime endDate) {
        log.info("Exporting agent performance for client {}", clientId);

        List<Object[]> agentStats = kpiRepository.getAgentPerformance(clientId, startDate, endDate);

        StringBuilder csv = new StringBuilder();
        csv.append("agent_id,agent_name,total_tickets,closed_tickets,avg_response_time_min,total_messages,first_response_count\n");

        for (Object[] row : agentStats) {
            csv.append(row[0] != null ? row[0] : "")  // agent_id
                    .append(CSV_SEPARATOR).append(escapeCsv(row[1]))  // agent_name
                    .append(CSV_SEPARATOR).append(row[2] != null ? row[2] : 0)  // total_tickets
                    .append(CSV_SEPARATOR).append(row[3] != null ? row[3] : 0)  // closed_tickets
                    .append(CSV_SEPARATOR).append(row[4] != null ? row[4] : 0)  // avg_response_time
                    .append(CSV_SEPARATOR).append(row[5] != null ? row[5] : 0)  // total_messages
                    .append(CSV_SEPARATOR).append(row[6] != null ? row[6] : 0)  // first_response_count
                    .append("\n");
        }

        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Generate filename with timestamp
     */
    public String generateFilename(String baseName, String extension) {
        String timestamp = LocalDateTime.now().format(FILE_DATE_FORMATTER);
        return baseName + "_" + timestamp + "." + extension;
    }

    // Helper methods

    private void addToZip(ZipOutputStream zos, String filename, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(filename);
        zos.putNextEntry(entry);
        zos.write(content);
        zos.closeEntry();
    }

    private String escapeCsv(Object value) {
        if (value == null) return "";
        String str = value.toString();
        // Escape quotes and wrap in quotes if contains special characters
        if (str.contains(",") || str.contains("\"") || str.contains("\n") || str.contains("\r")) {
            return "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
    }

    private String formatDate(LocalDateTime dateTime) {
        if (dateTime == null) return "";
        return dateTime.format(DATE_FORMATTER);
    }

    private String truncateContent(String content) {
        if (content == null) return "";
        // Truncate long content and remove newlines for CSV
        String cleaned = content.replace("\n", " ").replace("\r", " ");
        if (cleaned.length() > 200) {
            return cleaned.substring(0, 197) + "...";
        }
        return cleaned;
    }
}
