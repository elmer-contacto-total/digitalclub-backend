package com.digitalgroup.holape.domain.importdata.service;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.repository.ClientRepository;
import com.digitalgroup.holape.domain.common.enums.ImportStatus;
import com.digitalgroup.holape.domain.common.enums.ImportType;
import com.digitalgroup.holape.domain.common.enums.UserRole;
import com.digitalgroup.holape.domain.importdata.entity.Import;
import com.digitalgroup.holape.domain.importdata.entity.ImportMappingTemplate;
import com.digitalgroup.holape.domain.importdata.entity.TempImportUser;
import com.digitalgroup.holape.domain.importdata.repository.ImportMappingTemplateRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
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
import com.digitalgroup.holape.domain.crm.entity.CrmInfo;
import com.digitalgroup.holape.domain.crm.entity.CrmInfoSetting;
import com.digitalgroup.holape.domain.crm.repository.CrmInfoRepository;
import com.digitalgroup.holape.domain.crm.repository.CrmInfoSettingRepository;
import com.digitalgroup.holape.integration.storage.S3StorageService;

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
    private final ImportMappingTemplateRepository mappingTemplateRepository;
    private final TempImportUserRepository tempImportUserRepository;
    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final CrmInfoRepository crmInfoRepository;
    private final CrmInfoSettingRepository crmInfoSettingRepository;
    private final S3StorageService s3StorageService;
    private final PasswordEncoder passwordEncoder;

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
            Map.entry("country_code", "phone_code"),
            Map.entry("apellido_p", "last_name"),
            Map.entry("apellido_m", "last_name_2"),
            Map.entry("ejecutivo", "manager_email")
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
     * Find imports by client and user with pagination
     * PARIDAD RAILS: Non-admin users only see their own imports
     */
    public Page<Import> findByClientAndUser(Long clientId, Long userId, Pageable pageable) {
        return importRepository.findByClientIdAndUserId(clientId, userId, pageable);
    }

    /**
     * Create new import — stores file and returns headers + suggestions for interactive mapping.
     * Does NOT trigger validation — user must confirm mapping first via confirmMappingAndValidate().
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

        // Persist CSV file in S3 + inline base64 for re-reading at mapping confirmation
        persistCsvFile(importEntity, file, fileContent);

        // Status stays STATUS_NEW — no validation triggered
        return importEntity;
    }

    /**
     * Create FOH-specific import — stores file, does NOT trigger validation.
     * User must confirm mapping first via confirmMappingAndValidate().
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

        // Persist CSV file in S3 + inline base64
        persistCsvFile(importEntity, file, fileContent);

        // Status stays STATUS_NEW — no validation triggered
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
        ColumnMappingResult mappingResult = mapColumnsWithDetection(headers, importEntity.getClient().getId());
        Map<Integer, String> columnMapping = mappingResult.mapping();
        List<Map<String, Object>> unmatchedColumns = mappingResult.unmatchedColumns();
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

        // Mark both records in duplicate phone/email pairs
        markCrossDuplicates(importId);

        // Store errors and unmatched columns as JSON
        importEntity.setErrorsText(serializeValidationResult(errors, unmatchedColumns));
        importEntity.setStatus(ImportStatus.STATUS_VALID);
        importRepository.save(importEntity);

        log.info("Import {} validated: {} valid, {} invalid, {} unmatched columns",
                importId, validCount, invalidCount, unmatchedColumns.size());
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
        // Phase E: FOH also detects CRM columns via CrmInfoSetting
        ColumnMappingResult mappingResult = mapFohColumnsWithDetection(headers, importEntity.getClient().getId());
        Map<Integer, String> columnMapping = mappingResult.mapping();
        List<Map<String, Object>> unmatchedColumns = mappingResult.unmatchedColumns();
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

                // Agent linking is now handled generically by validateTempUser
                // (manager_email → email lookup → import_string fallback)

                List<String> validationErrors = validateTempUser(tempUser, importEntity.getClient().getId());

                if (validationErrors.isEmpty()) {
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

        // Mark both records in duplicate phone/email pairs
        markCrossDuplicates(importId);

        // Phase E: Store unmatched columns for interactive selection
        importEntity.setErrorsText(serializeValidationResult(errors, unmatchedColumns));
        importEntity.setStatus(ImportStatus.STATUS_VALID);
        importRepository.save(importEntity);

        log.info("FOH Import {} validated: {} valid, {} invalid, {} unmatched columns",
                importId, validCount, invalidCount, unmatchedColumns.size());
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

        // Auto-sync: create CrmInfoSettings for any new custom_field keys
        syncCrmInfoSettings(importEntity.getClient(), validUsers);

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
     * Parse errors_text JSON back to list.
     * Handles both old format (JSON array) and new format (JSON object with "errors" key).
     */
    private List<Map<String, Object>> parseErrorsText(String errorsText) {
        if (errorsText == null || errorsText.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            // New format: {"errors": [...], "unmatchedColumns": [...]}
            if (errorsText.trim().startsWith("{")) {
                Map<String, Object> wrapper = objectMapper.readValue(errorsText,
                        new TypeReference<Map<String, Object>>() {});
                Object errorsObj = wrapper.get("errors");
                if (errorsObj instanceof List<?> list) {
                    List<Map<String, Object>> result = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typedMap = (Map<String, Object>) map;
                            result.add(typedMap);
                        }
                    }
                    return result;
                }
                return new ArrayList<>();
            }
            // Old format: JSON array
            return objectMapper.readValue(errorsText, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse errors_text", e);
            return new ArrayList<>();
        }
    }

    /**
     * Serialize validation result including both errors and unmatched columns.
     */
    private String serializeValidationResult(List<Map<String, Object>> errors,
                                              List<Map<String, Object>> unmatchedColumns) {
        if ((errors == null || errors.isEmpty()) && (unmatchedColumns == null || unmatchedColumns.isEmpty())) {
            return null;
        }
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("errors", errors != null ? errors : List.of());
            if (unmatchedColumns != null && !unmatchedColumns.isEmpty()) {
                result.put("unmatchedColumns", unmatchedColumns);
            }
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize validation result", e);
            return serializeErrors(errors);
        }
    }

    /**
     * Extract unmatched columns from errorsText JSON.
     */
    public List<Map<String, Object>> getUnmatchedColumns(Long importId) {
        Import importEntity = findById(importId);
        String errorsText = importEntity.getErrorsText();
        if (errorsText == null || errorsText.isEmpty() || !errorsText.trim().startsWith("{")) {
            return List.of();
        }
        try {
            Map<String, Object> wrapper = objectMapper.readValue(errorsText,
                    new TypeReference<Map<String, Object>>() {});
            Object unmatchedObj = wrapper.get("unmatchedColumns");
            if (unmatchedObj instanceof List<?> list) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typedMap = (Map<String, Object>) map;
                        result.add(typedMap);
                    }
                }
                return result;
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse unmatched columns from errors_text", e);
        }
        return List.of();
    }

    /**
     * Accept unmatched columns: create CrmInfoSettings for selected columns
     * and re-mark those columns as CRM in the stored data.
     */
    @Transactional
    public void acceptUnmatchedColumns(Long importId, List<String> columnNames) {
        Import importEntity = findById(importId);
        Long clientId = importEntity.getClient().getId();

        for (String colName : columnNames) {
            // Check if CrmInfoSetting already exists
            Optional<CrmInfoSetting> existing = crmInfoSettingRepository
                    .findByClientIdAndColumnLabel(clientId, colName);

            if (existing.isEmpty()) {
                // Create new CrmInfoSetting
                Integer maxPos = crmInfoSettingRepository.findMaxPositionByClient(clientId);
                int nextPosition = (maxPos != null ? maxPos : 0) + 1;

                CrmInfoSetting setting = new CrmInfoSetting();
                setting.setClient(importEntity.getClient());
                setting.setColumnLabel(colName);
                setting.setColumnPosition(nextPosition);
                setting.setColumnType(CrmInfoSetting.ColumnType.TEXT);
                setting.setColumnVisible(true);
                setting.setStatus(CrmInfoSetting.Status.ACTIVE);
                crmInfoSettingRepository.save(setting);
            }
        }

        // Remove accepted columns from unmatched list in errorsText
        String errorsText = importEntity.getErrorsText();
        if (errorsText != null && errorsText.trim().startsWith("{")) {
            try {
                Map<String, Object> wrapper = objectMapper.readValue(errorsText,
                        new TypeReference<Map<String, Object>>() {});
                Set<String> acceptedSet = new HashSet<>(columnNames);

                Object unmatchedObj = wrapper.get("unmatchedColumns");
                if (unmatchedObj instanceof List<?> list) {
                    List<Map<String, Object>> remaining = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            Object name = map.get("name");
                            if (name != null && !acceptedSet.contains(name.toString())) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> typedMap = (Map<String, Object>) map;
                                remaining.add(typedMap);
                            }
                        }
                    }
                    if (remaining.isEmpty()) {
                        wrapper.remove("unmatchedColumns");
                    } else {
                        wrapper.put("unmatchedColumns", remaining);
                    }
                }

                importEntity.setErrorsText(objectMapper.writeValueAsString(wrapper));
                importRepository.save(importEntity);
            } catch (JsonProcessingException e) {
                log.warn("Failed to update errorsText after accepting columns", e);
            }
        }

        // Re-map accepted columns in TempImportUser records:
        // Move data from customFields "unmatched_X" to crmFields "X"
        List<TempImportUser> tempUsers = tempImportUserRepository.findByUserImportId(importId);
        for (TempImportUser tempUser : tempUsers) {
            Map<String, Object> customFields = tempUser.getCustomFields();
            if (customFields == null) continue;

            Map<String, String> crmFields = tempUser.getCrmFields();
            if (crmFields == null) {
                crmFields = new HashMap<>();
            }

            boolean changed = false;
            for (String colName : columnNames) {
                // The unmatched columns were stored with key = normalized column name
                // in customFields during createTempImportUser
                String unmatchedKey = normalizeColumnName(colName);
                if (customFields.containsKey(unmatchedKey)) {
                    crmFields.put(colName, String.valueOf(customFields.get(unmatchedKey)));
                    customFields.remove(unmatchedKey);
                    changed = true;
                }
                // Also check with "unmatched_" prefix in case stored that way
                if (customFields.containsKey("unmatched_" + colName)) {
                    crmFields.put(colName, String.valueOf(customFields.get("unmatched_" + colName)));
                    customFields.remove("unmatched_" + colName);
                    changed = true;
                }
            }

            if (changed) {
                tempUser.setCrmFields(crmFields);
                tempUser.setCustomFields(customFields.isEmpty() ? null : customFields);
                tempImportUserRepository.save(tempUser);
            }
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
     * Result of column mapping — separates known, CRM, and unmatched columns
     */
    private record ColumnMappingResult(
            Map<Integer, String> mapping,
            List<Map<String, Object>> unmatchedColumns
    ) {}

    /**
     * Map column headers to standard field names
     * PARIDAD RAILS: Detects CRM columns by matching against CrmInfoSetting labels
     * Phase D: Now tracks unmatched columns for interactive selection
     */
    private ColumnMappingResult mapColumnsWithDetection(String[] headers, Long clientId) {
        Map<Integer, String> mapping = new HashMap<>();
        List<Map<String, Object>> unmatchedColumns = new ArrayList<>();

        // Fetch CRM labels for this client (case-insensitive lookup)
        Set<String> crmLabels = new HashSet<>();
        if (clientId != null) {
            crmInfoSettingRepository.findByClientIdAndStatusOrderByColumnPositionAsc(
                    clientId, CrmInfoSetting.Status.ACTIVE)
                    .forEach(s -> crmLabels.add(s.getColumnLabel().toLowerCase().trim()));
        }

        // Collect all known standard field names
        Set<String> knownFields = new HashSet<>(COLUMN_ALIASES.values());

        for (int i = 0; i < headers.length; i++) {
            String headerOriginal = headers[i].trim();
            String header = normalizeColumnName(headerOriginal);
            String mappedField = COLUMN_ALIASES.get(header);

            if (mappedField != null) {
                // Known standard field
                mapping.put(i, mappedField);
            } else if (crmLabels.contains(headerOriginal.toLowerCase().trim())) {
                // CRM field detected by label match → suggest as custom_field
                mapping.put(i, "custom_field:" + headerOriginal);
            } else if (!header.isEmpty()) {
                // Unmatched column — not a known field and not an existing CRM setting
                mapping.put(i, "unmatched_" + headerOriginal);
                Map<String, Object> col = new HashMap<>();
                col.put("index", i);
                col.put("name", headerOriginal);
                unmatchedColumns.add(col);
            }
        }

        return new ColumnMappingResult(mapping, unmatchedColumns);
    }

    /**
     * Convenience wrapper that discards unmatched info (for backward compat)
     */
    private Map<Integer, String> mapColumns(String[] headers, Long clientId) {
        return mapColumnsWithDetection(headers, clientId).mapping();
    }

    /**
     * Map FOH-specific columns with CRM detection
     * Phase E: FOH also uses CrmInfoSetting for extra columns
     */
    private ColumnMappingResult mapFohColumnsWithDetection(String[] headers, Long clientId) {
        Map<Integer, String> mapping = new HashMap<>();
        List<Map<String, Object>> unmatchedColumns = new ArrayList<>();

        // FOH-specific known fields
        Set<String> fohKnownFields = new HashSet<>(COLUMN_ALIASES.values());
        fohKnownFields.add("manager_email");
        fohKnownFields.add("phone_order");

        // Fetch CRM labels for this client
        Set<String> crmLabels = new HashSet<>();
        if (clientId != null) {
            crmInfoSettingRepository.findByClientIdAndStatusOrderByColumnPositionAsc(
                    clientId, CrmInfoSetting.Status.ACTIVE)
                    .forEach(s -> crmLabels.add(s.getColumnLabel().toLowerCase().trim()));
        }

        for (int i = 0; i < headers.length; i++) {
            String headerOriginal = headers[i].trim();
            String header = normalizeColumnName(headerOriginal);

            // FOH-specific mappings — agent columns map to unified manager_email
            if (header.contains("agente") || header.contains("agent")) {
                mapping.put(i, "manager_email");
            } else if (header.contains("orden") || header.contains("order")) {
                mapping.put(i, "phone_order");
            } else {
                String mappedField = COLUMN_ALIASES.get(header);
                if (mappedField != null) {
                    mapping.put(i, mappedField);
                } else if (crmLabels.contains(headerOriginal.toLowerCase().trim())) {
                    // CRM field detected by label match → suggest as custom_field
                    mapping.put(i, "custom_field:" + headerOriginal);
                } else if (!header.isEmpty()) {
                    mapping.put(i, "unmatched_" + headerOriginal);
                    Map<String, Object> col = new HashMap<>();
                    col.put("index", i);
                    col.put("name", headerOriginal);
                    unmatchedColumns.add(col);
                }
            }
        }

        return new ColumnMappingResult(mapping, unmatchedColumns);
    }

    /**
     * Convenience wrapper for backward compat
     */
    private Map<Integer, String> mapFohColumns(String[] headers) {
        return mapFohColumnsWithDetection(headers, null).mapping();
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

        for (int i = 0; i < values.length; i++) {
            String field = columnMapping.get(i);
            if (field == null) continue;
            String value = cleanValue(values[i]);

            if (value == null || value.isEmpty()) continue;

            switch (field) {
                case "phone" -> tempUser.setPhone(normalizePhone(value));
                case "phone_code" -> tempUser.setPhoneCode(value);
                case "first_name" -> tempUser.setFirstName(value);
                case "last_name" -> tempUser.setLastName(value);
                case "last_name_2" -> {
                    // PARIDAD RAILS: "#{APELLIDO_P} #{APELLIDO_M}"
                    if (tempUser.getLastName() != null && !tempUser.getLastName().isEmpty()) {
                        tempUser.setLastName(tempUser.getLastName() + " " + value);
                    } else {
                        tempUser.setLastName(value);
                    }
                }
                case "first_name_2" -> {
                    // Segundo nombre: "#{NOMBRE1} #{NOMBRE2}"
                    if (tempUser.getFirstName() != null && !tempUser.getFirstName().isEmpty()) {
                        tempUser.setFirstName(tempUser.getFirstName() + " " + value);
                    } else {
                        tempUser.setFirstName(value);
                    }
                }
                case "email" -> tempUser.setEmail(value.toLowerCase());
                case "codigo" -> tempUser.setCodigo(value);
                case "role" -> tempUser.setRole(value);
                case "manager_email" -> tempUser.setManagerEmail(value.trim());
                default -> {
                    if (field.startsWith("custom_field:")) {
                        // Explicit custom field: use header name as key
                        String key = field.substring("custom_field:".length());
                        customFields.put(key, value);
                    } else if (field.startsWith("crm_")) {
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

        // PARIDAD RAILS: Default phoneCode to 51 (Peru) if not provided in CSV
        if (tempUser.getPhoneCode() == null || tempUser.getPhoneCode().isBlank()) {
            tempUser.setPhoneCode("51");
        }

        // PARIDAD RAILS: Auto-generate email if empty — "#{phone}@#{client.name.parameterize}.com"
        if ((tempUser.getEmail() == null || tempUser.getEmail().isEmpty()) && tempUser.getPhone() != null) {
            String clientSlug = parameterize(importEntity.getClient().getName());
            tempUser.setEmail(tempUser.getPhone() + "@" + clientSlug + ".com");
        }

        return tempUser;
    }

    /**
     * Create FOH-specific TempImportUser
     * PARIDAD RAILS: FOH overrides email to phone@foh.com, defaults role and phoneCode
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

        // PARIDAD RAILS: FOH email override — phone@foh.com
        if (tempUser.getPhone() != null) {
            tempUser.setEmail(tempUser.getPhone() + "@foh.com");
        }

        // PARIDAD RAILS: Default role to standard if empty
        if (tempUser.getRole() == null || tempUser.getRole().isBlank()) {
            tempUser.setRole("standard");
        }

        // PARIDAD RAILS: Default phoneCode to 51 (Peru) if empty
        if (tempUser.getPhoneCode() == null || tempUser.getPhoneCode().isBlank()) {
            tempUser.setPhoneCode("51");
        }

        return tempUser;
    }

    /**
     * Validate TempImportUser
     * PARIDAD RAILS: validate_temp_import_users — full validation including name, manager, email uniqueness
     */
    private List<String> validateTempUser(TempImportUser tempUser, Long clientId) {
        List<String> errors = new ArrayList<>();

        // Required field validation
        if (tempUser.getPhone() == null || tempUser.getPhone().isEmpty()) {
            errors.add("El teléfono no puede estar en blanco");
        }

        // B1: Require firstName and lastName
        if (tempUser.getFirstName() == null || tempUser.getFirstName().isBlank()) {
            errors.add("El nombre no puede estar en blanco");
        }
        if (tempUser.getLastName() == null || tempUser.getLastName().isBlank()) {
            errors.add("El apellido no puede estar en blanco");
        }

        // Phone format validation
        if (tempUser.getPhone() != null && !isValidPhone(tempUser.getPhone())) {
            errors.add("Formato de teléfono inválido: " + tempUser.getPhone());
        }

        // Email format validation
        if (tempUser.getEmail() != null && !tempUser.getEmail().isEmpty() &&
                !isValidEmail(tempUser.getEmail())) {
            errors.add("Formato de email inválido: " + tempUser.getEmail());
        }

        // Check for duplicate phone in database (existing user = update, not error)
        // NOTE: Removed — duplicate phone means UPDATE, not an error in Rails parity.
        // The createOrUpdateUser method handles updates.

        // Check for duplicate phone in same import
        if (tempUser.getPhone() != null && tempUser.getUserImport() != null) {
            List<TempImportUser> duplicates = tempImportUserRepository.findByImportAndPhone(
                    tempUser.getUserImport().getId(), tempUser.getPhone());
            duplicates = duplicates.stream()
                    .filter(d -> !Objects.equals(d.getId(), tempUser.getId()))
                    .toList();
            if (!duplicates.isEmpty()) {
                errors.add("Teléfono duplicado en importación: fila " + duplicates.get(0).getPhoneOrder());
            }
        }

        // B3: Check for duplicate email in same import
        if (tempUser.getEmail() != null && !tempUser.getEmail().isEmpty() && tempUser.getUserImport() != null) {
            List<TempImportUser> emailDuplicates = tempImportUserRepository.findByImportAndEmail(
                    tempUser.getUserImport().getId(), tempUser.getEmail());
            emailDuplicates = emailDuplicates.stream()
                    .filter(d -> !Objects.equals(d.getId(), tempUser.getId()))
                    .toList();
            if (!emailDuplicates.isEmpty()) {
                errors.add("Email duplicado en importación: " + tempUser.getEmail());
            }

            // Check email uniqueness in DB (only if not the same user being updated by phone)
            Optional<User> existingByEmail = userRepository.findByEmailAndClientId(tempUser.getEmail(), clientId);
            if (existingByEmail.isPresent()) {
                // Use the same lookup as createOrUpdateUser() to check if it's the same user
                String normalizedPhone = tempUser.getNormalizedPhone();
                Optional<User> existingByPhone = (normalizedPhone != null)
                        ? userRepository.findByPhoneAndClientId(normalizedPhone, clientId)
                        : Optional.empty();
                boolean isSameUser = existingByPhone.isPresent() &&
                        existingByPhone.get().getId().equals(existingByEmail.get().getId());
                if (!isSameUser) {
                    errors.add("El email ya está en uso por otro usuario: " + tempUser.getEmail());
                }
            }
        }

        // B2: Validate manager exists
        if (tempUser.getManagerEmail() != null && !tempUser.getManagerEmail().isBlank()) {
            String managerEmail = tempUser.getManagerEmail().trim();
            Optional<User> managerByEmail = userRepository.findByEmailAndClientId(managerEmail, clientId);
            if (managerByEmail.isEmpty()) {
                // Fallback: search by import_string
                Optional<User> managerByImportString = userRepository.findByImportStringAndClientId(managerEmail, clientId);
                if (managerByImportString.isPresent()) {
                    // Update tempUser's managerEmail to the real email
                    tempUser.setManagerEmail(managerByImportString.get().getEmail());
                } else {
                    errors.add("El agente asignado no existe: " + managerEmail);
                }
            }
        }

        return errors;
    }

    /**
     * Create or update user from TempImportUser
     * PARIDAD RAILS: create_user_import_worker.rb — aplica TODOS los campos del CSV
     */
    private void createOrUpdateUser(Import importEntity, TempImportUser tempUser) {
        String normalizedPhone = tempUser.getNormalizedPhone();
        Long clientId = importEntity.getClient().getId();
        String importType = importEntity.getImportType() != null
                ? importEntity.getImportType().name().toLowerCase() : "users";

        // Check if user exists
        Optional<User> existingUser = userRepository.findByPhoneAndClientId(normalizedPhone, clientId);

        User user;
        boolean isNewUser = existingUser.isEmpty();

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

            // PARIDAD RAILS: New users need encrypted_password (NOT NULL constraint)
            String randomPassword = UUID.randomUUID().toString().substring(0, 12);
            user.setEncryptedPassword(passwordEncoder.encode(randomPassword));
            user.setUuidToken(UUID.randomUUID().toString());
        }

        // PARIDAD RAILS: Apply role from CSV
        if (tempUser.getRole() != null && !tempUser.getRole().isBlank()) {
            user.setRole(UserRole.fromString(tempUser.getRole()));
        } else if (isNewUser) {
            user.setRole(UserRole.STANDARD);
        }

        // PARIDAD RAILS: Apply codigo from CSV
        if (tempUser.getCodigo() != null && !tempUser.getCodigo().isBlank()) {
            user.setCodigo(tempUser.getCodigo());
        }

        // PARIDAD RAILS: Apply importId
        user.setImportId(importEntity.getId());

        // Set manager if specified
        if (tempUser.getManagerEmail() != null && !tempUser.getManagerEmail().isEmpty()) {
            userRepository.findByEmailAndClientId(tempUser.getManagerEmail(), clientId)
                    .ifPresent(user::setManager);
        }

        // Unified storage: ALL extra fields (CRM + custom) → user.custom_fields (JSONB)
        // Both FOH and standard imports use the same storage now.
        {
            Map<String, Object> cf = new HashMap<>();

            if (tempUser.getCrmFields() != null && !tempUser.getCrmFields().isEmpty()) {
                cf.putAll(tempUser.getCrmFields());
            }
            if (tempUser.getCustomFields() != null && !tempUser.getCustomFields().isEmpty()) {
                cf.putAll(tempUser.getCustomFields());
            }

            user.setCustomFields(cf);
        }

        userRepository.save(user);
    }

    /**
     * Convert string to URL-friendly slug
     * PARIDAD RAILS: ActiveSupport parameterize — "Financiera Oh" → "financiera_oh"
     */
    private String parameterize(String input) {
        if (input == null || input.isBlank()) return "import";
        return input.trim().toLowerCase()
                .replaceAll("[áàäâ]", "a")
                .replaceAll("[éèëê]", "e")
                .replaceAll("[íìïî]", "i")
                .replaceAll("[óòöô]", "o")
                .replaceAll("[úùüû]", "u")
                .replaceAll("[ñ]", "n")
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
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
        Pattern pattern = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9._-]+\\.[A-Za-z]{2,}$");
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
     * Delete import and associated temp users
     * PARIDAD RAILS: @import.destroy!
     */
    @Transactional
    public void deleteImport(Long id) {
        Import importEntity = findById(id);
        tempImportUserRepository.deleteByImportId(id);
        importRepository.delete(importEntity);
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
     * PARIDAD RAILS: Headers en español, incluye columnas CRM del cliente, BOM UTF-8
     */
    public String generateSampleCsv(String importType, Long clientId) {
        StringBuilder csv = new StringBuilder();
        // BOM UTF-8 for Excel compatibility
        csv.append('\uFEFF');

        switch (importType) {
            case "user" -> {
                // Fetch CRM settings for this client
                List<CrmInfoSetting> crmSettings = clientId != null ?
                        crmInfoSettingRepository.findByClientIdAndStatusOrderByColumnPositionAsc(
                                clientId, CrmInfoSetting.Status.ACTIVE) :
                        List.of();

                // Headers — PARIDAD RAILS: Spanish column names
                csv.append("APELLIDO_P,APELLIDO_M,NOMBRE,CELULAR,CORREO,EJECUTIVO");
                for (CrmInfoSetting setting : crmSettings) {
                    csv.append(',').append(setting.getColumnLabel());
                }
                csv.append('\n');

                // Sample row
                csv.append("Perez,Gomez,Juan,987654321,juan@email.com,manager@email.com");
                for (int i = 0; i < crmSettings.size(); i++) {
                    csv.append(",Valor ").append(i + 1);
                }
                csv.append('\n');
            }
            case "foh" -> {
                // Phase E3: Dynamic FOH sample with CRM columns
                List<CrmInfoSetting> fohCrmSettings = clientId != null ?
                        crmInfoSettingRepository.findByClientIdAndStatusOrderByColumnPositionAsc(
                                clientId, CrmInfoSetting.Status.ACTIVE) :
                        List.of();

                csv.append("phone,first_name,last_name,agent_name,phone_order");
                for (CrmInfoSetting setting : fohCrmSettings) {
                    csv.append(',').append(setting.getColumnLabel());
                }
                csv.append('\n');

                csv.append("987654321,Juan,Perez,ANDREA GARCIA,1");
                for (int i = 0; i < fohCrmSettings.size(); i++) {
                    csv.append(",Valor ").append(i + 1);
                }
                csv.append('\n');

                csv.append("987654322,Maria,Garcia,BRENDA GONZALEZ,2");
                for (int i = 0; i < fohCrmSettings.size(); i++) {
                    csv.append(",Valor ").append(i + 1);
                }
                csv.append('\n');
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
     * Get TempImportUsers for a given import
     */
    public List<TempImportUser> getTempImportUsers(Long importId) {
        return tempImportUserRepository.findByUserImportId(importId);
    }

    /**
     * Get paged TempImportUsers with optional error filter and text search.
     * @param filter "all", "errors", or "valid"
     * @param search text to match against phone, firstName, lastName, email, managerEmail (null or blank = no search)
     */
    public Page<TempImportUser> getPagedTempUsers(Long importId, String filter, String search, Pageable pageable) {
        boolean hasSearch = search != null && !search.isBlank();

        if (hasSearch) {
            String q = "%" + search.trim().toLowerCase() + "%";
            return switch (filter) {
                case "errors" -> tempImportUserRepository.searchPagedInvalidByImport(importId, q, pageable);
                case "valid" -> tempImportUserRepository.searchPagedValidByImport(importId, q, pageable);
                default -> tempImportUserRepository.searchPagedByImport(importId, q, pageable);
            };
        }

        return switch (filter) {
            case "errors" -> tempImportUserRepository.findPagedInvalidByImport(importId, pageable);
            case "valid" -> tempImportUserRepository.findPagedValidByImport(importId, pageable);
            default -> tempImportUserRepository.findPagedByImport(importId, pageable);
        };
    }

    /**
     * Update editable fields of a TempImportUser (inline edit from preview).
     */
    @Transactional
    public TempImportUser updateTempUser(Long tempUserId, Map<String, String> fields) {
        TempImportUser tempUser = tempImportUserRepository.findById(tempUserId)
                .orElseThrow(() -> new ResourceNotFoundException("TempImportUser", tempUserId));

        if (fields.containsKey("firstName")) tempUser.setFirstName(fields.get("firstName"));
        if (fields.containsKey("lastName")) tempUser.setLastName(fields.get("lastName"));
        if (fields.containsKey("phone")) tempUser.setPhone(fields.get("phone"));
        if (fields.containsKey("phoneCode")) tempUser.setPhoneCode(fields.get("phoneCode"));
        if (fields.containsKey("email")) tempUser.setEmail(fields.get("email"));
        if (fields.containsKey("managerEmail")) tempUser.setManagerEmail(fields.get("managerEmail"));
        if (fields.containsKey("codigo")) tempUser.setCodigo(fields.get("codigo"));
        if (fields.containsKey("role")) tempUser.setRole(fields.get("role"));

        return tempImportUserRepository.save(tempUser);
    }

    /**
     * Delete a TempImportUser and update parent import's totRecords.
     */
    @Transactional
    public void deleteTempUser(Long importId, Long tempUserId) {
        TempImportUser tempUser = tempImportUserRepository.findById(tempUserId)
                .orElseThrow(() -> new ResourceNotFoundException("TempImportUser", tempUserId));

        tempImportUserRepository.delete(tempUser);

        Import importEntity = findById(importId);
        if (importEntity.getTotRecords() != null && importEntity.getTotRecords() > 0) {
            importEntity.setTotRecords(importEntity.getTotRecords() - 1);
            importRepository.save(importEntity);
        }
    }

    /**
     * Re-validate ALL TempImportUsers for an import.
     * Clears existing errors and runs validateTempUser() again for each record.
     * Resolves cross-record validation issues (e.g. duplicate phone/email after edit/delete).
     * Returns updated counts.
     */
    @Transactional
    public Map<String, Object> revalidateImport(Long importId) {
        Import importEntity = findById(importId);
        Long clientId = importEntity.getClient().getId();

        List<TempImportUser> allTempUsers = tempImportUserRepository.findByUserImportId(importId);

        int validCount = 0;
        int invalidCount = 0;

        for (TempImportUser tempUser : allTempUsers) {
            // Clear previous error
            tempUser.setErrorMessage(null);

            List<String> errors = validateTempUser(tempUser, clientId);

            if (errors.isEmpty()) {
                validCount++;
            } else {
                tempUser.setErrorMessage(String.join("; ", errors));
                invalidCount++;
            }

            tempImportUserRepository.save(tempUser);
        }

        log.info("Revalidated import {}: {} valid, {} invalid", importId, validCount, invalidCount);

        Map<String, Object> result = new HashMap<>();
        result.put("validCount", validCount);
        result.put("invalidCount", invalidCount);
        return result;
    }

    /**
     * Re-validate only affected TempImportUsers after an edit or delete.
     * For edits: revalidates the edited record + records sharing the same phone or email.
     * For deletes: uses deletedPhone/deletedEmail to find former duplicates that may now be valid.
     * Converts O(n) full revalidation into O(k) where k = affected records (typically 2-3).
     */
    @Transactional
    public Map<String, Object> revalidateAffected(Long importId, Long tempUserId,
                                                   String deletedPhone, String deletedEmail) {
        Import importEntity = findById(importId);
        Long clientId = importEntity.getClient().getId();

        Set<Long> idsToRevalidate = new HashSet<>();

        if (tempUserId != null) {
            // Edit case: revalidate the edited record + its phone/email neighbors
            tempImportUserRepository.findById(tempUserId).ifPresent(editedUser -> {
                idsToRevalidate.add(editedUser.getId());
                if (editedUser.getPhone() != null) {
                    tempImportUserRepository.findByImportAndPhone(importId, editedUser.getPhone())
                            .forEach(t -> idsToRevalidate.add(t.getId()));
                }
                if (editedUser.getEmail() != null && !editedUser.getEmail().isEmpty()) {
                    tempImportUserRepository.findByImportAndEmail(importId, editedUser.getEmail())
                            .forEach(t -> idsToRevalidate.add(t.getId()));
                }
            });
        }

        // Delete case (or additional neighbors): use deletedPhone/deletedEmail to find former duplicates
        if (deletedPhone != null && !deletedPhone.isEmpty()) {
            tempImportUserRepository.findByImportAndPhone(importId, deletedPhone)
                    .forEach(t -> idsToRevalidate.add(t.getId()));
        }
        if (deletedEmail != null && !deletedEmail.isEmpty()) {
            tempImportUserRepository.findByImportAndEmail(importId, deletedEmail)
                    .forEach(t -> idsToRevalidate.add(t.getId()));
        }

        // Revalidate only the affected records
        for (Long id : idsToRevalidate) {
            tempImportUserRepository.findById(id).ifPresent(t -> {
                t.setErrorMessage(null);
                List<String> errors = validateTempUser(t, clientId);
                if (!errors.isEmpty()) {
                    t.setErrorMessage(String.join("; ", errors));
                }
                tempImportUserRepository.save(t);
            });
        }

        // Return updated counts
        long validCount = tempImportUserRepository.countValidByImport(importId);
        long invalidCount = tempImportUserRepository.countInvalidByImport(importId);

        Map<String, Object> result = new HashMap<>();
        result.put("validCount", validCount);
        result.put("invalidCount", invalidCount);
        return result;
    }

    /**
     * Post-validation pass: mark ALL records in duplicate phone/email groups.
     * During sequential validation, the first record in a duplicate pair is saved as valid
     * because the second one doesn't exist yet. This method fixes that by checking all
     * records after they've all been created.
     */
    private void markCrossDuplicates(Long importId) {
        List<TempImportUser> allUsers = tempImportUserRepository.findByUserImportId(importId);

        // Group by phone
        Map<String, List<TempImportUser>> byPhone = new HashMap<>();
        for (TempImportUser user : allUsers) {
            if (user.getPhone() != null && !user.getPhone().isEmpty()) {
                byPhone.computeIfAbsent(user.getPhone(), k -> new ArrayList<>()).add(user);
            }
        }

        for (List<TempImportUser> group : byPhone.values()) {
            if (group.size() <= 1) continue;
            for (TempImportUser user : group) {
                if (user.getErrorMessage() == null || !user.getErrorMessage().contains("Teléfono duplicado")) {
                    String dupMsg = "Teléfono duplicado en importación";
                    String current = user.getErrorMessage();
                    user.setErrorMessage(current != null ? current + "; " + dupMsg : dupMsg);
                    tempImportUserRepository.save(user);
                }
            }
        }

        // Group by email (case-insensitive)
        Map<String, List<TempImportUser>> byEmail = new HashMap<>();
        for (TempImportUser user : allUsers) {
            if (user.getEmail() != null && !user.getEmail().isEmpty()) {
                byEmail.computeIfAbsent(user.getEmail().toLowerCase(), k -> new ArrayList<>()).add(user);
            }
        }

        for (List<TempImportUser> group : byEmail.values()) {
            if (group.size() <= 1) continue;
            for (TempImportUser user : group) {
                if (user.getErrorMessage() == null || !user.getErrorMessage().contains("Email duplicado en importación")) {
                    String dupMsg = "Email duplicado en importación: " + user.getEmail();
                    String current = user.getErrorMessage();
                    user.setErrorMessage(current != null ? current + "; " + dupMsg : dupMsg);
                    tempImportUserRepository.save(user);
                }
            }
        }
    }

    /**
     * Persist CSV file in S3 and store Shrine-compatible metadata
     * PARIDAD RAILS: import_file_data JSON column (Shrine format)
     */
    private void persistCsvFile(Import importEntity, MultipartFile file, byte[] fileContent) {
        try {
            String shrineJson;
            if (s3StorageService.isEnabled()) {
                String key = s3StorageService.uploadFile(
                        new ByteArrayInputStream(fileContent),
                        "imports",
                        file.getOriginalFilename(),
                        file.getContentType(),
                        fileContent.length);
                shrineJson = buildShrineJson(key, file.getOriginalFilename(),
                        fileContent.length, file.getContentType());
            } else {
                shrineJson = buildShrineJson(null, file.getOriginalFilename(),
                        fileContent.length, file.getContentType());
            }
            if (shrineJson != null) {
                // Only store base64 if S3 is not enabled (fallback for dev local)
                if (!s3StorageService.isEnabled()) {
                    try {
                        Map<String, Object> data = objectMapper.readValue(shrineJson,
                                new TypeReference<Map<String, Object>>() {});
                        data.put("csvBase64", Base64.getEncoder().encodeToString(fileContent));
                        shrineJson = objectMapper.writeValueAsString(data);
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to add csvBase64 to shrine JSON", e);
                    }
                }
                importEntity.setImportFileData(shrineJson);
                importRepository.save(importEntity);
            }
        } catch (Exception e) {
            log.warn("Failed to persist CSV file for import {}: {}", importEntity.getId(), e.getMessage());
        }
    }

    /**
     * Retrieve CSV file content — tries S3 first, falls back to base64.
     */
    public byte[] retrieveCsvContent(Import importEntity) {
        String fileData = importEntity.getImportFileData();
        if (fileData == null || fileData.isBlank()) {
            throw new BusinessException("No CSV data stored for this import");
        }
        try {
            Map<String, Object> data = objectMapper.readValue(fileData,
                    new TypeReference<Map<String, Object>>() {});

            // Try S3 first
            String s3Key = (String) data.get("id");
            if (s3StorageService.isEnabled() && s3Key != null && !s3Key.isBlank()) {
                return s3StorageService.downloadFile(s3Key);
            }

            // Fallback to base64 (for existing imports or dev without S3)
            String csvBase64 = (String) data.get("csvBase64");
            if (csvBase64 != null && !csvBase64.isBlank()) {
                return Base64.getDecoder().decode(csvBase64);
            }

            throw new BusinessException("No CSV content available (no S3 key and no csvBase64)");
        } catch (JsonProcessingException e) {
            throw new BusinessException("Failed to parse import file data: " + e.getMessage());
        }
    }

    /**
     * Build Shrine-compatible JSON for import_file_data column
     */
    private String buildShrineJson(String s3Key, String filename, long size, String contentType) {
        try {
            Map<String, Object> shrineData = new HashMap<>();
            if (s3Key != null) {
                shrineData.put("id", s3Key);
            }
            shrineData.put("storage", "store");
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("filename", filename);
            metadata.put("size", size);
            metadata.put("mime_type", contentType);
            shrineData.put("metadata", metadata);
            return objectMapper.writeValueAsString(shrineData);
        } catch (JsonProcessingException e) {
            log.warn("Failed to build Shrine JSON", e);
            return null;
        }
    }

    /**
     * Get CSV headers and auto-suggested mapping for the interactive mapping page.
     * Parses the stored CSV, reads headers, and applies COLUMN_ALIASES + FOH heuristics.
     * Also returns sample data (first 2 rows) for each column.
     */
    public Map<String, Object> getHeadersAndSuggestions(Long importId, boolean isFoh) {
        Import importEntity = findById(importId);
        byte[] fileContent = retrieveCsvContent(importEntity);
        String content = readAndEncodeFile(fileContent);
        List<String[]> rows = parseCsv(content);

        if (rows.isEmpty()) {
            throw new BusinessException("Empty file or invalid CSV format");
        }

        String[] headers = rows.get(0);
        Long clientId = importEntity.getClient().getId();

        // Build auto-suggestions using existing alias logic
        Map<Integer, String> autoMapping;
        if (isFoh) {
            autoMapping = mapFohColumnsWithDetection(headers, clientId).mapping();
        } else {
            autoMapping = mapColumnsWithDetection(headers, clientId).mapping();
        }

        // Build response: array of {index, header, suggestion, sampleData}
        List<Map<String, Object>> columns = new ArrayList<>();
        for (int i = 0; i < headers.length; i++) {
            Map<String, Object> col = new HashMap<>();
            col.put("index", i);
            col.put("header", headers[i].trim());

            // Determine suggestion — filter out unmatched_ prefixed as "no suggestion"
            String mapped = autoMapping.get(i);
            if (mapped != null && !mapped.startsWith("unmatched_")) {
                if (mapped.startsWith("custom_field:")) {
                    col.put("suggestion", "custom_field");
                } else {
                    col.put("suggestion", mapped);
                }
            }
            // If no suggestion, key absent → serialized as null by frontend

            // Sample data: values from first 2 data rows
            List<String> samples = new ArrayList<>();
            for (int r = 1; r <= Math.min(2, rows.size() - 1); r++) {
                String[] rowValues = rows.get(r);
                if (i < rowValues.length && rowValues[i] != null && !rowValues[i].trim().isEmpty()) {
                    samples.add(rowValues[i].trim());
                }
            }
            col.put("sampleData", samples);

            columns.add(col);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("importId", importId);
        result.put("columns", columns);
        result.put("totalRows", rows.size() - 1);
        return result;
    }

    /**
     * Confirm user-provided column mapping and trigger validation.
     * Called after the user reviews/adjusts column mapping on the mapping page.
     *
     * @param importId      the import ID
     * @param columnMapping user-confirmed mapping: key = column index (as string), value = field name
     * @param isFoh         whether this is a FOH import
     */
    @Transactional
    public void confirmMappingAndValidate(Long importId, Map<String, String> columnMapping, boolean isFoh) {
        Import importEntity = findById(importId);
        byte[] fileContent = retrieveCsvContent(importEntity);

        // Delete existing TempImportUsers if any (re-mapping case)
        tempImportUserRepository.deleteByImportId(importId);

        // Convert string keys to integer keys
        Map<Integer, String> intMapping = new HashMap<>();
        for (Map.Entry<String, String> entry : columnMapping.entrySet()) {
            String field = entry.getValue();
            if (field != null && !field.isBlank() && !"ignore".equals(field)) {
                intMapping.put(Integer.parseInt(entry.getKey()), field);
            }
        }

        if (isFoh) {
            validateImportFohWithMapping(importId, fileContent, intMapping);
        } else {
            validateImportWithMapping(importId, fileContent, intMapping);
        }
    }

    /**
     * Validate import using a user-provided column mapping (standard imports).
     */
    @Transactional
    public void validateImportWithMapping(Long importId, byte[] fileContent, Map<Integer, String> columnMapping) {
        Import importEntity = findById(importId);
        importEntity.setStatus(ImportStatus.STATUS_VALIDATING);
        importRepository.save(importEntity);

        String content = readAndEncodeFile(fileContent);
        List<String[]> rows = parseCsv(content);

        if (rows.isEmpty()) {
            throw new BusinessException("Empty file or invalid CSV format");
        }

        importEntity.setTotRecords(rows.size() - 1);

        List<Map<String, Object>> errors = new ArrayList<>();
        int validCount = 0;
        int invalidCount = 0;

        for (int i = 1; i < rows.size(); i++) {
            String[] values = rows.get(i);
            int rowNumber = i + 1;

            try {
                TempImportUser tempUser = createTempImportUser(
                        importEntity, columnMapping, values, rowNumber);

                List<String> validationErrors = validateTempUser(tempUser, importEntity.getClient().getId());

                if (validationErrors.isEmpty()) {
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

            if (i % 100 == 0) {
                importRepository.save(importEntity);
            }
        }

        // Mark both records in duplicate phone/email pairs
        markCrossDuplicates(importId);

        importEntity.setErrorsText(serializeValidationResult(errors, List.of()));
        importEntity.setStatus(ImportStatus.STATUS_VALID);
        importRepository.save(importEntity);

        log.info("Import {} validated with user mapping: {} valid, {} invalid", importId, validCount, invalidCount);
    }

    /**
     * Validate FOH import using a user-provided column mapping.
     */
    @Transactional
    public void validateImportFohWithMapping(Long importId, byte[] fileContent, Map<Integer, String> columnMapping) {
        Import importEntity = findById(importId);
        importEntity.setStatus(ImportStatus.STATUS_VALIDATING);
        importRepository.save(importEntity);

        String content = readAndEncodeFile(fileContent);
        List<String[]> rows = parseCsv(content);

        if (rows.isEmpty()) {
            throw new BusinessException("Empty file or invalid CSV format");
        }

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

                // Agent linking is now handled generically by validateTempUser

                List<String> validationErrors = validateTempUser(tempUser, importEntity.getClient().getId());

                if (validationErrors.isEmpty()) {
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

        // Mark both records in duplicate phone/email pairs
        markCrossDuplicates(importId);

        importEntity.setErrorsText(serializeValidationResult(errors, List.of()));
        importEntity.setStatus(ImportStatus.STATUS_VALID);
        importRepository.save(importEntity);

        log.info("FOH Import {} validated with user mapping: {} valid, {} invalid", importId, validCount, invalidCount);
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

    // ========== CRM Info Settings Auto-Sync ==========

    /**
     * Auto-create CrmInfoSettings for custom_field keys that don't have one yet.
     * Called before user creation so that new fields are immediately visible in CRM settings UI.
     * Handles concurrent imports by checking existence before creating (idempotent).
     */
    private void syncCrmInfoSettings(Client client, List<TempImportUser> tempUsers) {
        Long clientId = client.getId();

        // Collect all unique field keys from customFields and crmFields across all temp users
        Set<String> allFieldKeys = new LinkedHashSet<>();
        for (TempImportUser tempUser : tempUsers) {
            if (tempUser.getCustomFields() != null) {
                allFieldKeys.addAll(tempUser.getCustomFields().keySet());
            }
            if (tempUser.getCrmFields() != null) {
                allFieldKeys.addAll(tempUser.getCrmFields().keySet());
            }
        }

        if (allFieldKeys.isEmpty()) return;

        // Fetch existing CrmInfoSettings for this client (all, not just active)
        Set<String> existingLabels = new HashSet<>();
        crmInfoSettingRepository.findByClientIdOrderByColumnPositionAsc(clientId)
                .forEach(s -> existingLabels.add(s.getColumnLabel()));

        // Find keys that don't have a CrmInfoSetting yet
        List<String> newKeys = allFieldKeys.stream()
                .filter(key -> !existingLabels.contains(key))
                .toList();

        if (newKeys.isEmpty()) return;

        // Get current max position for this client
        Integer maxPos = crmInfoSettingRepository.findMaxPositionByClient(clientId);
        int nextPosition = (maxPos != null ? maxPos : 0) + 1;

        for (String key : newKeys) {
            // Double-check existence (handles concurrent imports)
            Optional<CrmInfoSetting> existing = crmInfoSettingRepository
                    .findByClientIdAndColumnLabel(clientId, key);

            if (existing.isEmpty()) {
                CrmInfoSetting setting = new CrmInfoSetting();
                setting.setClient(client);
                setting.setColumnLabel(key);
                setting.setColumnPosition(nextPosition++);
                setting.setColumnType(CrmInfoSetting.ColumnType.TEXT);
                setting.setColumnVisible(true);
                setting.setStatus(CrmInfoSetting.Status.ACTIVE);
                crmInfoSettingRepository.save(setting);
                log.info("Auto-created CrmInfoSetting '{}' for client {} (position {})",
                        key, clientId, setting.getColumnPosition());
            }
        }
    }

    // ========== Mapping Templates ==========

    /**
     * Get all mapping templates for a client
     */
    public List<ImportMappingTemplate> getMappingTemplates(Long clientId) {
        return mappingTemplateRepository.findByClientIdOrderByNameAsc(clientId);
    }

    /**
     * Save a new mapping template
     */
    @Transactional
    public ImportMappingTemplate saveMappingTemplate(Long clientId, String name, boolean isFoh,
                                                      Map<String, String> columnMapping, List<String> headers) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Client", clientId));

        // Check for duplicate name
        if (mappingTemplateRepository.findByClientIdAndName(clientId, name).isPresent()) {
            throw new BusinessException("Ya existe un template con el nombre '" + name + "'");
        }

        ImportMappingTemplate template = new ImportMappingTemplate();
        template.setClient(client);
        template.setName(name);
        template.setIsFoh(isFoh);
        template.setColumnMapping(columnMapping);
        template.setHeaders(headers);

        template = mappingTemplateRepository.save(template);
        log.info("Saved mapping template '{}' for client {}", name, clientId);
        return template;
    }

    /**
     * Delete a mapping template
     */
    @Transactional
    public void deleteMappingTemplate(Long templateId) {
        ImportMappingTemplate template = mappingTemplateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("ImportMappingTemplate", templateId));
        mappingTemplateRepository.delete(template);
        log.info("Deleted mapping template '{}' (id={})", template.getName(), templateId);
    }

    /**
     * Find a matching template for the given CSV headers.
     * Match is order-independent: template matches if the CSV has the same set of headers.
     */
    public ImportMappingTemplate findMatchingTemplate(Long clientId, List<String> csvHeaders, boolean isFoh) {
        List<ImportMappingTemplate> templates = mappingTemplateRepository.findByClientIdAndIsFoh(clientId, isFoh);

        Set<String> csvHeaderSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        csvHeaderSet.addAll(csvHeaders);

        for (ImportMappingTemplate template : templates) {
            if (template.getHeaders() == null) continue;
            Set<String> templateHeaderSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            templateHeaderSet.addAll(template.getHeaders());

            if (csvHeaderSet.equals(templateHeaderSet)) {
                return template;
            }
        }
        return null;
    }
}
