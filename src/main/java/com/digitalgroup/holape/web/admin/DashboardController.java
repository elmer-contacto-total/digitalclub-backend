package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.client.repository.ClientSettingRepository;
import com.digitalgroup.holape.domain.common.enums.UserRole;
import com.digitalgroup.holape.domain.kpi.service.KpiService;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.multitenancy.TenantContext;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final ClientSettingRepository clientSettingRepository;

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

        // Get today's KPIs as default
        LocalDate today = LocalDate.now();
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

            // Calculate date range based on button_id
            LocalDate fromDate;
            LocalDate toDate = LocalDate.now();
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

            LocalDateTime startDate = fromDate.atStartOfDay();
            LocalDateTime endDate = toDate.atTime(LocalTime.MAX);

            // Determine which agents to include based on object type and user role
            List<Long> agentIds = getAgentIdsForCalculation(clientId, userRole, user.getId(), object, object_option);
            log.debug("Agent IDs for calculation: {}", agentIds);

            // Calculate overall KPIs with percentages
            Map<String, Object> overallKpis = kpiService.calculateOverallKpisWithPercentages(
                    clientId, agentIds, fromDate, toDate);

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
     */
    private List<Long> getAgentIdsForCalculation(
            Long clientId,
            UserRole userRole,
            Long currentUserId,
            String object,
            String objectOption) {

        List<Long> agentIds = new ArrayList<>();

        // If a specific user is selected
        if (objectOption != null && !objectOption.equals("Todos")) {
            try {
                agentIds.add(Long.parseLong(objectOption));
                return agentIds;
            } catch (NumberFormatException e) {
                // Fall through to default behavior
            }
        }

        // Get agents based on role and object type
        switch (userRole) {
            case SUPER_ADMIN:
                if ("manager_level_4".equals(object)) {
                    // Get all supervisors
                    agentIds = userRepository.findByClient_IdAndRole(clientId, UserRole.MANAGER_LEVEL_4)
                            .stream().map(User::getId).collect(Collectors.toList());
                } else {
                    // Get all agents
                    agentIds = userRepository.findByClient_IdAndRole(clientId, UserRole.AGENT)
                            .stream().map(User::getId).collect(Collectors.toList());
                }
                break;

            case ADMIN:
                // Admin sees all agents
                agentIds = userRepository.findByClient_IdAndRole(clientId, UserRole.AGENT)
                        .stream().map(User::getId).collect(Collectors.toList());
                break;

            case MANAGER_LEVEL_4:
                // Supervisor sees only their agents
                agentIds = userRepository.findByManager_Id(currentUserId)
                        .stream()
                        .filter(u -> u.getRole() == UserRole.AGENT)
                        .map(User::getId)
                        .collect(Collectors.toList());
                break;

            case AGENT:
                // Agent sees only their own KPIs
                agentIds.add(currentUserId);
                break;

            default:
                // For other roles, show only their own
                agentIds.add(currentUserId);
                break;
        }

        return agentIds;
    }

    /**
     * Get dropdown options for agent/supervisor selection
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

        List<User> users;

        switch (userRole) {
            case SUPER_ADMIN:
                if ("manager_level_4".equals(object)) {
                    users = userRepository.findByClient_IdAndRole(clientId, UserRole.MANAGER_LEVEL_4);
                } else {
                    users = userRepository.findByClient_IdAndRole(clientId, UserRole.AGENT);
                }
                break;

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
     * Export KPIs to CSV
     * Only managers, admins, and staff can export KPIs
     */
    @GetMapping("/export_kpis")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4', 'STAFF')")
    public ResponseEntity<byte[]> exportKpis(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam(required = false, defaultValue = "30") int last_x_days,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from_date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to_date,
            @RequestParam(required = false, defaultValue = "agent") String object,
            @RequestParam(required = false, defaultValue = "Todos") String object_option) {

        Long clientId = user.getClientId();

        LocalDate fromDate;
        LocalDate toDate;

        if (from_date != null && to_date != null) {
            fromDate = from_date;
            toDate = to_date;
        } else {
            toDate = LocalDate.now();
            fromDate = toDate.minusDays(last_x_days);
        }

        List<Long> agentIds = getAgentIdsForCalculation(
                clientId, user.getUserRole(), user.getId(), object, object_option);

        Map<String, Object> overallKpis = kpiService.calculateOverallKpisWithPercentages(
                clientId, agentIds, fromDate, toDate);

        // Build CSV with BOM for Excel compatibility
        StringBuilder csv = new StringBuilder();
        csv.append('\ufeff'); // UTF-8 BOM
        csv.append("Tipo de KPI,Nombre de KPI,Valor,Cambio en %\n");

        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) overallKpis.get("values");
        @SuppressWarnings("unchecked")
        Map<String, Object> percentages = (Map<String, Object>) overallKpis.get("percentages");

        Map<String, String> kpiNames = Map.of(
                "unique_clients", "Clientes Únicos",
                "new_cases_period", "Nuevos Casos",
                "open_cases", "Total Casos Abiertos",
                "first_response_time", "Tiempo 1era Respuesta",
                "tmo", "TMO",
                "users_created", "Usuarios Creados",
                "unique_clients_contacted", "Clientes Contactados",
                "contact_ratio", "Tasa de Contacto"
        );

        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Object percentage = percentages.get(key);
            String displayName = kpiNames.getOrDefault(key, key);

            csv.append("Generales,");
            csv.append(displayName).append(",");
            csv.append(value).append(",");
            csv.append(percentage != null ? percentage + "%" : "N/A");
            csv.append("\n");
        }

        byte[] csvBytes = csv.toString().getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=utf-8"));
        headers.setContentDispositionFormData("attachment",
                "kpi_export_" + LocalDate.now() + ".csv");

        return ResponseEntity.ok()
                .headers(headers)
                .body(csvBytes);
    }

    /**
     * Export contacts to CSV
     * Equivalent to Rails: Admin::DashboardController#export_contacts
     * Exports all standard users (clients) from the current client
     */
    @GetMapping("/export_contacts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4', 'STAFF')")
    public ResponseEntity<byte[]> exportContacts(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam(required = false) Long managerId) {

        Long clientId = user.getClientId();

        List<User> contacts;
        if (managerId != null) {
            // Filter by specific manager/agent
            contacts = userRepository.findByClient_IdAndManager_IdAndRole(clientId, managerId, UserRole.STANDARD);
        } else {
            // All standard users for the client
            contacts = userRepository.findByClient_IdAndRole(clientId, UserRole.STANDARD);
        }

        // Build CSV with BOM
        StringBuilder csv = new StringBuilder();
        csv.append('\ufeff'); // UTF-8 BOM
        csv.append("ID,Codigo,Nombre,Apellido,Telefono,Email,Manager,Ultimo Mensaje,Creado\n");

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        for (User contact : contacts) {
            csv.append(contact.getId()).append(",");
            csv.append(escapeCSV(contact.getCodigo())).append(",");
            csv.append(escapeCSV(contact.getFirstName())).append(",");
            csv.append(escapeCSV(contact.getLastName())).append(",");
            csv.append(escapeCSV(contact.getPhone())).append(",");
            csv.append(escapeCSV(contact.getEmail())).append(",");
            csv.append(contact.getManager() != null ? escapeCSV(contact.getManager().getFullName()) : "").append(",");
            csv.append(contact.getLastMessageAt() != null ? contact.getLastMessageAt().format(formatter) : "").append(",");
            csv.append(contact.getCreatedAt() != null ? contact.getCreatedAt().format(formatter) : "");
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

    /**
     * Not authorized page
     */
    @GetMapping("/not_authorized")
    public ResponseEntity<Map<String, String>> notAuthorized() {
        return ResponseEntity.status(403)
                .body(Map.of("error", "You are not authorized to access this resource"));
    }
}
