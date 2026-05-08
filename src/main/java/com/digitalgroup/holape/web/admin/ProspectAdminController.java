package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.common.enums.UserRole;
import com.digitalgroup.holape.domain.importdata.entity.ImportMappingTemplate;
import com.digitalgroup.holape.domain.importdata.repository.ImportMappingTemplateRepository;
import com.digitalgroup.holape.domain.prospect.entity.Prospect;
import com.digitalgroup.holape.domain.prospect.service.ProspectService;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Prospect Admin Controller
 * Equivalent to Rails Admin::ProspectsController
 * Manages prospect/leads before they become users
 *
 * Aligned with Rails schema: prospects table has manager_id, name, phone, client_id, status, upgraded_to_user
 */
@Slf4j
@RestController
@RequestMapping("/app/prospects")
@RequiredArgsConstructor
public class ProspectAdminController {

    private final ProspectService prospectService;
    private final UserRepository userRepository;
    private final ImportMappingTemplateRepository templateRepository;

    /**
     * List prospects
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> index(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        Page<Prospect> prospectsPage;
        if (search != null && !search.isEmpty()) {
            prospectsPage = prospectService.search(
                    currentUser.getClientId(), search, pageable);
        } else if (status != null && !status.isEmpty()) {
            try {
                Status statusEnum = Status.valueOf(status.toUpperCase());
                prospectsPage = prospectService.findByClientAndStatus(
                        currentUser.getClientId(), statusEnum, pageable);
            } catch (IllegalArgumentException e) {
                prospectsPage = prospectService.findByClient(
                        currentUser.getClientId(), pageable);
            }
        } else {
            prospectsPage = prospectService.findByClient(
                    currentUser.getClientId(), pageable);
        }

        List<Map<String, Object>> data = prospectsPage.getContent().stream()
                .map(this::mapProspectToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "prospects", data,
                "total", prospectsPage.getTotalElements(),
                "page", page,
                "totalPages", prospectsPage.getTotalPages()
        ));
    }

    /**
     * Get prospect by ID
     */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> show(@PathVariable Long id) {
        Prospect prospect = prospectService.findById(id);
        return ResponseEntity.ok(mapProspectToResponse(prospect));
    }

    /**
     * Create new prospect
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody CreateProspectRequest request) {

        Prospect prospect = prospectService.createProspect(
                currentUser.getClientId(),
                request.phone(),
                request.name()
        );

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "prospect", mapProspectToResponse(prospect)
        ));
    }

    /**
     * Update prospect
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody UpdateProspectRequest request) {

        Status status = null;
        if (request.status() != null) {
            try {
                status = Status.valueOf(request.status().toUpperCase());
            } catch (IllegalArgumentException e) {
                // ignore invalid status
            }
        }

        Prospect prospect = prospectService.updateProspect(
                id,
                request.name(),
                status
        );

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "prospect", mapProspectToResponse(prospect)
        ));
    }

    /**
     * Delete prospect
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        prospectService.deleteProspect(id);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", "Prospect deleted successfully"
        ));
    }

    /**
     * Upgrade prospect to user
     */
    @PostMapping("/{id}/upgrade")
    public ResponseEntity<Map<String, Object>> upgradeToUser(
            @PathVariable Long id,
            @RequestBody(required = false) UpgradeToUserRequest request) {

        Long managerId = request != null ? request.managerId() : null;
        Long userId = prospectService.upgradeToUser(id, managerId);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "user_id", userId,
                "message", "Prospect upgraded to user successfully"
        ));
    }

    /**
     * Assign prospect to manager
     */
    @PostMapping("/{id}/assign")
    public ResponseEntity<Map<String, Object>> assign(
            @PathVariable Long id,
            @RequestBody AssignRequest request) {

        Prospect prospect = prospectService.assignToManager(id, request.managerId());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "prospect", mapProspectToResponse(prospect)
        ));
    }

    private Map<String, Object> mapProspectToResponse(Prospect prospect) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", prospect.getId());
        map.put("name", prospect.getName());
        map.put("phone", prospect.getPhone());
        map.put("client_id", prospect.getClientId());
        map.put("status", prospect.getStatus() != null ? prospect.getStatus().name().toLowerCase() : null);
        map.put("upgraded_to_user", prospect.getUpgradedToUser());
        map.put("created_at", prospect.getCreatedAt());
        map.put("updated_at", prospect.getUpdatedAt());

        if (prospect.getManager() != null) {
            map.put("manager_id", prospect.getManager().getId());
            map.put("manager_name", prospect.getManager().getFullName());
        }

        return map;
    }

    // ========== Conversión de Prospectos a CSV de Importación ==========

    /**
     * Genera uno o más CSVs en formato FOH / template custom a partir de asociaciones prospecto→usuario.
     * Cada grupo usa un template distinto → un CSV por grupo.
     *
     * POST /app/prospects/generate-import-csv
     * Body: { "groups": [{ "templateId": 3, "associations": [{ "prospectId": 1, "userId": 2 }] }] }
     * Response: { "files": [{ "filename": "...", "content": "base64..." }] }
     */
    @PostMapping("/generate-import-csv")
    @PreAuthorize("hasAnyRole('AGENT','MANAGER_LEVEL_1','MANAGER_LEVEL_2','MANAGER_LEVEL_3','MANAGER_LEVEL_4')")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> generateImportCsv(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody GenerateImportCsvRequest request) {

        Long clientId = currentUser.getClientId();
        List<Map<String, Object>> files = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();

        for (int gIdx = 0; gIdx < request.groups().size(); gIdx++) {
            ConversionGroup group = request.groups().get(gIdx);

            // Resolve template — only templateId == null uses hardcoded FOH.
            // Custom templates (including isFoh=true variants) honor their own headers/mapping.
            ImportMappingTemplate template = null;
            if (group.templateId() != null) {
                template = templateRepository.findById(group.templateId())
                        .filter(t -> t.getClient().getId().equals(clientId))
                        .orElse(null);
            }
            boolean useHardcodedFoh = template == null;

            StringBuilder csv = new StringBuilder();
            String headerLine = useHardcodedFoh ? FOH_HEADER : String.join(",", template.getHeaders());
            csv.append('﻿').append(headerLine).append("\r\n");

            for (AssociationItem assoc : group.associations()) {
                Optional<Prospect> prospectOpt = prospectService.findByIdAndClientId(assoc.prospectId(), clientId);
                if (prospectOpt.isEmpty()) {
                    skipped.add(skipEntry(assoc, "prospect_not_found_or_wrong_client"));
                    continue;
                }
                Prospect prospect = prospectOpt.get();

                Optional<User> userOpt = userRepository.findById(assoc.userId());
                if (userOpt.isEmpty()) {
                    skipped.add(skipEntry(assoc, "user_not_found"));
                    continue;
                }
                User user = userOpt.get();
                if (user.getRole() != UserRole.STANDARD) {
                    skipped.add(skipEntry(assoc, "user_not_standard"));
                    continue;
                }
                if (!clientId.equals(user.getClient().getId())) {
                    skipped.add(skipEntry(assoc, "user_wrong_client"));
                    continue;
                }
                if (userRepository.findByPhoneAndClientId(prospect.getPhone(), clientId).isPresent()) {
                    skipped.add(skipEntry(assoc, "phone_already_exists_as_user"));
                    continue;
                }

                String row = useHardcodedFoh
                        ? buildFohCsvRow(user, prospect.getPhone())
                        : buildTemplateCsvRow(user, prospect.getPhone(), template);
                csv.append(row).append("\r\n");
            }

            String templateName = template != null ? template.getName() : "FOH";
            String filename = "conversion_" + templateName.replaceAll("[^a-zA-Z0-9_-]", "_")
                    + "_g" + (gIdx + 1) + "_" + LocalDate.now() + ".csv";
            String base64 = Base64.getEncoder().encodeToString(
                    csv.toString().getBytes(StandardCharsets.UTF_8));

            files.add(Map.of("filename", filename, "content", base64));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("files", files);
        response.put("skipped", skipped);
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> skipEntry(AssociationItem assoc, String reason) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("prospectId", assoc.prospectId());
        entry.put("userId", assoc.userId());
        entry.put("reason", reason);
        return entry;
    }

    private static final String FOH_HEADER =
            "IDENTITY_CODE,TRATAMIENTO,APE_PAT,APE_MAT,NOMBRE1,NOMBRE2,DEPT_DOM," +
            "DIST_DOM,DIAS_MORA,DIA_VENC,SALDO_MORA,SALDO_CAPITAL,SALDO_TOTAL," +
            "LIST_TRA,ESTRATEG,TIP_CON1,PHONE1,TIP_CON2,PHONE2,TIP_CON3,PHONE3," +
            "TIP_CON4,PHONE_4,TIP_CON5,PHONE5,TIPO_CLIENTE,ULT_ACC,ULT_RESP," +
            "ULT_CONT,EST_PROM,TELF_GESTION,SCORE_COBRANZA,TIPO_PROD,FLAG_CLIENTES," +
            "OBSERVACION,FECHA_CARGA,HORA_CARGA,GRUPO_SCORE,MTO_PAGO_TOT_MES,ESTADO,ID,";

    private String buildFohCsvRow(User user, String prospectPhone) {
        String tratamiento = "";
        try {
            if (user.getManager() != null) tratamiento = nvl(user.getManager().getImportString());
        } catch (Exception ignored) {}

        String[] lastName  = splitTwo(user.getLastName());
        String[] firstName = splitTwo(user.getFirstName());

        String[] cols = {
            escapeCSV(nvl(user.getCodigo())),          // IDENTITY_CODE
            escapeCSV(tratamiento),                     // TRATAMIENTO
            escapeCSV(lastName[0]),                     // APE_PAT
            escapeCSV(lastName[1]),                     // APE_MAT
            escapeCSV(firstName[0]),                    // NOMBRE1
            escapeCSV(firstName[1]),                    // NOMBRE2
            "",                                         // DEPT_DOM
            escapeCSV(cf(user, "dist_dom")),            // DIST_DOM
            escapeCSV(cf(user, "dias_mora")),           // DIAS_MORA
            escapeCSV(cf(user, "dia_venc")),            // DIA_VENC
            escapeCSV(cf(user, "saldo_mora")),          // SALDO_MORA
            "",                                         // SALDO_CAPITAL
            escapeCSV(cf(user, "saldo_total")),         // SALDO_TOTAL
            escapeCSV(cf(user, "list_tra")),            // LIST_TRA
            "", "",                                     // ESTRATEG, TIP_CON1
            escapeCSV(nvl(prospectPhone)),              // PHONE1 ← sustitución clave
            "", "", "", "", "", "", "", "",             // TIP_CON2..PHONE5
            "",                                         // TIPO_CLIENTE
            escapeCSV(cf(user, "ult_acc")),             // ULT_ACC
            "", "", "", "", "",                         // ULT_RESP..SCORE_COBRANZA
            "", "", "", "", "", "", "", "",             // TIPO_PROD..ID
            ""                                          // trailing
        };
        return String.join(",", cols);
    }

    private String buildTemplateCsvRow(User user, String prospectPhone, ImportMappingTemplate template) {
        Map<String, String> mapping = template.getColumnMapping();
        List<String> cols = new ArrayList<>();
        for (String header : template.getHeaders()) {
            String field = mapping.getOrDefault(header, "");
            String value = resolveField(user, prospectPhone, field);
            cols.add(escapeCSV(value));
        }
        return String.join(",", cols);
    }

    /**
     * Resolve a single field value for the conversion CSV.
     * Vocabulary aligned with ImportService.createTempImportUser so any
     * column_mapping the Import wizard could produce resolves correctly here.
     *
     * Notable choices:
     *  - "phone" returns the PROSPECT's phone (the whole point of the conversion).
     *  - "email" returns "" so the Import auto-generates `<phone>@<client-domain>`
     *    from the new phone (avoids reusing the base user's email and breaking
     *    unique constraints).
     *  - The "+cf" suffix is stripped: it's an Import-side instruction to ALSO
     *    store the value in custom_fields; for output we just emit the value.
     */
    private String resolveField(User user, String prospectPhone, String field) {
        if (field == null || field.isEmpty()) return "";

        // Strip "+cf" suffix — it's an Import-side directive, irrelevant for output.
        if (field.endsWith("+cf") && !field.startsWith("custom_field:")) {
            field = field.substring(0, field.length() - 3);
        }

        return switch (field) {
            case "phone"         -> nvl(prospectPhone);
            case "phone_code"    -> "";
            case "codigo"        -> nvl(user.getCodigo());
            case "first_name"    -> splitTwo(user.getFirstName())[0];
            case "first_name_2"  -> splitTwo(user.getFirstName())[1];
            case "last_name"     -> splitTwo(user.getLastName())[0];
            case "last_name_2"   -> splitTwo(user.getLastName())[1];
            case "email"         -> "";
            case "role"          -> user.getRole() != null ? user.getRole().name().toLowerCase() : "";
            case "manager_email" -> user.getManager() != null ? nvl(user.getManager().getEmail()) : "";
            case "import_string" -> {
                try { yield user.getManager() != null ? nvl(user.getManager().getImportString()) : ""; }
                catch (Exception e) { yield ""; }
            }
            default -> {
                if (field.startsWith("custom_field:")) {
                    yield cf(user, field.substring("custom_field:".length()));
                }
                if (field.startsWith("crm_")) {
                    yield "";
                }
                yield "";
            }
        };
    }

    private String cf(User user, String key) {
        if (user.getCustomFields() == null) return "";
        Object val = user.getCustomFields().get(key);
        return val != null ? val.toString() : "";
    }

    private String nvl(String s) { return s != null ? s : ""; }

    private String[] splitTwo(String name) {
        if (name == null || name.isBlank()) return new String[]{"", ""};
        String[] parts = name.trim().split("\\s+", 2);
        return parts.length == 2 ? parts : new String[]{parts[0], ""};
    }

    private String escapeCSV(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ========== DTOs ==========

    public record CreateProspectRequest(
            String phone,
            String name
    ) {}

    public record UpdateProspectRequest(
            String name,
            String status
    ) {}

    public record UpgradeToUserRequest(Long managerId) {}

    public record AssignRequest(Long managerId) {}

    public record AssociationItem(Long prospectId, Long userId) {}
    public record ConversionGroup(Long templateId, List<AssociationItem> associations) {}
    public record GenerateImportCsvRequest(List<ConversionGroup> groups) {}
}
