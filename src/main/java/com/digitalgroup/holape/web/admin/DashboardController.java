package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.repository.ClientRepository;
import com.digitalgroup.holape.domain.client.repository.ClientSettingRepository;
import com.digitalgroup.holape.domain.common.enums.MessageDirection;
import com.digitalgroup.holape.domain.common.enums.UserRole;
import com.digitalgroup.holape.domain.kpi.service.KpiService;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import com.digitalgroup.holape.domain.ticket.repository.TicketRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.multitenancy.TenantContext;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.digitalgroup.holape.util.DateTimeUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Dashboard Controller
 * Equivalent to Rails Admin::DashboardController
 *
 * PARIDAD RAILS: Endpoints y respuestas compatibles con el frontend Angular
 */
@Slf4j
@RestController
@RequestMapping("/app")
@RequiredArgsConstructor
public class DashboardController {

    private final KpiService kpiService;
    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final ClientSettingRepository clientSettingRepository;
    private final TicketRepository ticketRepository;
    private final MessageRepository messageRepository;

    /**
     * Dashboard data endpoint
     */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> show(@AuthenticationPrincipal CustomUserDetails user) {
        Long clientId = user.getClientId();

        Map<String, Object> response = new HashMap<>();
        response.put("user", Map.of(
                "id", user.getId(),
                "email", user.getEmail(),
                "name", user.getFullName(),
                "role", user.getRole()
        ));
        response.put("clientId", clientId);

        // Get today's KPIs as default (use user's timezone, not system timezone)
        LocalDate today = LocalDate.now(DateTimeUtils.resolveZoneId(user.getTimeZone()));
        Map<String, Object> kpis = kpiService.calculateKpis(clientId, today, today);
        response.put("kpis", kpis);

        return ResponseEntity.ok(response);
    }

    /**
     * AJAX endpoint for KPI calculation (Rails parity)
     * Equivalent to Rails Admin::DashboardController#ajax_get_kpis
     *
     * Parameters:
     * - button_id: 'today', 'last_7', 'last_30', 'last_180', 'last_custom'
     * - object: 'agent', 'manager_level_4', 'Cliente' (type of entity to filter by)
     * - object_option: specific user ID or 'Todos' for all
     * - from_date, to_date: for custom date range (when button_id = 'last_custom')
     */
    @GetMapping("/calculate_kpis")
    public ResponseEntity<Map<String, Object>> calculateKpis(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam(required = false, defaultValue = "today") String button_id,
            @RequestParam(required = false, defaultValue = "agent") String object,
            @RequestParam(required = false, defaultValue = "Todos") String object_option,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from_date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to_date) {

        try {
            Long clientId = user.getClientId();
            UserRole userRole = user.getUserRole();

            log.debug("calculateKpis called: clientId={}, userRole={}, button_id={}, object={}, object_option={}",
                    clientId, userRole, button_id, object, object_option);

            if (clientId == null) {
                log.error("User clientId is null");
                return ResponseEntity.badRequest().body(Map.of("error", "Client ID is required"));
            }

            // Calculate date range based on button_id (use user's timezone, not system timezone)
            LocalDate fromDate;
            LocalDate toDate = LocalDate.now(DateTimeUtils.resolveZoneId(user.getTimeZone()));
            String comparisonLabel;

            switch (button_id) {
                case "last_7":
                    fromDate = toDate.minusDays(7);
                    comparisonLabel = "Desde la semana anterior";
                    break;
                case "last_30":
                    fromDate = toDate.minusDays(30);
                    comparisonLabel = "Desde el mes anterior";
                    break;
                case "last_180":
                    fromDate = toDate.minusDays(180);
                    comparisonLabel = "Desde el semestre anterior";
                    break;
                case "last_custom":
                    if (from_date != null && to_date != null) {
                        fromDate = from_date;
                        toDate = to_date;
                        long days = java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate);
                        comparisonLabel = String.format("Desde el período anterior (%d días)", days);
                    } else {
                        fromDate = toDate;
                        comparisonLabel = "Desde ayer";
                    }
                    break;
                default: // "today"
                    fromDate = toDate;
                    comparisonLabel = "Desde ayer";
                    break;
            }

            // Convert dates to UTC using user's timezone (Rails parity)
            String userTimezone = user.getTimeZone();
            LocalDateTime startDate = DateTimeUtils.startOfDayInUtc(fromDate, userTimezone);
            LocalDateTime endDate = DateTimeUtils.endOfDayInUtc(toDate, userTimezone);

            // Determine which agents to include based on object type and user role
            List<Long> agentIds = getAgentIdsForCalculation(clientId, userRole, user.getId(), object, object_option);
            log.debug("Agent IDs for calculation: {}", agentIds);

            // Calculate daysDiff from original LocalDate range (before UTC conversion)
            long daysDiff = java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate);
            if (daysDiff == 0) daysDiff = 1;

            // Calculate overall KPIs with percentages
            Map<String, Object> overallKpis = kpiService.calculateOverallKpisWithPercentages(
                    clientId, agentIds, startDate, endDate, daysDiff);

            // Calculate individual KPIs per agent
            Map<Long, Map<String, Object>> individualKpis = kpiService.calculateIndividualKpisForAgents(
                    agentIds, startDate, endDate);

            // Get dropdown options based on object type
            List<Object[]> dropdownOptions = getDropdownOptions(clientId, userRole, user.getId(), object);

            // Check if client has ticket_close_types configured
            // PARIDAD RAILS: @show_close_type_kpis = @current_client.client_settings.find_by(name: 'ticket_close_types').try(:hash_value).present?
            boolean showCloseTypeKpis = clientSettingRepository.existsByClientIdAndNameWithHashValue(
                    clientId, "ticket_close_types");

            // Build response (Rails-compatible structure)
            Map<String, Object> response = new HashMap<>();
            response.put("overall_kpis", overallKpis);
            response.put("individual_kpis", individualKpis);
            response.put("comparison_label", comparisonLabel);
            response.put("dropdown_options", dropdownOptions);
            response.put("show_close_type_kpis", showCloseTypeKpis);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error calculating KPIs: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Error calculating KPIs",
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
            ));
        }
    }

    /**
     * Get agent IDs for KPI calculation based on filters
     * PARIDAD RAILS: calculate_kpis method in dashboard_controller.rb
     *
     * Key differences from previous implementation:
     * - object='manager_level_4' + "Todos" → ALL AGENTS (not supervisors)
     * - object='manager_level_4' + specific supervisor → supervisor's SUBORDINATES (not the supervisor ID itself)
     * - object='Cliente' → all agents of the active client (same as "Todos")
     */
    private List<Long> getAgentIdsForCalculation(
            Long clientId,
            UserRole userRole,
            Long currentUserId,
            String object,
            String objectOption) {

        // PARIDAD RAILS: 'Cliente' → KPIs de todos los agentes del cliente activo
        if ("Cliente".equals(object)) {
            return userRepository.findByClient_IdAndRole(clientId, UserRole.AGENT)
                    .stream().map(User::getId).collect(Collectors.toList());
        }

        // PARIDAD RAILS: when 'manager_level_4'
        if ("manager_level_4".equals(object)) {
            if (objectOption == null || "Todos".equals(objectOption)) {
                // Rails: User.where(client_id: @current_client.id, role: 'agent').pluck(:id)
                return userRepository.findByClient_IdAndRole(clientId, UserRole.AGENT)
                        .stream().map(User::getId).collect(Collectors.toList());
            } else {
                // Rails: User.find(object_option).subordinates.pluck(:id)
                // A supervisor's subordinates are agents managed by them
                try {
                    Long supervisorId = Long.parseLong(objectOption);
                    return userRepository.findByManager_Id(supervisorId)
                            .stream()
                            .map(User::getId)
                            .collect(Collectors.toList());
                } catch (NumberFormatException e) {
                    return new ArrayList<>();
                }
            }
        }

        // PARIDAD RAILS: else branch — default agent-level logic
        // If a specific agent is selected
        if (objectOption != null && !objectOption.equals("Todos")) {
            try {
                // Rails: list_of_sectoristas = [object_option]
                return List.of(Long.parseLong(objectOption));
            } catch (NumberFormatException e) {
                // Fall through to default behavior
            }
        }

        // "Todos" or no specific selection — depends on current user's role
        switch (userRole) {
            case SUPER_ADMIN:
            case ADMIN:
                // Rails: agent_list = User.where(client_id: @current_client.id, role: 'agent')
                return userRepository.findByClient_IdAndRole(clientId, UserRole.AGENT)
                        .stream().map(User::getId).collect(Collectors.toList());

            case MANAGER_LEVEL_4:
                // Rails: agent_list = User.where(client_id: @current_client.id, manager_id: current_user.id, role: 'agent')
                if ("agent".equals(object)) {
                    return userRepository.findByManager_Id(currentUserId)
                            .stream()
                            .filter(u -> u.getRole() == UserRole.AGENT)
                            .map(User::getId)
                            .collect(Collectors.toList());
                } else {
                    // Rails: list_of_sectoristas = [current_user.id]
                    return List.of(currentUserId);
                }

            case AGENT:
                // Rails: list_of_sectoristas = [current_user.id]
                return List.of(currentUserId);

            default:
                return List.of(currentUserId);
        }
    }

    /**
     * Get dropdown options for agent/supervisor selection
     * PARIDAD RAILS: options_list logic in calculate_kpis
     */
    private List<Object[]> getDropdownOptions(
            Long clientId,
            UserRole userRole,
            Long currentUserId,
            String object) {

        List<Object[]> options = new ArrayList<>();
        options.add(new Object[]{"Todos", "", "Todos"});

        if (userRole == null) {
            return options;
        }

        // PARIDAD RAILS: 'Cliente' → dropdown with client name
        if ("Cliente".equals(object)) {
            Client client = clientRepository.findById(clientId).orElse(null);
            if (client != null) {
                options.add(new Object[]{client.getName(), " ", client.getId()});
            }
            return options;
        }

        // PARIDAD RAILS: 'manager_level_4' → dropdown with supervisors list
        if ("manager_level_4".equals(object)) {
            List<User> supervisors = userRepository.findByClient_IdAndRole(clientId, UserRole.MANAGER_LEVEL_4);
            for (User u : supervisors) {
                String firstName = u.getFirstName() != null ? u.getFirstName() : "";
                String lastName = u.getLastName() != null ? u.getLastName() : "";
                options.add(new Object[]{firstName, lastName, u.getId()});
            }
            return options;
        }

        // Default: agent-level dropdown
        List<User> users;

        switch (userRole) {
            case SUPER_ADMIN:
            case ADMIN:
                users = userRepository.findByClient_IdAndRole(clientId, UserRole.AGENT);
                break;

            case MANAGER_LEVEL_4:
                users = userRepository.findByManager_Id(currentUserId)
                        .stream()
                        .filter(u -> u.getRole() == UserRole.AGENT)
                        .collect(Collectors.toList());
                break;

            default:
                users = new ArrayList<>();
                break;
        }

        if (users != null) {
            for (User u : users) {
                String firstName = u.getFirstName() != null ? u.getFirstName() : "";
                String lastName = u.getLastName() != null ? u.getLastName() : "";
                options.add(new Object[]{firstName, lastName, u.getId()});
            }
        }

        return options;
    }

    /**
     * Export KPIs to CSV — one row per ticket (Rails parity)
     * PARIDAD RAILS: Admin::DashboardController#export_kpis
     * Rails allows admin? (includes super_admin) and manager_level_4
     */
    @GetMapping("/export_kpis")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_4')")
    public ResponseEntity<byte[]> exportKpis(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam(required = false, defaultValue = "30") int last_x_days,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from_date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to_date) {

        Long clientId = user.getClientId();

        LocalDate fromDate;
        LocalDate toDate;

        if (from_date != null && to_date != null) {
            fromDate = from_date;
            toDate = to_date;
        } else {
            toDate = LocalDate.now(DateTimeUtils.resolveZoneId(user.getTimeZone()));
            fromDate = toDate.minusDays(last_x_days);
        }

        // Convert dates to UTC using user's timezone (Rails parity)
        String userTimezone = user.getTimeZone();
        LocalDateTime startDate = DateTimeUtils.startOfDayInUtc(fromDate, userTimezone);
        LocalDateTime endDate = DateTimeUtils.endOfDayInUtc(toDate, userTimezone);

        // Query all tickets for the client in the range (Rails does NOT filter by agent)
        List<Ticket> tickets = ticketRepository.findForKpiExport(clientId, startDate, endDate);

        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yy");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

        // Build CSV with BOM for Excel compatibility
        StringBuilder csv = new StringBuilder();
        csv.append('\ufeff'); // UTF-8 BOM
        csv.append("Ticket Id,Fecha,Hora,Nombre Campaña,Nombre del Asesor,Nombre del Cliente,");
        csv.append("Móvil del Cliente,Clientes Únicos (DNI),Caso Finalizado,Últimos Mensajes,");
        csv.append("Tiempo de Respuesta (min),Tiempo de Respuesta (dd:hh:mm),TMO (min),TMO (dd:hh:mm)\n");

        for (Ticket ticket : tickets) {
            // First incoming message (for date/time and response time calculation)
            Optional<Message> firstIncoming = messageRepository
                    .findFirstByTicketIdAndDirectionOrderByCreatedAtAsc(ticket.getId(), MessageDirection.INCOMING);

            // First outgoing message (first response)
            Optional<Message> firstOutgoing = messageRepository
                    .findFirstResponseToTicket(ticket.getId());

            // Last 3 incoming messages
            List<Message> lastIncoming = messageRepository
                    .findLastIncomingByTicketId(ticket.getId(), PageRequest.of(0, 3));

            // first_incoming_message_sent_at (fallback to ticket.createdAt)
            LocalDateTime firstIncomingSentAt = firstIncoming
                    .map(m -> m.getSentAt() != null ? m.getSentAt() : m.getCreatedAt())
                    .orElse(ticket.getCreatedAt());

            // Convert to user's timezone for display
            LocalDateTime displayDateTime = DateTimeUtils.toTimezone(firstIncomingSentAt, userTimezone);
            String fecha = displayDateTime.format(dateFormatter);
            String hora = displayDateTime.format(timeFormatter);

            // Campaign name (from agent's manager)
            String campaignName = ticket.getAgent() != null ? ticket.getAgent().getCampaignName() : "";

            // Agent name
            String agentName = ticket.getAgent() != null ? ticket.getAgent().getFullName() : "";

            // Client name & phone & DNI
            User client = ticket.getUser();
            String clientName = client != null ? client.getFullName() : "";
            String clientPhone = client != null && client.getPhone() != null ? client.getPhone() : "";
            String dni = client != null && client.getCodigo() != null ? client.getCodigo() : "";

            // Caso Finalizado
            String casoFinalizado;
            if (ticket.isOpen()) {
                casoFinalizado = "Ticket Abierto";
            } else if ("sin_acuerdo".equals(ticket.getCloseType())) {
                casoFinalizado = "Sin Acuerdo";
            } else {
                casoFinalizado = "Con Acuerdo";
            }

            // Last 3 incoming messages joined with " | "
            String ultimosMensajes = lastIncoming.stream()
                    .map(m -> m.getContent() != null ? m.getContent() : "")
                    .collect(Collectors.joining(" | "));

            // First response time (minutes between first incoming and first outgoing)
            Long firstResponseMin = null;
            if (firstIncoming.isPresent() && firstOutgoing.isPresent()) {
                LocalDateTime inAt = firstIncoming.get().getSentAt() != null
                        ? firstIncoming.get().getSentAt() : firstIncoming.get().getCreatedAt();
                LocalDateTime outAt = firstOutgoing.get().getCreatedAt();
                long mins = Duration.between(inAt, outAt).toMinutes();
                firstResponseMin = Math.max(mins, 0L);
            }

            // TMO (minutes between first incoming and closedAt — only for closed tickets)
            Long tmoMin = null;
            if (ticket.isClosed() && ticket.getClosedAt() != null && firstIncoming.isPresent()) {
                LocalDateTime inAt = firstIncoming.get().getSentAt() != null
                        ? firstIncoming.get().getSentAt() : firstIncoming.get().getCreatedAt();
                long mins = Duration.between(inAt, ticket.getClosedAt()).toMinutes();
                tmoMin = Math.max(mins, 0L);
            }

            // Write CSV row
            csv.append(ticket.getId()).append(",");
            csv.append(escapeCSV(fecha)).append(",");
            csv.append(escapeCSV(hora)).append(",");
            csv.append(escapeCSV(campaignName)).append(",");
            csv.append(escapeCSV(agentName)).append(",");
            csv.append(escapeCSV(clientName)).append(",");
            csv.append(escapeCSV(clientPhone)).append(",");
            csv.append(escapeCSV(dni)).append(",");
            csv.append(escapeCSV(casoFinalizado)).append(",");
            csv.append(escapeCSV(ultimosMensajes)).append(",");
            csv.append(firstResponseMin != null ? firstResponseMin : "").append(",");
            csv.append(firstResponseMin != null ? formatDdHhMm(firstResponseMin) : "").append(",");
            csv.append(tmoMin != null ? tmoMin : "").append(",");
            csv.append(tmoMin != null ? formatDdHhMm(tmoMin) : "");
            csv.append("\n");
        }

        byte[] csvBytes = csv.toString().getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=utf-8"));
        headers.setContentDispositionFormData("attachment",
                "kpi_export_" + LocalDate.now() + ".csv");

        log.info("Exported {} tickets to KPI CSV", tickets.size());

        return ResponseEntity.ok()
                .headers(headers)
                .body(csvBytes);
    }

    private String formatDdHhMm(long totalMinutes) {
        long days = totalMinutes / 1440;
        long hours = (totalMinutes % 1440) / 60;
        long mins = totalMinutes % 60;
        return String.format("%02d:%02d:%02d", days, hours, mins);
    }

    /**
     * Export contacts to CSV
     * Equivalent to Rails: Admin::DashboardController#export_contacts
     * Exports all standard users (clients) from the current client
     */
    @GetMapping("/export_contacts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4', 'AGENT', 'STAFF')")
    public ResponseEntity<byte[]> exportContacts(
            @AuthenticationPrincipal CustomUserDetails user) {

        Long clientId = user.getClientId();

        // PARIDAD RAILS: current_user.subordinates — scope by role
        List<Object[]> contacts;
        UserRole userRole = user.getUserRole();

        if (userRole == UserRole.SUPER_ADMIN || userRole == UserRole.ADMIN || userRole == UserRole.STAFF) {
            // Admins/staff ven todos los contactos
            contacts = userRepository.findContactsForExport(clientId, UserRole.STANDARD.getValue());
        } else if (userRole == UserRole.MANAGER_LEVEL_4) {
            // Supervisor: clientes de sus agentes
            List<Long> agentIds = userRepository.findByManager_Id(user.getId())
                    .stream().filter(u -> u.getRole() == UserRole.AGENT)
                    .map(User::getId).collect(Collectors.toList());
            if (agentIds.isEmpty()) {
                contacts = List.of();
            } else {
                contacts = userRepository.findContactsForExportByManagers(clientId, agentIds, UserRole.STANDARD.getValue());
            }
        } else {
            // AGENT, MANAGER_LEVEL_1/2/3: solo sus subordinados directos
            contacts = userRepository.findContactsForExportByManager(clientId, user.getId(), UserRole.STANDARD.getValue());
        }

        // Build CSV with BOM
        StringBuilder csv = new StringBuilder();
        csv.append('\ufeff'); // UTF-8 BOM
        csv.append("ID,Codigo,Nombre,Apellido,Telefono,Email,Manager,Ultimo Mensaje,Creado\n");

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        // Each row: [id, codigo, first_name, last_name, phone, email, manager_name, last_message_at, created_at]
        for (Object[] row : contacts) {
            csv.append(row[0]).append(",");
            csv.append(escapeCSV(str(row[1]))).append(",");
            csv.append(escapeCSV(str(row[2]))).append(",");
            csv.append(escapeCSV(str(row[3]))).append(",");
            csv.append(escapeCSV(str(row[4]))).append(",");
            csv.append(escapeCSV(str(row[5]))).append(",");
            csv.append(escapeCSV(str(row[6]))).append(",");
            csv.append(row[7] != null ? ((Timestamp) row[7]).toLocalDateTime().format(formatter) : "").append(",");
            csv.append(row[8] != null ? ((Timestamp) row[8]).toLocalDateTime().format(formatter) : "");
            csv.append("\n");
        }

        byte[] csvBytes = csv.toString().getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=utf-8"));
        headers.setContentDispositionFormData("attachment",
                "contacts_export_" + LocalDate.now() + ".csv");

        log.info("Exported {} contacts to CSV", contacts.size());

        return ResponseEntity.ok()
                .headers(headers)
                .body(csvBytes);
    }

    private String escapeCSV(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String str(Object o) {
        return o != null ? o.toString() : "";
    }

    /**
     * Not authorized page
     */
    @GetMapping("/not_authorized")
    public ResponseEntity<Map<String, String>> notAuthorized() {
        return ResponseEntity.status(403)
                .body(Map.of("error", "You are not authorized to access this resource"));
    }
}
