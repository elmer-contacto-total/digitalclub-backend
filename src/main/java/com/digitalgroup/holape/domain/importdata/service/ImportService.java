package com.digitalgroup.holape.domain.importdata.service;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.repository.ClientRepository;
import com.digitalgroup.holape.domain.common.enums.ImportStatus;
import com.digitalgroup.holape.domain.common.enums.ImportType;
import com.digitalgroup.holape.domain.importdata.entity.Import;
import com.digitalgroup.holape.domain.importdata.entity.TempImportUser;
import com.digitalgroup.holape.domain.importdata.repository.ImportRepository;
import com.digitalgroup.holape.domain.importdata.repository.TempImportUserRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.exception.BusinessException;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.digitalgroup.holape.domain.audit.listener.AuditEntityListener;

/**
 * Import Service
 * Handles CSV imports for users and prospects with full validation,
 * encoding detection, and TempImportUser staging workflow.
 * Equivalent to Rails Import model logic (525+ lines)
 *
 * PARIDAD RAILS: Usa campos exactos de Rails schema
 * - user_id (no created_by_id)
 * - tot_records (no total_rows)
 * - progress (no processed_rows)
 * - errors_text (no error_details como JSONB)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    private final ImportRepository importRepository;
    private final TempImportUserRepository tempImportUserRepository;
    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /**
     * FOH Agent mapping - PARIDAD RAILS
     * Maps import identifiers to agent EMAILS (not phones)
     * Source: Rails Import.match_foh_agent (lines 17-44)
     */
    private static final Map<String, String> FOH_AGENT_MAPPING = Map.of(
            "MARCADOR BIG TICKET WSP - RVILLANUEVA", "rosy.villanueva@somosoh.pe",
            "MARCADOR BIG TICKET WSP - YRAMIREZ", "yuli.ramirez@somosoh.pe",
            "MARCADOR BIG TICKET WSP - JMONJE", "joselin.monje@somosoh.pe",
            "MARCADOR BIG TICKET WSP - MGRANDE", "maria.grande@somosoh.pe",
            "JAPAZA", "jackeline.apaza@somosoh.pe",
            "NTICLLASUCA", "nancy.ticllasuca@somosoh.pe",
            "YHUERTA", "yeymi.huerta@somosoh.pe",
            "NCONTRERAS", "nelly.contreras@somosoh.pe"
    );

    // Standard column mappings
    private static final Map<String, String> COLUMN_ALIASES = Map.ofEntries(
            Map.entry("telefono", "phone"),
            Map.entry("celular", "phone"),
            Map.entry("movil", "phone"),
            Map.entry("mobile", "phone"),
            Map.entry("nombre", "first_name"),
            Map.entry("nombres", "first_name"),
            Map.entry("name", "first_name"),
            Map.entry("apellido", "last_name"),
            Map.entry("apellidos", "last_name"),
            Map.entry("surname", "last_name"),
            Map.entry("correo", "email"),
            Map.entry("mail", "email"),
            Map.entry("codigo", "codigo"),
            Map.entry("code", "codigo"),
            Map.entry("rol", "role"),
            Map.entry("role", "role"),
            Map.entry("manager", "manager_email"),
            Map.entry("jefe", "manager_email"),
            Map.entry("supervisor", "manager_email"),
            Map.entry("phone_code", "phone_code"),
            Map.entry("codigo_pais", "phone_code"),
            Map.entry("country_code", "phone_code")
    );

    // Required fields for validation
    private static final Set<String> REQUIRED_FIELDS = Set.of("phone");

    // BOM bytes for encoding detection
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF16_LE_BOM = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF16_BE_BOM = {(byte) 0xFE, (byte) 0xFF};

    /**
     * Find import by ID
     */
    public Import findById(Long id) {
        return importRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Import", id));
    }

    /**
     * Find imports by client with pagination
     */
    public Page<Import> findByClient(Long clientId, Pageable pageable) {
        return importRepository.findByClientId(clientId, pageable);
    }

    /**
     * Create new import and trigger validation
     * PARIDAD RAILS: usa user_id, no created_by_id
     */
    @Transactional
    public Import createImport(Long clientId, Long userId, MultipartFile file,
                               String importType, Long assignToUserId) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Client", clientId));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        Import importEntity = new Import();
        importEntity.setClient(client);
        importEntity.setUser(user);
        importEntity.setImportType(ImportType.USERS);
        importEntity.setStatus(ImportStatus.STATUS_NEW);

        importEntity = importRepository.save(importEntity);

        // Store file content for async processing
        byte[] fileContent;
        try {
            fileContent = file.getBytes();
        } catch (IOException e) {
            log.error("Error reading file content", e);
            throw new BusinessException("Error reading file: " + e.getMessage());
        }

        // Trigger async validation
        validateImportAsync(importEntity.getId(), fileContent, assignToUserId);

        return importEntity;
    }

    /**
     * Create FOH-specific import
     */
    @Transactional
    public Import createFohImport(Long clientId, Long userId, MultipartFile file) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Client", clientId));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        Import importEntity = new Import();
        importEntity.setClient(client);
        importEntity.setUser(user);
        importEntity.setImportType(ImportType.USERS);
        importEntity.setStatus(ImportStatus.STATUS_NEW);

        importEntity = importRepository.save(importEntity);

        byte[] fileContent;
        try {
            fileContent = file.getBytes();
        } catch (IOException e) {
            log.error("Error reading file content", e);
            throw new BusinessException("Error reading file: " + e.getMessage());
        }

        // Trigger FOH-specific validation
        validateImportFohAsync(importEntity.getId(), fileContent);

        return importEntity;
    }

    /**
     * Async validation - parses CSV and creates TempImportUser records
     */
    @Async
    public void validateImportAsync(Long importId, byte[] fileContent, Long assignToUserId) {
        try {
            validateImport(importId, fileContent, assignToUserId);
        } catch (Exception e) {
            log.error("Error validating import {}", importId, e);
            markAsFailed(importId, "Validation error: " + e.getMessage());
        }
    }

    /**
     * Async FOH validation - parses CSV with FOH-specific logic
     */
    @Async
    public void validateImportFohAsync(Long importId, byte[] fileContent) {
        try {
            validateImportFoh(importId, fileContent);
        } catch (Exception e) {
            log.error("Error validating FOH import {}", importId, e);
            markAsFailed(importId, "FOH validation error: " + e.getMessage());
        }
    }

    /**
     * Main validation logic - creates TempImportUser records
     * PARIDAD RAILS: usa tot_records, progress, errors_text
     */
    @Transactional
    public void validateImport(Long importId, byte[] fileContent, Long assignToUserId) {
        Import importEntity = findById(importId);
        importEntity.setStatus(ImportStatus.STATUS_VALIDATING);
        importRepository.save(importEntity);

        // Detect encoding and read file
        String content = readAndEncodeFile(fileContent);
        List<String[]> rows = parseCsv(content);

        if (rows.isEmpty()) {
            throw new BusinessException("Empty file or invalid CSV format");
        }

        String[] headers = rows.get(0);
        Map<Integer, String> columnMapping = mapColumns(headers);
        importEntity.setTotRecords(rows.size() - 1);

        List<Map<String, Object>> errors = new ArrayList<>();
        int validCount = 0;
        int invalidCount = 0;

        // Process each row and create TempImportUser
        for (int i = 1; i < rows.size(); i++) {
            String[] values = rows.get(i);
            int rowNumber = i + 1;

            try {
                TempImportUser tempUser = createTempImportUser(
                        importEntity, columnMapping, values, rowNumber);

                // Validate the temp user
                List<String> validationErrors = validateTempUser(tempUser, importEntity.getClient().getId());

                // PARIDAD RAILS: La validez se determina por error_message
                // error_message IS NULL → válido, error_message IS NOT NULL → inválido
                if (validationErrors.isEmpty()) {
                    // tempUser.errorMessage permanece null → considerado válido
                    validCount++;
                } else {
                    tempUser.setErrorMessage(String.join("; ", validationErrors));
                    invalidCount++;

                    Map<String, Object> errorDetail = new HashMap<>();
                    errorDetail.put("row", rowNumber);
                    errorDetail.put("errors", validationErrors);
                    errorDetail.put("phone", tempUser.getPhone());
                    errors.add(errorDetail);
                }

                tempImportUserRepository.save(tempUser);
                importEntity.setProgress(importEntity.getProgress() + 1);

            } catch (Exception e) {
                invalidCount++;
                Map<String, Object> errorDetail = new HashMap<>();
                errorDetail.put("row", rowNumber);
                errorDetail.put("errors", List.of(e.getMessage()));
                errors.add(errorDetail);
            }

            // Save progress periodically
            if (i % 100 == 0) {
                importRepository.save(importEntity);
            }
        }

        // Store errors as JSON text
        importEntity.setErrorsText(serializeErrors(errors));
        importEntity.setStatus(ImportStatus.STATUS_VALID);
        importRepository.save(importEntity);

        log.info("Import {} validated: {} valid, {} invalid",
                importId, validCount, invalidCount);
    }

    /**
     * FOH-specific validation with agent mapping
     */
    @Transactional
    public void validateImportFoh(Long importId, byte[] fileContent) {
        Import importEntity = findById(importId);
        importEntity.setStatus(ImportStatus.STATUS_VALIDATING);
        importRepository.save(importEntity);

        String content = readAndEncodeFile(fileContent);
        List<String[]> rows = parseCsv(content);

        if (rows.isEmpty()) {
            throw new BusinessException("Empty file or invalid CSV format");
        }

        String[] headers = rows.get(0);
        Map<Integer, String> columnMapping = mapFohColumns(headers);
        importEntity.setTotRecords(rows.size() - 1);

        List<Map<String, Object>> errors = new ArrayList<>();
        int validCount = 0;
        int invalidCount = 0;

        for (int i = 1; i < rows.size(); i++) {
            String[] values = rows.get(i);
            int rowNumber = i + 1;

            try {
                TempImportUser tempUser = createFohTempImportUser(
                        importEntity, columnMapping, values, rowNumber);

                // Match FOH agent - PARIDAD RAILS: Retorna email directamente
                String agentEmail = matchFohAgent(tempUser, importEntity.getClient().getId());
                if (agentEmail != null && !agentEmail.isEmpty()) {
                    tempUser.setManagerEmail(agentEmail);
                }

                List<String> validationErrors = validateTempUser(tempUser, importEntity.getClient().getId());

                // PARIDAD RAILS: La validez se determina por error_message
                // error_message IS NULL → válido, error_message IS NOT NULL → inválido
                if (validationErrors.isEmpty()) {
                    // tempUser.errorMessage permanece null → considerado válido
                    validCount++;
                } else {
                    tempUser.setErrorMessage(String.join("; ", validationErrors));
                    invalidCount++;

                    Map<String, Object> errorDetail = new HashMap<>();
                    errorDetail.put("row", rowNumber);
                    errorDetail.put("errors", validationErrors);
                    errors.add(errorDetail);
                }

                tempImportUserRepository.save(tempUser);
                importEntity.setProgress(importEntity.getProgress() + 1);

            } catch (Exception e) {
                invalidCount++;
                Map<String, Object> errorDetail = new HashMap<>();
                errorDetail.put("row", rowNumber);
                errorDetail.put("errors", List.of(e.getMessage()));
                errors.add(errorDetail);
            }

            if (i % 100 == 0) {
                importRepository.save(importEntity);
            }
        }

        importEntity.setErrorsText(serializeErrors(errors));
        importEntity.setStatus(ImportStatus.STATUS_VALID);
        importRepository.save(importEntity);

        log.info("FOH Import {} validated: {} valid, {} invalid",
                importId, validCount, invalidCount);
    }

    /**
     * Process validated import - creates actual users from TempImportUser
     */
    @Async
    public void processImportAsync(Long importId) {
        try {
            processImport(importId);
        } catch (Exception e) {
            log.error("Error processing import {}", importId, e);
            markAsFailed(importId, "Processing error: " + e.getMessage());
        }
    }

    /**
     * Process validated TempImportUser records and create real users
     * PARIDAD RAILS: usa progress, tot_records, errors_text
     * PARIDAD RAILS: User.without_auditing (create_user_import_worker.rb línea 10)
     */
    @Transactional
    public void processImport(Long importId) {
        Import importEntity = findById(importId);

        if (importEntity.getStatus() != ImportStatus.STATUS_VALID) {
            throw new BusinessException("Import must be validated before processing");
        }

        importEntity.setStatus(ImportStatus.STATUS_PROCESSING);
        importEntity.setProgress(0);
        importRepository.save(importEntity);

        List<TempImportUser> validUsers = tempImportUserRepository.findValidByImport(importId);
        List<Map<String, Object>> errors = parseErrorsText(importEntity.getErrorsText());
        int successCount = 0;

        // PARIDAD RAILS: Deshabilitar auditoría durante imports (without_auditing block)
        try {
            AuditEntityListener.disableAuditing();

            for (TempImportUser tempUser : validUsers) {
                try {
                    createOrUpdateUser(importEntity, tempUser);
                    tempUser.setProcessed(true);
                    tempImportUserRepository.save(tempUser);
                    successCount++;
                } catch (Exception e) {
                    tempUser.addError("Creation failed: " + e.getMessage());
                    tempImportUserRepository.save(tempUser);

                    Map<String, Object> errorDetail = new HashMap<>();
                    // PARIDAD RAILS: Usar phoneOrder como indicador de fila
                    errorDetail.put("row", tempUser.getPhoneOrder());
                    errorDetail.put("phone", tempUser.getPhone());
                    errorDetail.put("error", e.getMessage());
                    errors.add(errorDetail);
                }

                importEntity.setProgress(importEntity.getProgress() + 1);

                if (importEntity.getProgress() % 50 == 0) {
                    importRepository.save(importEntity);
                }
            }
        } finally {
            AuditEntityListener.enableAuditing();
        }

        importEntity.setStatus(ImportStatus.STATUS_COMPLETED);
        importEntity.setErrorsText(serializeErrors(errors));
        importRepository.save(importEntity);

        log.info("Import {} completed: {} success, {} errors",
                importId, successCount, errors.size());
    }

    /**
     * Serialize errors list to JSON string for errors_text column
     */
    private String serializeErrors(List<Map<String, Object>> errors) {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(errors);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize errors", e);
            return errors.toString();
        }
    }

    /**
     * Parse errors_text JSON back to list
     */
    private List<Map<String, Object>> parseErrorsText(String errorsText) {
        if (errorsText == null || errorsText.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(errorsText, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse errors_text", e);
            return new ArrayList<>();
        }
    }

    /**
     * Read file with encoding detection
     */
    private String readAndEncodeFile(byte[] fileContent) {
        // Check for BOM
        Charset charset = detectEncoding(fileContent);
        int skipBytes = getBomLength(fileContent);

        byte[] contentWithoutBom = skipBytes > 0 ?
                Arrays.copyOfRange(fileContent, skipBytes, fileContent.length) : fileContent;

        String content = new String(contentWithoutBom, charset);

        // Clean up common issues
        content = content.replace("\r\n", "\n").replace("\r", "\n");

        return content;
    }

    /**
     * Detect file encoding from BOM or content analysis
     */
    private Charset detectEncoding(byte[] content) {
        if (content.length >= 3 && content[0] == UTF8_BOM[0] &&
                content[1] == UTF8_BOM[1] && content[2] == UTF8_BOM[2]) {
            return StandardCharsets.UTF_8;
        }

        if (content.length >= 2) {
            if (content[0] == UTF16_LE_BOM[0] && content[1] == UTF16_LE_BOM[1]) {
                return StandardCharsets.UTF_16LE;
            }
            if (content[0] == UTF16_BE_BOM[0] && content[1] == UTF16_BE_BOM[1]) {
                return StandardCharsets.UTF_16BE;
            }
        }

        // Try to detect by content analysis
        if (isValidUtf8(content)) {
            return StandardCharsets.UTF_8;
        }

        // Fallback to ISO-8859-1 (Latin1) which accepts all byte sequences
        return StandardCharsets.ISO_8859_1;
    }

    /**
     * Get BOM length to skip
     */
    private int getBomLength(byte[] content) {
        if (content.length >= 3 && content[0] == UTF8_BOM[0] &&
                content[1] == UTF8_BOM[1] && content[2] == UTF8_BOM[2]) {
            return 3;
        }
        if (content.length >= 2) {
            if ((content[0] == UTF16_LE_BOM[0] && content[1] == UTF16_LE_BOM[1]) ||
                    (content[0] == UTF16_BE_BOM[0] && content[1] == UTF16_BE_BOM[1])) {
                return 2;
            }
        }
        return 0;
    }

    /**
     * Check if content is valid UTF-8
     */
    private boolean isValidUtf8(byte[] content) {
        int i = 0;
        while (i < content.length) {
            int b = content[i] & 0xFF;
            if (b < 0x80) {
                i++;
            } else if ((b & 0xE0) == 0xC0) {
                if (i + 1 >= content.length || (content[i + 1] & 0xC0) != 0x80) return false;
                i += 2;
            } else if ((b & 0xF0) == 0xE0) {
                if (i + 2 >= content.length || (content[i + 1] & 0xC0) != 0x80 ||
                        (content[i + 2] & 0xC0) != 0x80) return false;
                i += 3;
            } else if ((b & 0xF8) == 0xF0) {
                if (i + 3 >= content.length || (content[i + 1] & 0xC0) != 0x80 ||
                        (content[i + 2] & 0xC0) != 0x80 || (content[i + 3] & 0xC0) != 0x80) return false;
                i += 4;
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * Parse CSV content with proper quote handling
     */
    private List<String[]> parseCsv(String content) {
        List<String[]> rows = new ArrayList<>();
        String[] lines = content.split("\n");

        // Detect delimiter
        char delimiter = detectDelimiter(lines[0]);

        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            rows.add(parseCsvLine(line, delimiter));
        }

        return rows;
    }

    /**
     * Detect CSV delimiter (comma, semicolon, or tab)
     */
    private char detectDelimiter(String headerLine) {
        int commas = countOccurrences(headerLine, ',');
        int semicolons = countOccurrences(headerLine, ';');
        int tabs = countOccurrences(headerLine, '\t');

        if (semicolons > commas && semicolons > tabs) return ';';
        if (tabs > commas && tabs > semicolons) return '\t';
        return ',';
    }

    private int countOccurrences(String str, char c) {
        int count = 0;
        for (char ch : str.toCharArray()) {
            if (ch == c) count++;
        }
        return count;
    }

    /**
     * Parse single CSV line with quote handling
     */
    private String[] parseCsvLine(String line, char delimiter) {
        List<String> values = new ArrayList<>();
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
            } else if (c == delimiter && !inQuotes) {
                values.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        values.add(current.toString().trim());

        return values.toArray(new String[0]);
    }

    /**
     * Map column headers to standard field names
     */
    private Map<Integer, String> mapColumns(String[] headers) {
        Map<Integer, String> mapping = new HashMap<>();

        for (int i = 0; i < headers.length; i++) {
            String header = normalizeColumnName(headers[i]);
            String mappedField = COLUMN_ALIASES.getOrDefault(header, header);
            mapping.put(i, mappedField);
        }

        return mapping;
    }

    /**
     * Map FOH-specific columns
     */
    private Map<Integer, String> mapFohColumns(String[] headers) {
        Map<Integer, String> mapping = new HashMap<>();

        for (int i = 0; i < headers.length; i++) {
            String header = normalizeColumnName(headers[i]);

            // FOH-specific mappings
            if (header.contains("agente") || header.contains("agent")) {
                mapping.put(i, "agent_name");
            } else if (header.contains("orden") || header.contains("order")) {
                mapping.put(i, "phone_order");
            } else {
                String mappedField = COLUMN_ALIASES.getOrDefault(header, header);
                mapping.put(i, mappedField);
            }
        }

        return mapping;
    }

    /**
     * Normalize column name for matching
     */
    private String normalizeColumnName(String name) {
        return name.toLowerCase()
                .trim()
                .replace(" ", "_")
                .replace("-", "_")
                .replaceAll("[^a-z0-9_]", "");
    }

    /**
     * Create TempImportUser from CSV row
     * PARIDAD RAILS: No existe campo rowNumber, se usa phoneOrder para el orden
     */
    private TempImportUser createTempImportUser(Import importEntity,
                                                 Map<Integer, String> columnMapping,
                                                 String[] values, int rowNumber) {
        TempImportUser tempUser = new TempImportUser();
        tempUser.setUserImport(importEntity);
        // PARIDAD RAILS: Usar phoneOrder para almacenar el número de fila
        tempUser.setPhoneOrder(rowNumber);
        tempUser.setProcessed(false);

        Map<String, Object> customFields = new HashMap<>();
        Map<String, String> crmFields = new HashMap<>();

        for (int i = 0; i < values.length && i < columnMapping.size(); i++) {
            String field = columnMapping.get(i);
            String value = cleanValue(values[i]);

            if (value == null || value.isEmpty()) continue;

            switch (field) {
                case "phone" -> tempUser.setPhone(normalizePhone(value));
                case "phone_code" -> tempUser.setPhoneCode(value);
                case "first_name" -> tempUser.setFirstName(value);
                case "last_name" -> tempUser.setLastName(value);
                case "email" -> tempUser.setEmail(value.toLowerCase());
                case "codigo" -> tempUser.setCodigo(value);
                case "role" -> tempUser.setRole(value);
                case "manager_email" -> tempUser.setManagerEmail(value.toLowerCase());
                default -> {
                    // Check if it's a CRM field (starts with crm_)
                    if (field.startsWith("crm_")) {
                        crmFields.put(field.substring(4), value);
                    } else {
                        customFields.put(field, value);
                    }
                }
            }
        }

        if (!customFields.isEmpty()) {
            tempUser.setCustomFields(customFields);
        }
        if (!crmFields.isEmpty()) {
            tempUser.setCrmFields(crmFields);
        }

        return tempUser;
    }

    /**
     * Create FOH-specific TempImportUser
     */
    private TempImportUser createFohTempImportUser(Import importEntity,
                                                    Map<Integer, String> columnMapping,
                                                    String[] values, int rowNumber) {
        TempImportUser tempUser = createTempImportUser(importEntity, columnMapping, values, rowNumber);

        // Extract phone order if present
        for (int i = 0; i < values.length && i < columnMapping.size(); i++) {
            String field = columnMapping.get(i);
            String value = cleanValue(values[i]);

            if ("phone_order".equals(field) && value != null) {
                try {
                    tempUser.setPhoneOrder(Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    // Ignore invalid phone order
                }
            }
        }

        return tempUser;
    }

    /**
     * Match FOH agent by name - PARIDAD RAILS
     * Returns agent EMAIL (not phone) to match Rails behavior
     * Source: Rails Import.match_foh_agent (lines 17-44)
     *
     * PARIDAD RAILS:
     * 1. Primero busca en el mapeo hardcodeado (case statement en Rails)
     * 2. Si no encuentra, busca por import_string en la base de datos
     * 3. Retorna EMAIL del agente (no teléfono)
     */
    private String matchFohAgent(TempImportUser tempUser, Long clientId) {
        Map<String, Object> customFields = tempUser.getCustomFields();
        if (customFields == null) return null;

        String agentName = (String) customFields.get("agent_name");
        if (agentName == null || agentName.isEmpty()) return null;

        // Normalize and match
        String trimmedName = agentName.trim();
        String normalizedName = trimmedName.toUpperCase();

        // 1. Direct match in hardcoded mapping (Rails case statement)
        if (FOH_AGENT_MAPPING.containsKey(trimmedName)) {
            return FOH_AGENT_MAPPING.get(trimmedName);
        }
        if (FOH_AGENT_MAPPING.containsKey(normalizedName)) {
            return FOH_AGENT_MAPPING.get(normalizedName);
        }

        // 2. Partial match in mapping
        for (Map.Entry<String, String> entry : FOH_AGENT_MAPPING.entrySet()) {
            if (normalizedName.contains(entry.getKey().toUpperCase()) ||
                    entry.getKey().toUpperCase().contains(normalizedName)) {
                return entry.getValue();
            }
        }

        // 3. PARIDAD RAILS: Fallback - buscar por import_string en la base de datos
        // Rails: found_user = User.find_by(import_string: trimmed_email)
        Optional<User> foundUser = userRepository.findByImportStringAndClientId(trimmedName, clientId);
        if (foundUser.isPresent()) {
            return foundUser.get().getEmail();
        }

        // No match found - return empty string like Rails
        return "";
    }

    /**
     * Validate TempImportUser
     */
    private List<String> validateTempUser(TempImportUser tempUser, Long clientId) {
        List<String> errors = new ArrayList<>();

        // Required field validation
        if (tempUser.getPhone() == null || tempUser.getPhone().isEmpty()) {
            errors.add("Phone is required");
        }

        // Phone format validation
        if (tempUser.getPhone() != null && !isValidPhone(tempUser.getPhone())) {
            errors.add("Invalid phone format: " + tempUser.getPhone());
        }

        // Email format validation
        if (tempUser.getEmail() != null && !tempUser.getEmail().isEmpty() &&
                !isValidEmail(tempUser.getEmail())) {
            errors.add("Invalid email format: " + tempUser.getEmail());
        }

        // Check for duplicate phone in database
        if (tempUser.getPhone() != null) {
            String normalizedPhone = tempUser.getNormalizedPhone();
            if (userRepository.findByPhoneAndClientId(normalizedPhone, clientId).isPresent()) {
                errors.add("User with phone " + tempUser.getPhone() + " already exists");
            }
        }

        // Check for duplicate in same import
        if (tempUser.getPhone() != null && tempUser.getUserImport() != null) {
            List<TempImportUser> duplicates = tempImportUserRepository.findByImportAndPhone(
                    tempUser.getUserImport().getId(), tempUser.getPhone());
            // Exclude self
            duplicates = duplicates.stream()
                    .filter(d -> !Objects.equals(d.getId(), tempUser.getId()))
                    .toList();
            if (!duplicates.isEmpty()) {
                // PARIDAD RAILS: Usar phoneOrder como indicador de fila
                errors.add("Duplicate phone in import: row " + duplicates.get(0).getPhoneOrder());
            }
        }

        return errors;
    }

    /**
     * Create or update user from TempImportUser
     */
    private void createOrUpdateUser(Import importEntity, TempImportUser tempUser) {
        String normalizedPhone = tempUser.getNormalizedPhone();

        // Check if user exists
        Optional<User> existingUser = userRepository.findByPhoneAndClientId(
                normalizedPhone, importEntity.getClient().getId());

        User user;
        if (existingUser.isPresent()) {
            user = existingUser.get();
            // Update existing user
            if (tempUser.getFirstName() != null) user.setFirstName(tempUser.getFirstName());
            if (tempUser.getLastName() != null) user.setLastName(tempUser.getLastName());
            if (tempUser.getEmail() != null) user.setEmail(tempUser.getEmail());
        } else {
            // Create new user
            user = new User();
            user.setClient(importEntity.getClient());
            user.setPhone(normalizedPhone);
            user.setFirstName(tempUser.getFirstName());
            user.setLastName(tempUser.getLastName());
            user.setEmail(tempUser.getEmail());
        }

        // Set manager if specified
        if (tempUser.getManagerEmail() != null && !tempUser.getManagerEmail().isEmpty()) {
            userRepository.findByEmailAndClientId(tempUser.getManagerEmail(), importEntity.getClient().getId())
                    .ifPresent(user::setManager);
        }

        // Set CRM fields if present
        if (tempUser.getCrmFields() != null && !tempUser.getCrmFields().isEmpty()) {
            // Store in customFields
            Map<String, Object> customFields = user.getCustomFields();
            if (customFields == null) {
                customFields = new HashMap<>();
            }
            customFields.putAll(tempUser.getCrmFields());
            user.setCustomFields(customFields);
        }

        userRepository.save(user);
    }

    /**
     * Clean and trim value
     */
    private String cleanValue(String value) {
        if (value == null) return null;
        value = value.trim();
        // Remove surrounding quotes
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }

    /**
     * Normalize phone number
     */
    private String normalizePhone(String phone) {
        if (phone == null) return null;
        // Remove all non-numeric characters
        String normalized = phone.replaceAll("[^0-9]", "");
        // Remove leading zeros
        while (normalized.startsWith("0")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /**
     * Validate phone format
     */
    private boolean isValidPhone(String phone) {
        if (phone == null || phone.isEmpty()) return false;
        String normalized = phone.replaceAll("[^0-9]", "");
        return normalized.length() >= 7 && normalized.length() <= 15;
    }

    /**
     * Validate email format
     */
    private boolean isValidEmail(String email) {
        if (email == null || email.isEmpty()) return false;
        Pattern pattern = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
        Matcher matcher = pattern.matcher(email);
        return matcher.matches();
    }

    /**
     * Cancel import
     * PARIDAD RAILS: No existe CANCELLED status, se usa STATUS_ERROR
     */
    @Transactional
    public Import cancelImport(Long id) {
        Import importEntity = findById(id);

        if (importEntity.getStatus() == ImportStatus.STATUS_COMPLETED) {
            throw new BusinessException("Cannot cancel completed import");
        }

        importEntity.setStatus(ImportStatus.STATUS_ERROR);
        importEntity.setErrorsText(serializeErrors(List.of(
            Map.of("message", "Import cancelled by user", "type", "cancelled")
        )));

        // Clean up temp import users
        tempImportUserRepository.deleteByImportId(id);

        return importRepository.save(importEntity);
    }

    /**
     * Mark import as failed
     */
    private void markAsFailed(Long importId, String errorMessage) {
        Import importEntity = importRepository.findById(importId).orElse(null);
        if (importEntity != null) {
            importEntity.setStatus(ImportStatus.STATUS_ERROR);

            List<Map<String, Object>> errors = parseErrorsText(importEntity.getErrorsText());
            Map<String, Object> error = new HashMap<>();
            error.put("message", errorMessage);
            error.put("type", "system_error");
            errors.add(error);
            importEntity.setErrorsText(serializeErrors(errors));

            importRepository.save(importEntity);
        }
    }

    /**
     * Get import progress/status
     * PARIDAD RAILS: usa tot_records, progress, calculateProgress()
     */
    public Map<String, Object> getImportProgress(Long importId) {
        Import importEntity = findById(importId);

        Map<String, Object> progress = new HashMap<>();
        progress.put("id", importEntity.getId());
        progress.put("status", importEntity.getStatus().name().toLowerCase());
        progress.put("tot_records", importEntity.getTotRecords());
        progress.put("progress", importEntity.getProgress());
        progress.put("progress_percent", importEntity.calculateProgress());

        // Calculate valid/invalid counts from TempImportUser
        if (importEntity.getStatus() == ImportStatus.STATUS_VALID) {
            progress.put("valid_count", tempImportUserRepository.countValidByImport(importId));
            progress.put("invalid_count", tempImportUserRepository.countInvalidByImport(importId));
        }

        return progress;
    }

    /**
     * Get validation errors for import
     */
    public List<TempImportUser> getValidationErrors(Long importId, Pageable pageable) {
        return tempImportUserRepository.findInvalidByImport(importId);
    }

    /**
     * Generate sample CSV content
     */
    public String generateSampleCsv(String importType) {
        StringBuilder csv = new StringBuilder();

        switch (importType) {
            case "user" -> {
                csv.append("phone,first_name,last_name,email,phone_code,manager_email,codigo\n");
                csv.append("987654321,Juan,Perez,juan@email.com,51,manager@email.com,USR001\n");
                csv.append("987654322,Maria,Garcia,maria@email.com,51,manager@email.com,USR002\n");
            }
            case "foh" -> {
                csv.append("phone,first_name,last_name,agent_name,phone_order\n");
                csv.append("987654321,Juan,Perez,ANDREA GARCIA,1\n");
                csv.append("987654322,Maria,Garcia,BRENDA GONZALEZ,2\n");
            }
            case "prospect" -> {
                csv.append("phone,first_name,last_name,email,company,notes\n");
                csv.append("987654321,Juan,Perez,juan@email.com,Empresa SA,Interesado en producto\n");
            }
            default -> {
                csv.append("phone,first_name,last_name,email\n");
                csv.append("987654321,Juan,Perez,juan@email.com\n");
            }
        }

        return csv.toString();
    }

    /**
     * Confirm and process validated import
     */
    @Transactional
    public void confirmImport(Long importId) {
        Import importEntity = findById(importId);

        if (importEntity.getStatus() != ImportStatus.STATUS_VALID) {
            throw new BusinessException("Import must be validated before confirmation");
        }

        long validCount = tempImportUserRepository.countValidByImport(importId);
        if (validCount == 0) {
            throw new BusinessException("No valid records to import");
        }

        // Trigger async processing
        processImportAsync(importId);
    }
}
