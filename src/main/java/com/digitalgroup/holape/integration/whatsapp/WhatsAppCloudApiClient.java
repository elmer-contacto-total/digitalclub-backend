package com.digitalgroup.holape.integration.whatsapp;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.repository.ClientSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * WhatsApp Cloud API Client
 * Equivalent to Rails Services::WhatsappCloudApi
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppCloudApiClient {

    private final ClientSettingRepository clientSettingRepository;
    private final WebClient.Builder webClientBuilder;

    @Value("${whatsapp.api-version:v19.0}")
    private String apiVersion;

    @Value("${whatsapp.base-url:https://graph.facebook.com}")
    private String baseUrl;

    /**
     * Sends a text message via WhatsApp
     */
    public Mono<WhatsAppResponse> sendTextMessage(Client client, String toPhone, String message) {
        String phoneNumberId = getClientSetting(client.getId(), "whatsapp_phone_number_id");
        String accessToken = getClientSetting(client.getId(), "whatsapp_api_token");

        if (phoneNumberId == null || accessToken == null) {
            log.error("WhatsApp not configured for client: {}", client.getId());
            return Mono.error(new RuntimeException("WhatsApp not configured for this client"));
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", normalizePhone(toPhone));
        payload.put("type", "text");
        payload.put("text", Map.of("body", message));

        return sendRequest(phoneNumberId, accessToken, payload);
    }

    /**
     * Sends a template message via WhatsApp
     */
    public Mono<WhatsAppResponse> sendTemplateMessage(Client client, String toPhone,
                                                       String templateName, String language,
                                                       List<Map<String, Object>> components) {
        String phoneNumberId = getClientSetting(client.getId(), "whatsapp_phone_number_id");
        String accessToken = getClientSetting(client.getId(), "whatsapp_api_token");

        if (phoneNumberId == null || accessToken == null) {
            log.error("WhatsApp not configured for client: {}", client.getId());
            return Mono.error(new RuntimeException("WhatsApp not configured for this client"));
        }

        Map<String, Object> template = new HashMap<>();
        template.put("name", templateName);
        template.put("language", Map.of("code", language));
        if (components != null && !components.isEmpty()) {
            template.put("components", components);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", normalizePhone(toPhone));
        payload.put("type", "template");
        payload.put("template", template);

        return sendRequest(phoneNumberId, accessToken, payload);
    }

    /**
     * Sends a template message with simple variables map
     * Convenience method that converts variables to components
     */
    public Mono<WhatsAppResponse> sendTemplateMessage(Client client, String toPhone,
                                                       String templateName,
                                                       Map<String, String> variables) {
        List<Map<String, Object>> components = new ArrayList<>();

        if (variables != null && !variables.isEmpty()) {
            List<Map<String, Object>> parameters = new ArrayList<>();
            for (String value : variables.values()) {
                parameters.add(Map.of("type", "text", "text", value));
            }
            components.add(Map.of("type", "body", "parameters", parameters));
        }

        return sendTemplateMessage(client, toPhone, templateName, "es", components);
    }

    /**
     * Sends a media message (image, document, video, audio)
     */
    public Mono<WhatsAppResponse> sendMediaMessage(Client client, String toPhone,
                                                    String mediaUrl, String mediaType,
                                                    String caption) {
        String phoneNumberId = getClientSetting(client.getId(), "whatsapp_phone_number_id");
        String accessToken = getClientSetting(client.getId(), "whatsapp_api_token");

        if (phoneNumberId == null || accessToken == null) {
            log.error("WhatsApp not configured for client: {}", client.getId());
            return Mono.error(new RuntimeException("WhatsApp not configured for this client"));
        }

        // Determine media type
        String type = switch (mediaType != null ? mediaType.toLowerCase() : "document") {
            case "image", "image/jpeg", "image/png" -> "image";
            case "video", "video/mp4" -> "video";
            case "audio", "audio/mpeg", "audio/ogg" -> "audio";
            default -> "document";
        };

        Map<String, Object> mediaContent = new HashMap<>();
        mediaContent.put("link", mediaUrl);
        if (caption != null && !caption.isEmpty() && !"audio".equals(type)) {
            mediaContent.put("caption", caption);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", normalizePhone(toPhone));
        payload.put("type", type);
        payload.put(type, mediaContent);

        return sendRequest(phoneNumberId, accessToken, payload);
    }

    /**
     * Mark message as read
     */
    public Mono<Void> markMessageAsRead(Client client, String messageId) {
        String phoneNumberId = getClientSetting(client.getId(), "whatsapp_phone_number_id");
        String accessToken = getClientSetting(client.getId(), "whatsapp_api_token");

        if (phoneNumberId == null || accessToken == null) {
            return Mono.empty();
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("status", "read");
        payload.put("message_id", messageId);

        String url = String.format("%s/%s/%s/messages", baseUrl, apiVersion, phoneNumberId);

        return webClientBuilder.build()
                .post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Void.class)
                .doOnError(e -> log.error("Failed to mark message as read: {}", e.getMessage()));
    }

    /**
     * Gets all templates for a client
     */
    public Mono<List<Map<String, Object>>> getAllTemplates(Client client) {
        String businessId = client.getWhatsappBusinessId();
        String accessToken = getClientSetting(client.getId(), "whatsapp_api_token");

        if (businessId == null || accessToken == null) {
            log.error("WhatsApp not configured for client: {}", client.getId());
            return Mono.error(new RuntimeException("WhatsApp not configured for this client"));
        }

        String url = String.format("%s/%s/%s/message_templates", baseUrl, apiVersion, businessId);

        return webClientBuilder.build()
                .get()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(Map.class)
                .<List<Map<String, Object>>>map(response -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
                    return data != null ? data : Collections.emptyList();
                })
                .doOnError(e -> log.error("Failed to get templates: {}", e.getMessage()));
    }

    private Mono<WhatsAppResponse> sendRequest(String phoneNumberId, String accessToken,
                                                Map<String, Object> payload) {
        String url = String.format("%s/%s/%s/messages", baseUrl, apiVersion, phoneNumberId);

        return webClientBuilder.build()
                .post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(WhatsAppResponse.class)
                .doOnSuccess(r -> log.info("WhatsApp message sent successfully"))
                .doOnError(e -> log.error("Failed to send WhatsApp message: {}", e.getMessage()));
    }

    private String getClientSetting(Long clientId, String name) {
        return clientSettingRepository.findStringValueByClientAndName(clientId, name)
                .orElse(null);
    }

    private String normalizePhone(String phone) {
        if (phone == null) return null;
        String cleaned = phone.replaceAll("[^0-9]", "");
        return cleaned.startsWith("+") ? cleaned : cleaned;
    }

    public record WhatsAppResponse(
            String messaging_product,
            List<Contact> contacts,
            List<MessageInfo> messages
    ) {
        public record Contact(String input, String wa_id) {}
        public record MessageInfo(String id) {}
    }
}
