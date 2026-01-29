package com.digitalgroup.holape.integration.facebook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Facebook Graph API Service
 * Equivalent to Rails Services::GraphApi
 * Handles WhatsApp Business API onboarding and configuration
 *
 * PARIDAD RAILS: Usa versión v19.0 del Graph API (igual que Rails)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphApiService {

    // PARIDAD RAILS: Versión v19.0 (Rails usa v19.0)
    private static final String GRAPH_API_BASE_URL = "https://graph.facebook.com/v19.0";

    @Value("${facebook.app-id:886437752963072}")
    private String appId;

    @Value("${facebook.app-secret:}")
    private String appSecret;

    @Value("${facebook.app-api-access-token:}")
    private String appApiAccessToken;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    /**
     * Exchange OAuth code for access token
     * Equivalent to: Services::GraphApi.exchange_whatsapp_code
     */
    public Mono<String> exchangeWhatsAppCode(String code) {
        WebClient client = webClientBuilder.baseUrl(GRAPH_API_BASE_URL).build();

        return client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/oauth/access_token")
                        .queryParam("client_id", appId)
                        .queryParam("client_secret", appSecret)
                        .queryParam("code", code)
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(response -> {
                    if (response.has("access_token")) {
                        return response.get("access_token").asText();
                    }
                    throw new RuntimeException("No access token in response");
                })
                .doOnError(e -> log.error("Failed to exchange WhatsApp code: {}", e.getMessage()));
    }

    /**
     * Get Facebook Business ID using debug_token endpoint
     * PARIDAD RAILS: Usa /debug_token con app access token (igual que Rails)
     * Equivalent to: Services::GraphApi.get_business_id
     *
     * Rails implementation:
     * - Uses Authorization header with app API access token
     * - Passes input_token (business access token) and client_secret as query params
     * - Extracts business_id from response.data.granular_scopes[0].target_ids[0]
     */
    public Mono<String> getBusinessId(String businessAccessToken) {
        WebClient client = webClientBuilder.baseUrl(GRAPH_API_BASE_URL).build();

        return client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/debug_token")
                        .queryParam("input_token", businessAccessToken)
                        .queryParam("client_secret", appSecret)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + appApiAccessToken)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(response -> {
                    log.info("Response from Facebook when getting business ID: {}", response);
                    // PARIDAD RAILS: Extract from data.granular_scopes[0].target_ids[0]
                    if (response.has("data") && response.get("data").has("granular_scopes")) {
                        JsonNode granularScopes = response.get("data").get("granular_scopes");
                        if (granularScopes.isArray() && granularScopes.size() > 0) {
                            JsonNode targetIds = granularScopes.get(0).get("target_ids");
                            if (targetIds != null && targetIds.isArray() && targetIds.size() > 0) {
                                return targetIds.get(0).asText();
                            }
                        }
                    }
                    throw new RuntimeException("No business ID in debug_token response");
                })
                .doOnError(e -> log.error("Failed to get business ID: {}", e.getMessage()));
    }

    /**
     * Get WhatsApp Business Account data
     * Equivalent to: Services::GraphApi.get_whatsapp_data
     */
    public Mono<WhatsAppBusinessData> getWhatsAppData(String accessToken, String businessId) {
        WebClient client = webClientBuilder.baseUrl(GRAPH_API_BASE_URL).build();

        return client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/{businessId}/owned_whatsapp_business_accounts")
                        .queryParam("fields", "id,name,timezone_id,account_review_status,on_behalf_of_business_info")
                        .queryParam("access_token", accessToken)
                        .build(businessId))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .flatMap(response -> {
                    if (response.has("data") && response.get("data").isArray() &&
                            response.get("data").size() > 0) {

                        JsonNode waba = response.get("data").get(0);
                        String wabaId = waba.get("id").asText();

                        // Get phone numbers for this WABA
                        return getPhoneNumbers(accessToken, wabaId)
                                .map(phoneData -> {
                                    WhatsAppBusinessData data = new WhatsAppBusinessData();
                                    data.setWabaId(wabaId);
                                    data.setWabaName(waba.has("name") ? waba.get("name").asText() : null);
                                    data.setTimezoneId(waba.has("timezone_id") ? waba.get("timezone_id").asText() : null);
                                    data.setAccountReviewStatus(waba.has("account_review_status") ?
                                            waba.get("account_review_status").asText() : null);

                                    if (phoneData != null) {
                                        data.setPhoneNumberId(phoneData.get("id"));
                                        data.setPhoneNumber(phoneData.get("display_phone_number"));
                                        data.setVerifiedName(phoneData.get("verified_name"));
                                        data.setQualityRating(phoneData.get("quality_rating"));
                                    }

                                    return data;
                                });
                    }
                    return Mono.error(new RuntimeException("No WhatsApp Business Account found"));
                })
                .doOnError(e -> log.error("Failed to get WhatsApp data: {}", e.getMessage()));
    }

    /**
     * Get phone numbers for a WhatsApp Business Account
     */
    private Mono<Map<String, String>> getPhoneNumbers(String accessToken, String wabaId) {
        WebClient client = webClientBuilder.baseUrl(GRAPH_API_BASE_URL).build();

        return client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/{wabaId}/phone_numbers")
                        .queryParam("fields", "id,display_phone_number,verified_name,quality_rating,code_verification_status")
                        .queryParam("access_token", accessToken)
                        .build(wabaId))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(response -> {
                    Map<String, String> phoneData = new HashMap<>();
                    if (response.has("data") && response.get("data").isArray() &&
                            response.get("data").size() > 0) {
                        JsonNode phone = response.get("data").get(0);
                        phoneData.put("id", phone.has("id") ? phone.get("id").asText() : null);
                        phoneData.put("display_phone_number", phone.has("display_phone_number") ?
                                phone.get("display_phone_number").asText() : null);
                        phoneData.put("verified_name", phone.has("verified_name") ?
                                phone.get("verified_name").asText() : null);
                        phoneData.put("quality_rating", phone.has("quality_rating") ?
                                phone.get("quality_rating").asText() : null);
                    }
                    return phoneData;
                });
    }

    /**
     * Subscribe WABA to webhooks
     * Equivalent to: Services::GraphApi.subscribe_waba_to_webhooks
     */
    public Mono<Boolean> subscribeWabaToWebhooks(String accessToken, String wabaId) {
        WebClient client = webClientBuilder.baseUrl(GRAPH_API_BASE_URL).build();

        return client.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/{wabaId}/subscribed_apps")
                        .queryParam("access_token", accessToken)
                        .build(wabaId))
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(response -> {
                    if (response.has("success")) {
                        return response.get("success").asBoolean();
                    }
                    return false;
                })
                .doOnSuccess(success -> log.info("WABA {} webhook subscription: {}", wabaId, success))
                .doOnError(e -> log.error("Failed to subscribe WABA to webhooks: {}", e.getMessage()));
    }

    /**
     * Register phone number for WhatsApp
     */
    public Mono<Boolean> registerPhoneNumber(String accessToken, String phoneNumberId, String pin) {
        WebClient client = webClientBuilder.baseUrl(GRAPH_API_BASE_URL).build();

        Map<String, String> body = Map.of(
                "messaging_product", "whatsapp",
                "pin", pin
        );

        return client.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/{phoneNumberId}/register")
                        .queryParam("access_token", accessToken)
                        .build(phoneNumberId))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(response -> response.has("success") && response.get("success").asBoolean())
                .doOnError(e -> log.error("Failed to register phone number: {}", e.getMessage()));
    }

    /**
     * Data class for WhatsApp Business Account information
     */
    public static class WhatsAppBusinessData {
        private String wabaId;
        private String wabaName;
        private String timezoneId;
        private String accountReviewStatus;
        private String phoneNumberId;
        private String phoneNumber;
        private String verifiedName;
        private String qualityRating;

        // Getters and Setters
        public String getWabaId() { return wabaId; }
        public void setWabaId(String wabaId) { this.wabaId = wabaId; }
        public String getWabaName() { return wabaName; }
        public void setWabaName(String wabaName) { this.wabaName = wabaName; }
        public String getTimezoneId() { return timezoneId; }
        public void setTimezoneId(String timezoneId) { this.timezoneId = timezoneId; }
        public String getAccountReviewStatus() { return accountReviewStatus; }
        public void setAccountReviewStatus(String accountReviewStatus) { this.accountReviewStatus = accountReviewStatus; }
        public String getPhoneNumberId() { return phoneNumberId; }
        public void setPhoneNumberId(String phoneNumberId) { this.phoneNumberId = phoneNumberId; }
        public String getPhoneNumber() { return phoneNumber; }
        public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
        public String getVerifiedName() { return verifiedName; }
        public void setVerifiedName(String verifiedName) { this.verifiedName = verifiedName; }
        public String getQualityRating() { return qualityRating; }
        public void setQualityRating(String qualityRating) { this.qualityRating = qualityRating; }
    }
}
