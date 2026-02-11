package com.digitalgroup.holape.domain.crm.service;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.repository.ClientRepository;
import com.digitalgroup.holape.domain.crm.entity.CrmInfo;
import com.digitalgroup.holape.domain.crm.entity.CrmInfoSetting;
import com.digitalgroup.holape.domain.crm.repository.CrmInfoRepository;
import com.digitalgroup.holape.domain.crm.repository.CrmInfoSettingRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.exception.BusinessException;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CRM Service
 * Handles custom CRM fields per client and user
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrmService {

    private final CrmInfoSettingRepository settingRepository;
    private final CrmInfoRepository crmInfoRepository;
    private final ClientRepository clientRepository;
    private final UserRepository userRepository;

    // ========== CRM Settings Management ==========

    public List<CrmInfoSetting> getSettingsByClient(Long clientId) {
        return settingRepository.findByClientIdOrderByColumnPositionAsc(clientId);
    }

    public List<CrmInfoSetting> getActiveSettingsByClient(Long clientId) {
        return settingRepository.findByClientIdAndStatusOrderByColumnPositionAsc(
                clientId, CrmInfoSetting.Status.ACTIVE);
    }

    public List<CrmInfoSetting> getVisibleSettingsByClient(Long clientId) {
        return settingRepository.findVisibleByClient(clientId);
    }

    public CrmInfoSetting findSettingById(Long id) {
        return settingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CrmInfoSetting", id));
    }

    @Transactional
    public CrmInfoSetting createSetting(Long clientId, String columnLabel,
                                        CrmInfoSetting.ColumnType columnType,
                                        Boolean columnVisible) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Client", clientId));

        // Check for duplicate label
        if (settingRepository.findByClientIdAndColumnLabel(clientId, columnLabel).isPresent()) {
            throw new BusinessException("Column label '" + columnLabel + "' already exists");
        }

        // Get next position
        Integer maxPosition = settingRepository.findMaxPositionByClient(clientId);
        int nextPosition = (maxPosition != null ? maxPosition : 0) + 1;

        CrmInfoSetting setting = new CrmInfoSetting();
        setting.setClient(client);
        setting.setColumnLabel(columnLabel);
        setting.setColumnType(columnType != null ? columnType : CrmInfoSetting.ColumnType.TEXT);
        setting.setColumnPosition(nextPosition);
        setting.setColumnVisible(columnVisible != null ? columnVisible : true);
        setting.setStatus(CrmInfoSetting.Status.ACTIVE);

        setting = settingRepository.save(setting);
        log.info("Created CRM setting '{}' for client {}", columnLabel, clientId);

        return setting;
    }

    @Transactional
    public CrmInfoSetting updateSetting(Long id, String columnLabel,
                                        CrmInfoSetting.ColumnType columnType,
                                        Boolean columnVisible,
                                        CrmInfoSetting.Status status) {
        CrmInfoSetting setting = findSettingById(id);

        if (columnLabel != null && !columnLabel.equals(setting.getColumnLabel())) {
            // Check for duplicate
            var existing = settingRepository.findByClientIdAndColumnLabel(
                    setting.getClient().getId(), columnLabel);
            if (existing.isPresent() && !existing.get().getId().equals(id)) {
                throw new BusinessException("Column label '" + columnLabel + "' already exists");
            }
            setting.setColumnLabel(columnLabel);
        }

        if (columnType != null) setting.setColumnType(columnType);
        if (columnVisible != null) setting.setColumnVisible(columnVisible);
        if (status != null) setting.setStatus(status);

        return settingRepository.save(setting);
    }

    @Transactional
    public void deleteSetting(Long id) {
        CrmInfoSetting setting = findSettingById(id);

        // Soft delete by setting status to INACTIVE
        setting.setStatus(CrmInfoSetting.Status.INACTIVE);
        settingRepository.save(setting);

        log.info("Deactivated CRM setting {}", id);
    }

    @Transactional
    public void reorderSettings(Long clientId, List<Long> settingIds) {
        int position = 1;
        for (Long settingId : settingIds) {
            CrmInfoSetting setting = findSettingById(settingId);
            if (!setting.getClient().getId().equals(clientId)) {
                throw new BusinessException("Setting does not belong to this client");
            }
            setting.setColumnPosition(position++);
            settingRepository.save(setting);
        }
    }

    // ========== CRM Info (User Values) Management ==========

    /**
     * @deprecated Use getVisibleCrmDataByUser(User) instead — reads from custom_fields
     */
    public List<CrmInfo> getCrmInfoByUser(Long userId) {
        return crmInfoRepository.findActiveByUser(userId);
    }

    /**
     * @deprecated Use getVisibleCrmDataByUser(User) instead — reads from custom_fields
     */
    public List<CrmInfo> getVisibleCrmInfoByUser(Long userId) {
        return crmInfoRepository.findVisibleByUser(userId);
    }

    /**
     * Get all CRM data for a user from custom_fields, using crm_info_settings as schema.
     * This replaces the old getCrmInfoMapByUser that read from the crm_infos table.
     */
    public Map<String, String> getCrmInfoMapByUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        return getCrmDataByUser(user);
    }

    /**
     * Get all CRM data for a user from custom_fields, keyed by setting label.
     */
    public Map<String, String> getCrmDataByUser(User user) {
        Long clientId = user.getClient().getId();
        List<CrmInfoSetting> settings = settingRepository
                .findByClientIdAndStatusOrderByColumnPositionAsc(clientId, CrmInfoSetting.Status.ACTIVE);

        Map<String, Object> cf = user.getCustomFields();
        if (cf == null) return Map.of();

        Map<String, String> result = new LinkedHashMap<>();
        for (CrmInfoSetting s : settings) {
            if (cf.containsKey(s.getColumnLabel())) {
                result.put(s.getColumnLabel(), String.valueOf(cf.get(s.getColumnLabel())));
            }
        }
        return result;
    }

    /**
     * Get visible CRM data for a user from custom_fields.
     * Uses crm_info_settings to determine visibility and ordering.
     */
    public Map<String, String> getVisibleCrmDataByUser(User user) {
        Long clientId = user.getClient().getId();
        List<CrmInfoSetting> settings = settingRepository
                .findByClientIdAndStatusOrderByColumnPositionAsc(clientId, CrmInfoSetting.Status.ACTIVE);

        Map<String, Object> cf = user.getCustomFields();
        if (cf == null) return Map.of();

        Map<String, String> result = new LinkedHashMap<>();
        for (CrmInfoSetting s : settings) {
            if (Boolean.TRUE.equals(s.getColumnVisible()) && cf.containsKey(s.getColumnLabel())) {
                result.put(s.getColumnLabel(), String.valueOf(cf.get(s.getColumnLabel())));
            }
        }
        return result;
    }

    /**
     * Set a CRM value for a user. Writes to custom_fields (unified storage).
     * Also maintains CrmInfo table for backward compatibility during transition.
     */
    @Transactional
    public CrmInfo setCrmInfo(Long userId, Long settingId, String value) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        CrmInfoSetting setting = findSettingById(settingId);

        // Verify setting belongs to user's client
        if (!setting.getClient().getId().equals(user.getClient().getId())) {
            throw new BusinessException("CRM setting does not belong to user's client");
        }

        CrmInfo crmInfo = crmInfoRepository.findByUserIdAndCrmInfoSettingId(userId, settingId)
                .orElse(new CrmInfo());

        crmInfo.setUser(user);
        crmInfo.setCrmInfoSetting(setting);
        crmInfo.setColumnValue(value);

        // Validate value type
        if (!crmInfo.isValueValid()) {
            throw new BusinessException("Value does not match expected type: " + setting.getColumnType());
        }

        // Also write to custom_fields (unified storage)
        Map<String, Object> cf = user.getCustomFields();
        if (cf == null) cf = new HashMap<>();
        cf.put(setting.getColumnLabel(), value);
        user.setCustomFields(cf);
        userRepository.save(user);

        return crmInfoRepository.save(crmInfo);
    }

    @Transactional
    public void setCrmInfoBatch(Long userId, Map<Long, String> settingValues) {
        for (Map.Entry<Long, String> entry : settingValues.entrySet()) {
            setCrmInfo(userId, entry.getKey(), entry.getValue());
        }
    }

    @Transactional
    public void setCrmInfoByLabel(Long userId, Map<String, String> labelValues) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        Long clientId = user.getClient().getId();

        for (Map.Entry<String, String> entry : labelValues.entrySet()) {
            CrmInfoSetting setting = settingRepository
                    .findByClientIdAndColumnLabel(clientId, entry.getKey())
                    .orElse(null);

            if (setting != null && setting.getStatus() == CrmInfoSetting.Status.ACTIVE) {
                setCrmInfo(userId, setting.getId(), entry.getValue());
            }
        }
    }

    /**
     * Get available data fields for template parameters
     * Returns standard fields + CRM fields
     */
    public List<Map<String, String>> getAvailableDataFields(Long clientId) {
        List<Map<String, String>> fields = new java.util.ArrayList<>();

        // Standard fields
        fields.add(Map.of("field", "first_name", "label", "Nombre"));
        fields.add(Map.of("field", "last_name", "label", "Apellido"));
        fields.add(Map.of("field", "phone", "label", "Teléfono"));
        fields.add(Map.of("field", "email", "label", "Email"));

        // CRM fields
        List<CrmInfoSetting> crmSettings = getActiveSettingsByClient(clientId);
        for (CrmInfoSetting setting : crmSettings) {
            fields.add(Map.of(
                    "field", "crm_" + setting.getId(),
                    "label", setting.getColumnLabel()
            ));
        }

        return fields;
    }

    /**
     * Resolve template parameter value for a user.
     * Reads CRM values from user.custom_fields (unified storage).
     */
    public String resolveFieldValue(User user, String fieldName) {
        if (fieldName == null) return "";

        // Standard fields
        return switch (fieldName) {
            case "first_name" -> user.getFirstName() != null ? user.getFirstName() : "";
            case "last_name" -> user.getLastName() != null ? user.getLastName() : "";
            case "phone" -> user.getPhone() != null ? user.getPhone() : "";
            case "email" -> user.getEmail() != null ? user.getEmail() : "";
            default -> {
                // CRM field (format: crm_123) — resolve setting ID to label, then look in custom_fields
                if (fieldName.startsWith("crm_")) {
                    try {
                        Long settingId = Long.parseLong(fieldName.substring(4));
                        CrmInfoSetting setting = settingRepository.findById(settingId).orElse(null);
                        if (setting != null && user.getCustomFields() != null) {
                            Object val = user.getCustomFields().get(setting.getColumnLabel());
                            yield val != null ? String.valueOf(val) : "";
                        }
                        yield "";
                    } catch (NumberFormatException e) {
                        yield "";
                    }
                }
                yield "";
            }
        };
    }
}
