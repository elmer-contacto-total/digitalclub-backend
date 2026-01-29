package com.digitalgroup.holape.domain.client.service;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.entity.ClientSetting;
import com.digitalgroup.holape.domain.client.repository.ClientRepository;
import com.digitalgroup.holape.domain.client.repository.ClientSettingRepository;
import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client Service
 * Handles client (tenant) management
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientService {

    private final ClientRepository clientRepository;
    private final ClientSettingRepository clientSettingRepository;

    public Client findById(Long id) {
        return clientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Client", id));
    }

    public Page<Client> findAll(Pageable pageable) {
        return clientRepository.findAll(pageable);
    }

    /**
     * Creates a new client and initializes default settings
     * PARIDAD RAILS: Equivalente a after_commit :create_client_settings, on: :create
     * Also validates manager_levels_must_be_present before save
     */
    @Transactional
    public Client create(Client client) {
        // PARIDAD RAILS: validate :manager_levels_must_be_present (líneas 31-45)
        validateManagerLevels(client);

        Client savedClient = clientRepository.save(client);
        createDefaultSettings(savedClient);
        log.info("Created client {} with default settings", savedClient.getId());
        return savedClient;
    }

    /**
     * Validates that if exists_manager_level_X is true, the nomenclature must be present
     * PARIDAD RAILS: validate :manager_levels_must_be_present (client.rb líneas 31-45)
     */
    private void validateManagerLevels(Client client) {
        if (client.getClientStructure() == null) {
            return;
        }

        var structure = client.getClientStructure();
        java.util.List<String> errors = new java.util.ArrayList<>();

        if (Boolean.TRUE.equals(structure.getExistsManagerLevel1())
                && (structure.getManagerLevel1() == null || structure.getManagerLevel1().isBlank())) {
            errors.add("Nomenclatura de Gerente de Nivel 1 no puede estar en blanco");
        }
        if (Boolean.TRUE.equals(structure.getExistsManagerLevel2())
                && (structure.getManagerLevel2() == null || structure.getManagerLevel2().isBlank())) {
            errors.add("Nomenclatura de Gerente de Nivel 2 no puede estar en blanco");
        }
        if (Boolean.TRUE.equals(structure.getExistsManagerLevel3())
                && (structure.getManagerLevel3() == null || structure.getManagerLevel3().isBlank())) {
            errors.add("Nomenclatura de Gerente de Nivel 3 no puede estar en blanco");
        }

        if (!errors.isEmpty()) {
            throw new com.digitalgroup.holape.exception.BusinessException(
                    "Validación de estructura de cliente fallida: " + String.join(", ", errors));
        }
    }

    /**
     * Creates default client settings as per Rails callback create_client_settings
     * PARIDAD RAILS: Crea 4 settings por defecto al crear un cliente:
     * - time_for_ticket_autoclose: 12 (horas)
     * - templates_language: { language_type: 'multi', default_language: 'es' }
     * - available_categories: { categories: ['general', 'finance', 'events'] }
     * - create_user_from_prospect: false
     */
    @Transactional
    public void createDefaultSettings(Client client) {
        Long clientId = client.getId();

        // 1. time_for_ticket_autoclose = 12 hours
        setIntegerSettingIfNotExists(clientId, "time_for_ticket_autoclose", 12);

        // 2. templates_language = { language_type: 'multi', default_language: 'es' }
        Map<String, Object> templatesLanguage = new java.util.HashMap<>();
        templatesLanguage.put("language_type", "multi");
        templatesLanguage.put("default_language", "es");
        setHashSettingIfNotExists(clientId, "templates_language", templatesLanguage);

        // 3. available_categories = { categories: ['general', 'finance', 'events'] }
        Map<String, Object> availableCategories = new java.util.HashMap<>();
        availableCategories.put("categories", java.util.Arrays.asList("general", "finance", "events"));
        setHashSettingIfNotExists(clientId, "available_categories", availableCategories);

        // 4. create_user_from_prospect = false
        setBooleanSettingIfNotExists(clientId, "create_user_from_prospect", false);

        log.debug("Created default settings for client {}", clientId);
    }

    private void setIntegerSettingIfNotExists(Long clientId, String name, Integer value) {
        if (clientSettingRepository.findByClientIdAndName(clientId, name).isEmpty()) {
            setIntegerSetting(clientId, name, value);
        }
    }

    private void setBooleanSettingIfNotExists(Long clientId, String name, Boolean value) {
        if (clientSettingRepository.findByClientIdAndName(clientId, name).isEmpty()) {
            setBooleanSetting(clientId, name, value);
        }
    }

    private void setHashSettingIfNotExists(Long clientId, String name, Map<String, Object> value) {
        if (clientSettingRepository.findByClientIdAndName(clientId, name).isEmpty()) {
            setHashSetting(clientId, name, value);
        }
    }

    public List<Client> findAllActive() {
        return clientRepository.findByStatus(Status.ACTIVE);
    }

    public boolean isUserClient(Long clientId, Long userClientId) {
        return clientId != null && clientId.equals(userClientId);
    }

    @Transactional
    public Client updateClient(Long id, String name, Status status) {
        Client client = findById(id);

        if (name != null) client.setName(name);
        if (status != null) client.setStatus(status);

        return clientRepository.save(client);
    }

    /**
     * Update client with full entity (all fields)
     * PARIDAD RAILS: Admin::ClientsController#update
     */
    @Transactional
    public Client updateClientFull(Client client) {
        // Validate manager levels before saving
        validateManagerLevels(client);
        return clientRepository.save(client);
    }

    /**
     * Delete a client
     * PARIDAD RAILS: Admin::ClientsController#destroy
     */
    @Transactional
    public void deleteClient(Long id) {
        Client client = findById(id);
        clientRepository.delete(client);
        log.info("Deleted client {}", id);
    }

    // ========== Client Settings ==========

    public List<ClientSetting> getSettings(Long clientId) {
        return clientSettingRepository.findByClientId(clientId);
    }

    public Optional<ClientSetting> getSetting(Long clientId, String name) {
        return clientSettingRepository.findByClientIdAndName(clientId, name);
    }

    public String getClientSettingValue(Long clientId, String name) {
        return clientSettingRepository.findByClientIdAndName(clientId, name)
                .map(ClientSetting::getStringValue)
                .orElse(null);
    }

    public Integer getClientSettingIntValue(Long clientId, String name) {
        return clientSettingRepository.findByClientIdAndName(clientId, name)
                .map(ClientSetting::getIntegerValue)
                .orElse(null);
    }

    public Boolean getClientSettingBoolValue(Long clientId, String name) {
        return clientSettingRepository.findByClientIdAndName(clientId, name)
                .map(ClientSetting::getBooleanValue)
                .orElse(null);
    }

    @Transactional
    public ClientSetting setStringSetting(Long clientId, String name, String value) {
        Client client = findById(clientId);
        ClientSetting setting = clientSettingRepository.findByClientIdAndName(clientId, name)
                .orElseGet(() -> {
                    ClientSetting newSetting = new ClientSetting();
                    newSetting.setClient(client);
                    newSetting.setName(name);
                    newSetting.setLocalizedName(name);
                    newSetting.setDataType(0); // String
                    return newSetting;
                });

        setting.setStringValue(value);
        return clientSettingRepository.save(setting);
    }

    @Transactional
    public ClientSetting setIntegerSetting(Long clientId, String name, Integer value) {
        Client client = findById(clientId);
        ClientSetting setting = clientSettingRepository.findByClientIdAndName(clientId, name)
                .orElseGet(() -> {
                    ClientSetting newSetting = new ClientSetting();
                    newSetting.setClient(client);
                    newSetting.setName(name);
                    newSetting.setLocalizedName(name);
                    newSetting.setDataType(1); // Integer
                    return newSetting;
                });

        setting.setIntegerValue(value);
        return clientSettingRepository.save(setting);
    }

    @Transactional
    public ClientSetting setBooleanSetting(Long clientId, String name, Boolean value) {
        Client client = findById(clientId);
        ClientSetting setting = clientSettingRepository.findByClientIdAndName(clientId, name)
                .orElseGet(() -> {
                    ClientSetting newSetting = new ClientSetting();
                    newSetting.setClient(client);
                    newSetting.setName(name);
                    newSetting.setLocalizedName(name);
                    newSetting.setDataType(5); // Boolean
                    return newSetting;
                });

        setting.setBooleanValue(value);
        return clientSettingRepository.save(setting);
    }

    /**
     * Sets a hash (JSON) setting for a client
     * PARIDAD RAILS: Necesario para templates_language y available_categories
     */
    @Transactional
    public ClientSetting setHashSetting(Long clientId, String name, Map<String, Object> value) {
        Client client = findById(clientId);
        ClientSetting setting = clientSettingRepository.findByClientIdAndName(clientId, name)
                .orElseGet(() -> {
                    ClientSetting newSetting = new ClientSetting();
                    newSetting.setClient(client);
                    newSetting.setName(name);
                    newSetting.setLocalizedName(name);
                    newSetting.setDataType(4); // Hash/JSON
                    return newSetting;
                });

        setting.setHashValue(value);
        return clientSettingRepository.save(setting);
    }

    @Transactional
    public void updateSettings(Long clientId, Map<String, Object> settings) {
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof String) {
                setStringSetting(clientId, name, (String) value);
            } else if (value instanceof Integer) {
                setIntegerSetting(clientId, name, (Integer) value);
            } else if (value instanceof Boolean) {
                setBooleanSetting(clientId, name, (Boolean) value);
            } else if (value instanceof Number) {
                setIntegerSetting(clientId, name, ((Number) value).intValue());
            }
        }
    }

    // ========== WhatsApp Configuration ==========

    @Transactional
    public void updateWhatsAppConfig(Long clientId, String phoneNumberId,
                                     String businessAccountId, String accessToken,
                                     String webhookVerifyToken) {
        if (phoneNumberId != null) {
            setStringSetting(clientId, "whatsapp_phone_number_id", phoneNumberId);
        }
        if (businessAccountId != null) {
            setStringSetting(clientId, "whatsapp_business_account_id", businessAccountId);
        }
        if (accessToken != null) {
            setStringSetting(clientId, "whatsapp_access_token", accessToken);
        }
        if (webhookVerifyToken != null) {
            setStringSetting(clientId, "whatsapp_webhook_verify_token", webhookVerifyToken);
        }

        log.info("Updated WhatsApp config for client {}", clientId);
    }

    // ========== Convenience Methods ==========

    public Integer getTicketAutoCloseHours(Long clientId) {
        return getClientSettingIntValue(clientId, "time_for_ticket_autoclose");
    }

    public Integer getAlertTimeNotRespondedConversation(Long clientId) {
        return getClientSettingIntValue(clientId, "alert_time_not_responded_conversation");
    }

    public String getTimezone(Long clientId) {
        String tz = getClientSettingValue(clientId, "timezone");
        return tz != null ? tz : "America/Lima";
    }
}
