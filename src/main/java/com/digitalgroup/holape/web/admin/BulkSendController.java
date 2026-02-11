package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.bulksend.entity.BulkSend;
import com.digitalgroup.holape.domain.bulksend.entity.BulkSendRecipient;
import com.digitalgroup.holape.domain.bulksend.entity.BulkSendRule;
import com.digitalgroup.holape.domain.bulksend.repository.BulkSendRecipientRepository;
import com.digitalgroup.holape.domain.bulksend.repository.BulkSendRepository;
import com.digitalgroup.holape.domain.bulksend.service.BulkSendService;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import com.digitalgroup.holape.security.CustomUserDetails;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bulk Send Controller
 * Manages mass sending via Electron (CSV-based)
 */
@Slf4j
@RestController
@RequestMapping("/app/bulk_sends")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4', 'AGENT')")
public class BulkSendController {

    private final BulkSendService bulkSendService;
    private final BulkSendRepository bulkSendRepository;
    private final BulkSendRecipientRepository recipientRepository;
    private final ObjectMapper objectMapper;

    /**
     * List agents assignable for bulk sends (based on current user's role)
     */
    @GetMapping("/assignable_agents")
    public ResponseEntity<Map<String, Object>> getAssignableAgents(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        List<User> agents = bulkSendService.getAssignableAgents(
                currentUser.getId(), currentUser.getClientId(), currentUser.getUserRole());

        List<Map<String, Object>> agentList = agents.stream()
                .map(a -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", a.getId());
                    m.put("name", a.getFullName());
                    m.put("email", a.getEmail());
                    m.put("role", a.getRole().name());
                    return m;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("agents", agentList));
    }

    /**
     * Preview CSV — parse and return headers + first rows
     */
    @PostMapping("/csv/preview")
    public ResponseEntity<Map<String, Object>> previewCsv(
            @RequestParam("csv") MultipartFile csvFile) throws IOException {

        List<String[]> rows = parseCsv(csvFile);
        if (rows.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El archivo CSV está vacío"));
        }

        String[] headers = rows.get(0);
        List<String[]> dataRows = rows.subList(1, Math.min(rows.size(), 6)); // first 5 data rows

        // Auto-detect phone and name columns
        int phoneCol = detectColumn(headers, "phone", "telefono", "teléfono", "celular", "numero", "número", "whatsapp");
        int nameCol = detectColumn(headers, "name", "nombre", "nombre_completo", "full_name");

        return ResponseEntity.ok(Map.of(
                "headers", headers,
                "preview_rows", dataRows,
                "total_rows", rows.size() - 1,
                "phone_column", phoneCol,
                "name_column", nameCol
        ));
    }

    /**
     * Create bulk send from CSV
     */
    @PostMapping("/csv")
    @Transactional
    public ResponseEntity<Map<String, Object>> createFromCsv(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam("csv") MultipartFile csvFile,
            @RequestParam("message_content") String messageContent,
            @RequestParam("phone_column") int phoneColumn,
            @RequestParam(value = "name_column", defaultValue = "-1") int nameColumn,
            @RequestParam(value = "assigned_agent_id", required = false) Long assignedAgentId,
            @RequestParam(value = "attachment", required = false) MultipartFile attachment) throws IOException {

        List<String[]> rows = parseCsv(csvFile);
        if (rows.size() < 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "El CSV debe tener al menos una fila de datos"));
        }

        String[] headers = rows.get(0);
        List<BulkSendService.CsvRecipientDTO> recipients = new ArrayList<>();

        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (row.length <= phoneColumn) continue;

            String phone = normalizePhone(row[phoneColumn]);
            if (phone == null || phone.isBlank()) continue;

            String name = (nameColumn >= 0 && nameColumn < row.length) ? row[nameColumn].trim() : "";

            // Build custom variables from all other columns
            Map<String, String> variables = new HashMap<>();
            for (int c = 0; c < Math.min(row.length, headers.length); c++) {
                if (c != phoneColumn && c != nameColumn) {
                    variables.put(headers[c].trim().toLowerCase().replace(" ", "_"), row[c].trim());
                }
            }

            recipients.add(new BulkSendService.CsvRecipientDTO(phone, name, variables));
        }

        if (recipients.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No se encontraron destinatarios válidos en el CSV"));
        }

        // Handle attachment
        String attachmentPath = null;
        String attachmentType = null;
        Long attachmentSize = null;
        String attachmentOriginalName = null;

        if (attachment != null && !attachment.isEmpty()) {
            attachmentOriginalName = attachment.getOriginalFilename();
            attachmentType = detectAttachmentType(attachmentOriginalName);
            attachmentSize = attachment.getSize();

            // Save to temp directory
            Path tempDir = Files.createTempDirectory("bulk_send_");
            Path savedFile = tempDir.resolve(attachmentOriginalName != null ? attachmentOriginalName : "attachment");
            Files.copy(attachment.getInputStream(), savedFile, StandardCopyOption.REPLACE_EXISTING);
            attachmentPath = savedFile.toAbsolutePath().toString();
        }

        BulkSend bulkSend = bulkSendService.createFromCsv(
                currentUser.getId(),
                currentUser.getClientId(),
                messageContent,
                attachmentPath,
                attachmentType,
                attachmentSize,
                attachmentOriginalName,
                recipients,
                assignedAgentId
        );

        log.info("Bulk send {} created by user {} with {} recipients",
                bulkSend.getId(), currentUser.getId(), recipients.size());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "bulk_send", mapBulkSendToResponse(bulkSend),
                "message", "Envío masivo creado. Inicia el envío desde la aplicación de escritorio."
        ));
    }

    /**
     * List bulk sends
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> index(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<BulkSend> bulkSendsPage;

        boolean isSupervisor = currentUser.isAdmin() || currentUser.isManager();

        if (currentUser.isSuperAdmin()) {
            bulkSendsPage = bulkSendRepository.findAll(pageable);
        } else if (isSupervisor) {
            if (status != null && !status.isBlank()) {
                bulkSendsPage = bulkSendRepository.findByClientIdAndStatusOrderByCreatedAtDesc(
                        currentUser.getClientId(), status.toUpperCase(), pageable);
            } else {
                bulkSendsPage = bulkSendRepository.findByClientIdOrderByCreatedAtDesc(
                        currentUser.getClientId(), pageable);
            }
        } else {
            // Agents see sends assigned to them
            if (status != null && !status.isBlank()) {
                bulkSendsPage = bulkSendRepository.findByAssignedAgentIdAndStatusOrderByCreatedAtDesc(
                        currentUser.getId(), status.toUpperCase(), pageable);
            } else {
                bulkSendsPage = bulkSendRepository.findByAssignedAgentIdOrderByCreatedAtDesc(
                        currentUser.getId(), pageable);
            }
        }

        List<Map<String, Object>> data = bulkSendsPage.getContent().stream()
                .map(this::mapBulkSendToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "bulk_sends", data,
                "total", bulkSendsPage.getTotalElements(),
                "page", page,
                "totalPages", bulkSendsPage.getTotalPages()
        ));
    }

    /**
     * Get bulk send detail with recipients
     */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> show(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "0") int recipientPage,
            @RequestParam(required = false, defaultValue = "50") int recipientSize) {

        BulkSend bulkSend = bulkSendRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BulkSend", id));

        Pageable pageable = PageRequest.of(recipientPage, recipientSize);
        Page<BulkSendRecipient> recipientsPage = recipientRepository.findByBulkSendId(id, pageable);

        List<Map<String, Object>> recipientData = recipientsPage.getContent().stream()
                .map(this::mapRecipientToResponse)
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>(mapBulkSendToResponse(bulkSend));
        response.put("recipients", recipientData);
        response.put("recipients_total", recipientsPage.getTotalElements());
        response.put("recipients_page", recipientPage);
        response.put("recipients_total_pages", recipientsPage.getTotalPages());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable Long id) {
        bulkSendService.pauseBulkSend(id);
        return ResponseEntity.ok(Map.of("result", "success", "message", "Envío pausado"));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable Long id) {
        bulkSendService.resumeBulkSend(id);
        return ResponseEntity.ok(Map.of("result", "success", "message", "Envío reanudado"));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long id) {
        bulkSendService.cancelBulkSend(id);
        return ResponseEntity.ok(Map.of("result", "success", "message", "Envío cancelado"));
    }

    /**
     * Create a BulkSend from a BulkMessage template text + provided recipients.
     * The BulkMessage only provides the message text; recipients come from the request body.
     */
    @PostMapping("/from-bulk-message/{bulkMessageId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> createFromBulkMessage(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @PathVariable Long bulkMessageId,
            @RequestBody FromBulkMessageRequest request) {

        com.digitalgroup.holape.domain.message.entity.BulkMessage bulkMessage =
                bulkSendService.findBulkMessage(bulkMessageId);

        if (request.recipients() == null || request.recipients().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Se requiere al menos un destinatario"));
        }

        List<BulkSendService.CsvRecipientDTO> recipients = request.recipients().stream()
                .filter(r -> r.phone() != null && !r.phone().isBlank())
                .map(r -> new BulkSendService.CsvRecipientDTO(r.phone(), r.name() != null ? r.name() : "", Map.of()))
                .collect(Collectors.toList());

        if (recipients.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No se encontraron destinatarios válidos"));
        }

        BulkSend bulkSend = bulkSendService.createFromCsv(
                currentUser.getId(),
                currentUser.getClientId(),
                bulkMessage.getMessage(),
                null, null, null, null,
                recipients,
                request.assignedAgentId()
        );

        log.info("BulkSend {} created from BulkMessage {} with {} recipients",
                bulkSend.getId(), bulkMessageId, recipients.size());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "bulk_send", mapBulkSendToResponse(bulkSend),
                "message", "Envío masivo creado desde mensaje predefinido"
        ));
    }

    /**
     * Get next pending recipient for Electron polling
     */
    @GetMapping("/{id}/next-recipient")
    public ResponseEntity<Map<String, Object>> nextRecipient(@PathVariable Long id) {
        BulkSend bulkSend = bulkSendRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BulkSend", id));

        Optional<Map<String, Object>> next = bulkSendService.getNextRecipient(id);

        if (next.isEmpty()) {
            Map<String, Object> emptyResponse = new HashMap<>();
            emptyResponse.put("has_next", false);
            emptyResponse.put("total_recipients", bulkSend.getTotalRecipients());
            emptyResponse.put("message", "No hay más destinatarios pendientes");

            // Check if it was due to daily limit
            if (bulkSend.getAssignedAgent() != null && bulkSend.getClient() != null) {
                BulkSendRule rules = bulkSendService.getOrCreateRules(bulkSend.getClient().getId());
                long sentToday = bulkSendRepository.sumSentByAssignedAgentSince(
                        bulkSend.getAssignedAgent().getId(),
                        LocalDateTime.of(LocalDate.now(), LocalTime.MIN));
                if (sentToday >= rules.getMaxDailyMessages()) {
                    emptyResponse.put("daily_limit_reached", true);
                    emptyResponse.put("message", "Límite diario de mensajes alcanzado (" + rules.getMaxDailyMessages() + ")");
                }
            }

            return ResponseEntity.ok(emptyResponse);
        }

        Map<String, Object> response = new HashMap<>(next.get());
        response.put("has_next", true);
        response.put("total_recipients", bulkSend.getTotalRecipients());
        return ResponseEntity.ok(response);
    }

    /**
     * Report result for a recipient (Electron)
     */
    @PostMapping("/{id}/recipient-result")
    public ResponseEntity<Map<String, Object>> recipientResult(
            @PathVariable Long id,
            @RequestBody RecipientResultRequest request) {

        bulkSendService.reportRecipientResult(id, request.recipientId(), request.success(), request.errorMessage());

        BulkSend bulkSend = bulkSendRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BulkSend", id));

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "bulk_send_status", bulkSend.getStatus(),
                "sent_count", bulkSend.getSentCount(),
                "failed_count", bulkSend.getFailedCount(),
                "progress_percent", bulkSend.getProgressPercent()
        ));
    }

    /**
     * Get send rules (supervisors only)
     */
    @GetMapping("/rules")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4')")
    public ResponseEntity<Map<String, Object>> getRules(
            @AuthenticationPrincipal CustomUserDetails currentUser) {
        BulkSendRule rules = bulkSendService.getOrCreateRules(currentUser.getClientId());
        return ResponseEntity.ok(Map.of("rules", mapRulesToResponse(rules)));
    }

    /**
     * Update send rules (supervisors only)
     */
    @PutMapping("/rules")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4')")
    public ResponseEntity<Map<String, Object>> updateRules(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody Map<String, Object> request) {
        BulkSendRule rules = bulkSendService.updateRules(currentUser.getClientId(), request);
        return ResponseEntity.ok(Map.of(
                "result", "success",
                "rules", mapRulesToResponse(rules)
        ));
    }

    // --- CSV parsing helpers ---

    private List<String[]> parseCsv(MultipartFile file) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                // Simple CSV parsing — handles quoted fields
                rows.add(parseCsvLine(line));
            }
        }
        return rows;
    }

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if ((c == ',' || c == ';') && !inQuotes) {
                fields.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }

    private int detectColumn(String[] headers, String... candidates) {
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim().toLowerCase().replace(" ", "_");
            for (String candidate : candidates) {
                if (h.equals(candidate) || h.contains(candidate)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String normalizePhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("[^0-9+]", "").trim();
        if (digits.isEmpty()) return null;
        // Remove leading + if present and ensure it's a reasonable phone
        if (digits.startsWith("+")) digits = digits.substring(1);
        return digits.length() >= 8 ? digits : null;
    }

    private String detectAttachmentType(String filename) {
        if (filename == null) return "document";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif")) {
            return "image";
        } else if (lower.endsWith(".mp4") || lower.endsWith(".avi") || lower.endsWith(".mov")) {
            return "video";
        } else {
            return "document";
        }
    }

    // --- Response mapping ---

    private Map<String, Object> mapBulkSendToResponse(BulkSend bulkSend) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", bulkSend.getId());
        map.put("send_method", bulkSend.getSendMethod());
        map.put("status", bulkSend.getStatus());
        map.put("total_recipients", bulkSend.getTotalRecipients());
        map.put("sent_count", bulkSend.getSentCount());
        map.put("failed_count", bulkSend.getFailedCount());
        map.put("progress_percent", bulkSend.getProgressPercent());
        map.put("message_content", bulkSend.getMessageContent());
        map.put("message_preview", truncate(bulkSend.getMessageContent(), 100));
        map.put("attachment_path", bulkSend.getAttachmentPath());
        map.put("attachment_type", bulkSend.getAttachmentType());
        map.put("attachment_size", bulkSend.getAttachmentSize());
        map.put("attachment_original_name", bulkSend.getAttachmentOriginalName());
        map.put("started_at", bulkSend.getStartedAt());
        map.put("completed_at", bulkSend.getCompletedAt());
        map.put("error_summary", bulkSend.getErrorSummary());
        map.put("created_at", bulkSend.getCreatedAt());
        map.put("updated_at", bulkSend.getUpdatedAt());
        map.put("client_id", bulkSend.getClient() != null ? bulkSend.getClient().getId() : null);
        map.put("user_id", bulkSend.getUser() != null ? bulkSend.getUser().getId() : null);
        map.put("user_name", bulkSend.getUser() != null ? bulkSend.getUser().getFullName() : null);
        map.put("assigned_agent_id", bulkSend.getAssignedAgent() != null ? bulkSend.getAssignedAgent().getId() : null);
        map.put("assigned_agent_name", bulkSend.getAssignedAgent() != null ? bulkSend.getAssignedAgent().getFullName() : null);
        return map;
    }

    private Map<String, Object> mapRecipientToResponse(BulkSendRecipient recipient) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", recipient.getId());
        map.put("phone", recipient.getPhone());
        map.put("recipient_name", recipient.getRecipientName());
        map.put("status", recipient.getStatus());
        map.put("sent_at", recipient.getSentAt());
        map.put("error_message", recipient.getErrorMessage());
        map.put("custom_variables", recipient.getCustomVariables());
        return map;
    }

    private Map<String, Object> mapRulesToResponse(BulkSendRule rules) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", rules.getId());
        map.put("max_daily_messages", rules.getMaxDailyMessages());
        map.put("min_delay_seconds", rules.getMinDelaySeconds());
        map.put("max_delay_seconds", rules.getMaxDelaySeconds());
        map.put("pause_after_count", rules.getPauseAfterCount());
        map.put("pause_duration_minutes", rules.getPauseDurationMinutes());
        map.put("send_hour_start", rules.getSendHourStart());
        map.put("send_hour_end", rules.getSendHourEnd());
        map.put("enabled", rules.getEnabled());
        return map;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    // --- Request DTOs ---

    public record RecipientResultRequest(
            Long recipientId,
            boolean success,
            String errorMessage
    ) {}

    public record FromBulkMessageRecipient(
            String phone,
            String name
    ) {}

    public record FromBulkMessageRequest(
            List<FromBulkMessageRecipient> recipients,
            Long assignedAgentId
    ) {}
}
