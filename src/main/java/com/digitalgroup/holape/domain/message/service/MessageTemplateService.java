package com.digitalgroup.holape.domain.message.service;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.repository.ClientRepository;
import com.digitalgroup.holape.domain.client.service.ClientService;
import com.digitalgroup.holape.domain.common.entity.Language;
import com.digitalgroup.holape.domain.common.enums.TemplateWhatsAppStatus;
import com.digitalgroup.holape.domain.common.repository.LanguageRepository;
import com.digitalgroup.holape.domain.message.entity.MessageTemplate;
import com.digitalgroup.holape.domain.message.repository.MessageTemplateRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.exception.BusinessException;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import com.digitalgroup.holape.integration.whatsapp.WhatsAppCloudApiClient;
import com.digitalgroup.holape.domain.client.entity.ClientSetting;
import com.digitalgroup.holape.domain.client.repository.ClientSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Message Template Service
 * Manages WhatsApp message templates
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageTemplateService {

    private final MessageTemplateRepository messageTemplateRepository;
    private final ClientRepository clientRepository;
    private final ClientSettingRepository clientSettingRepository;
    private final UserRepository userRepository;
    private final LanguageRepository languageRepository;
    private final WhatsAppCloudApiClient whatsAppCloudApiClient;

    public MessageTemplate findById(Long id) {
        return messageTemplateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MessageTemplate", id));
    }

    public Page<MessageTemplate> findByClient(Long clientId, Pageable pageable) {
        return messageTemplateRepository.findByClientId(clientId, pageable);
    }

    public Page<MessageTemplate> searchByClient(Long clientId, String search, Pageable pageable) {
        return messageTemplateRepository.findByClientIdAndNameContainingIgnoreCase(clientId, search, pageable);
    }

    public Page<MessageTemplate> findByClientAndStatus(Long clientId, String status, Pageable pageable) {
        try {
            TemplateWhatsAppStatus templateStatus = TemplateWhatsAppStatus.valueOf(status.toUpperCase());
            return messageTemplateRepository.findByClientIdAndTemplateWhatsappStatus(clientId, templateStatus, pageable);
        } catch (IllegalArgumentException e) {
            return messageTemplateRepository.findByClientId(clientId, pageable);
        }
    }

    public MessageTemplate findByNameAndClient(String name, Long clientId) {
        return messageTemplateRepository.findByNameAndClientId(name, clientId)
                .orElseThrow(() -> new ResourceNotFoundException("MessageTemplate", "name: " + name));
    }

    @Transactional
    public MessageTemplate createTemplate(Long clientId, Long userId, String name, String languageCode,
                                          Integer category, Integer headerMediaType, String headerContent,
                                          String bodyContent, String footerContent, Integer totButtons) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Client", clientId));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        // Check if template name already exists
        if (messageTemplateRepository.findByNameAndClientId(name, clientId).isPresent()) {
            throw new BusinessException("Template with name '" + name + "' already exists");
        }

        // Get language entity
        Language language = languageRepository.findByLanguageCode(languageCode != null ? languageCode : "es")
                .orElseGet(() -> languageRepository.findById(1L).orElse(null));

        if (language == null) {
            throw new BusinessException("Language not found");
        }

        MessageTemplate template = MessageTemplate.builder()
                .client(client)
                .user(user)
                .name(name)
                .language(language)
                .category(category != null ? category : 0)
                .headerMediaType(headerMediaType != null ? headerMediaType : 0)
                .headerContent(headerContent)
                .bodyContent(bodyContent)
                .footerContent(footerContent)
                .totButtons(totButtons != null ? totButtons : 0)
                .templateWhatsappStatus(TemplateWhatsAppStatus.DRAFT)
                .build();

        return messageTemplateRepository.save(template);
    }

    @Transactional
    public MessageTemplate updateTemplate(Long id, String headerContent, String bodyContent,
                                          String footerContent, Integer totButtons) {
        MessageTemplate template = findById(id);

        // Only allow updates for draft or rejected templates
        if (template.getTemplateWhatsappStatus() == TemplateWhatsAppStatus.APPROVED) {
            throw new BusinessException("Cannot update approved template. Create a new version instead.");
        }

        if (headerContent != null) template.setHeaderContent(headerContent);
        if (bodyContent != null) template.setBodyContent(bodyContent);
        if (footerContent != null) template.setFooterContent(footerContent);
        if (totButtons != null) template.setTotButtons(totButtons);

        // Reset status to draft if it was rejected
        if (template.getTemplateWhatsappStatus() == TemplateWhatsAppStatus.REJECTED) {
            template.setTemplateWhatsappStatus(TemplateWhatsAppStatus.DRAFT);
        }

        return messageTemplateRepository.save(template);
    }

    @Transactional
    public MessageTemplate updateTemplateParams(Long templateId, List<ParamUpdate> paramUpdates) {
        MessageTemplate template = findById(templateId);

        if (template.getParams() == null || template.getParams().isEmpty()) {
            throw new BusinessException("Template has no parameters to update");
        }

        for (ParamUpdate update : paramUpdates) {
            template.getParams().stream()
                    .filter(p -> p.getId().equals(update.id()))
                    .findFirst()
                    .ifPresent(param -> {
                        param.setDataField(update.dataField());
                        param.setDefaultValue(update.defaultValue());
                    });
        }

        return messageTemplateRepository.save(template);
    }

    public record ParamUpdate(Long id, String dataField, String defaultValue) {}

    @Transactional
    public void deleteTemplate(Long id) {
        MessageTemplate template = findById(id);
        messageTemplateRepository.delete(template);
        log.info("Deleted message template {}", id);
    }

    @Transactional
    public MessageTemplate submitForApproval(Long id) {
        MessageTemplate template = findById(id);

        if (template.getTemplateWhatsappStatus() != TemplateWhatsAppStatus.DRAFT &&
            template.getTemplateWhatsappStatus() != TemplateWhatsAppStatus.REJECTED) {
            throw new BusinessException("Only draft or rejected templates can be submitted for approval");
        }

        // Get client WhatsApp settings
        String accessToken = getClientSettingValueWithFallback(template.getClient().getId(), "whatsapp_access_token", "whatsapp_api_token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new BusinessException("WhatsApp access token not configured");
        }

        try {
            // Submit to WhatsApp Cloud API (placeholder)
            template.setTemplateWhatsappStatus(TemplateWhatsAppStatus.PENDING_APPROVAL);
            template = messageTemplateRepository.save(template);

            log.info("Submitted template {} for WhatsApp approval", id);

        } catch (Exception e) {
            log.error("Failed to submit template {} for approval", id, e);
            throw new BusinessException("Failed to submit template: " + e.getMessage());
        }

        return template;
    }

    /**
     * Sync templates with WhatsApp Cloud API
     * Equivalent to Rails: MessageTemplate.sync_with_cloud_api
     */
    @Transactional
    public int syncWithCloudApi(Long clientId) {
        String accessToken = getClientSettingValueWithFallback(clientId, "whatsapp_access_token", "whatsapp_api_token");
        String businessAccountId = getClientSettingValueWithFallback(clientId, "whatsapp_business_account_id", "whatsapp_account_id");

        if (accessToken == null || accessToken.isBlank() ||
            businessAccountId == null || businessAccountId.isBlank()) {
            throw new BusinessException("WhatsApp credentials not configured");
        }

        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Client", clientId));

        try {
            // Get templates from WhatsApp Cloud API
            List<java.util.Map<String, Object>> cloudTemplates = whatsAppCloudApiClient.getAllTemplates(client).block();

            // Get default user (first admin) for creating templates
            User defaultUser = userRepository.findFirstByClient_IdOrderByIdAsc(clientId)
                    .orElse(null);

            if (defaultUser == null) {
                throw new BusinessException("No users found for client");
            }

            Language defaultLanguage = languageRepository.findByLanguageCode("es")
                    .orElseGet(() -> languageRepository.findById(1L).orElse(null));

            int syncedCount = 0;
            if (cloudTemplates != null) {
                for (java.util.Map<String, Object> cloudTemplate : cloudTemplates) {
                    String name = (String) cloudTemplate.getOrDefault("name", "");
                    String status = (String) cloudTemplate.getOrDefault("status", "DRAFT");

                    // Find or create local template
                    MessageTemplate localTemplate = messageTemplateRepository
                            .findByNameAndClientId(name, clientId)
                            .orElse(null);

                    if (localTemplate != null) {
                        // Update existing template status
                        localTemplate.setTemplateWhatsappStatus(mapWhatsAppStatus(status));
                        messageTemplateRepository.save(localTemplate);
                        syncedCount++;
                    } else {
                        // Create new template from WhatsApp
                        MessageTemplate newTemplate = MessageTemplate.builder()
                                .client(client)
                                .user(defaultUser)
                                .name(name)
                                .language(defaultLanguage)
                                .templateWhatsappStatus(mapWhatsAppStatus(status))
                                .category(mapCategory((String) cloudTemplate.getOrDefault("category", "UTILITY")))
                                .build();

                        // Extract components if present
                        @SuppressWarnings("unchecked")
                        java.util.List<java.util.Map<String, Object>> components =
                                (java.util.List<java.util.Map<String, Object>>) cloudTemplate.get("components");
                        if (components != null) {
                            for (java.util.Map<String, Object> component : components) {
                                String type = (String) component.getOrDefault("type", "");
                                switch (type) {
                                    case "HEADER" -> newTemplate.setHeaderContent(
                                            (String) component.getOrDefault("text", ""));
                                    case "BODY" -> newTemplate.setBodyContent(
                                            (String) component.getOrDefault("text", ""));
                                    case "FOOTER" -> newTemplate.setFooterContent(
                                            (String) component.getOrDefault("text", ""));
                                }
                            }
                        }

                        messageTemplateRepository.save(newTemplate);
                        syncedCount++;
                    }
                }
            }

            log.info("Synced {} templates for client {}", syncedCount, clientId);
            return syncedCount;

        } catch (Exception e) {
            log.error("Failed to sync templates for client {}", clientId, e);
            throw new BusinessException("Failed to sync templates: " + e.getMessage());
        }
    }

    private TemplateWhatsAppStatus mapWhatsAppStatus(String whatsappStatus) {
        return switch (whatsappStatus.toUpperCase()) {
            case "APPROVED" -> TemplateWhatsAppStatus.APPROVED;
            case "PENDING" -> TemplateWhatsAppStatus.PENDING_APPROVAL;
            case "REJECTED" -> TemplateWhatsAppStatus.REJECTED;
            default -> TemplateWhatsAppStatus.DRAFT;
        };
    }

    private Integer mapCategory(String category) {
        return switch (category.toUpperCase()) {
            case "MARKETING" -> 1;
            case "UTILITY" -> 2;
            case "AUTHENTICATION" -> 3;
            default -> 0;
        };
    }

    private String getClientSettingValue(Long clientId, String settingName) {
        return clientSettingRepository.findByClientIdAndName(clientId, settingName)
                .map(ClientSetting::getStringValue)
                .orElse(null);
    }

    private String getClientSettingValueWithFallback(Long clientId, String primary, String legacy) {
        String value = getClientSettingValue(clientId, primary);
        if (value == null || value.isBlank()) {
            value = getClientSettingValue(clientId, legacy);
        }
        return value;
    }
}
