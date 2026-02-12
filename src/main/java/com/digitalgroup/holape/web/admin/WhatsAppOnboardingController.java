package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.client.service.ClientService;
import com.digitalgroup.holape.exception.BusinessException;
import com.digitalgroup.holape.integration.facebook.GraphApiService;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * WhatsApp Onboarding Controller
 * Equivalent to Rails Admin::WhatsappOnboardingController
 * Handles WhatsApp Business API OAuth flow and configuration
 *
 * Aligned with actual entity structure:
 * - Uses ClientService to manage settings as name-value pairs
 */
@Slf4j
@RestController
@RequestMapping("/app/whatsapp_onboarding")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
public class WhatsAppOnboardingController {

    private final GraphApiService graphApiService;
    private final ClientService clientService;

    /**
     * Check onboarding status
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> status(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        Long clientId = currentUser.getClientId();

        String accessToken = clientService.getClientSettingValueWithFallback(clientId, "whatsapp_access_token", "whatsapp_api_token");
        String phoneNumberId = clientService.getClientSettingValue(clientId, "whatsapp_phone_number_id");
        String businessAccountId = clientService.getClientSettingValueWithFallback(clientId, "whatsapp_business_account_id", "whatsapp_account_id");

        boolean isConnected = accessToken != null && !accessToken.isEmpty();

        return ResponseEntity.ok(Map.of(
                "is_connected", isConnected,
                "phone_number_id", phoneNumberId != null ? phoneNumberId : "",
                "business_account_id", businessAccountId != null ? businessAccountId : ""
        ));
    }

    /**
     * Exchange OAuth code for access token
     * Equivalent to: exchange_whatsapp_code
     */
    @PostMapping("/exchange_code")
    public ResponseEntity<Map<String, Object>> exchangeCode(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody ExchangeCodeRequest request) {

        try {
            String accessToken = graphApiService.exchangeWhatsAppCode(request.code()).block();

            if (accessToken == null) {
                throw new BusinessException("Failed to exchange code for access token");
            }

            // Save access token using ClientService
            clientService.setStringSetting(currentUser.getClientId(), "whatsapp_access_token", accessToken);

            log.info("WhatsApp access token saved for client {}", currentUser.getClientId());

            return ResponseEntity.ok(Map.of(
                    "result", "success",
                    "message", "Access token obtained successfully"
            ));

        } catch (Exception e) {
            log.error("Failed to exchange WhatsApp code: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "result", "error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Complete onboarding by fetching WhatsApp data
     * Equivalent to: show action in Rails
     */
    @PostMapping("/complete")
    public ResponseEntity<Map<String, Object>> complete(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        Long clientId = currentUser.getClientId();
        String accessToken = clientService.getClientSettingValueWithFallback(clientId, "whatsapp_access_token", "whatsapp_api_token");

        if (accessToken == null || accessToken.isEmpty()) {
            throw new BusinessException("Access token not found. Please exchange code first.");
        }

        try {
            // Get business ID
            String businessId = graphApiService.getBusinessId(accessToken).block();

            if (businessId == null) {
                throw new BusinessException("Failed to get business ID");
            }

            // Get WhatsApp data
            GraphApiService.WhatsAppBusinessData waData = graphApiService
                    .getWhatsAppData(accessToken, businessId)
                    .block();

            if (waData == null) {
                throw new BusinessException("Failed to get WhatsApp Business data");
            }

            // Subscribe to webhooks
            graphApiService.subscribeWabaToWebhooks(accessToken, waData.getWabaId())
                    .block();

            // Update client settings using ClientService
            clientService.setStringSetting(clientId, "whatsapp_business_account_id", waData.getWabaId());
            clientService.setStringSetting(clientId, "whatsapp_phone_number_id", waData.getPhoneNumberId());

            log.info("WhatsApp onboarding completed for client {}", clientId);

            return ResponseEntity.ok(Map.of(
                    "result", "success",
                    "waba_id", waData.getWabaId(),
                    "waba_name", waData.getWabaName() != null ? waData.getWabaName() : "",
                    "phone_number_id", waData.getPhoneNumberId(),
                    "phone_number", waData.getPhoneNumber() != null ? waData.getPhoneNumber() : "",
                    "verified_name", waData.getVerifiedName() != null ? waData.getVerifiedName() : "",
                    "quality_rating", waData.getQualityRating() != null ? waData.getQualityRating() : "",
                    "account_review_status", waData.getAccountReviewStatus() != null ? waData.getAccountReviewStatus() : ""
            ));

        } catch (Exception e) {
            log.error("Failed to complete WhatsApp onboarding: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "result", "error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Disconnect WhatsApp
     */
    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        Long clientId = currentUser.getClientId();

        // Clear WhatsApp settings
        clientService.setStringSetting(clientId, "whatsapp_access_token", null);
        clientService.setStringSetting(clientId, "whatsapp_business_account_id", null);
        clientService.setStringSetting(clientId, "whatsapp_phone_number_id", null);

        log.info("WhatsApp disconnected for client {}", clientId);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", "WhatsApp disconnected successfully"
        ));
    }

    /**
     * Refresh WhatsApp data
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        // Same as complete but without subscribing to webhooks again
        return complete(currentUser);
    }

    public record ExchangeCodeRequest(String code) {}
}
