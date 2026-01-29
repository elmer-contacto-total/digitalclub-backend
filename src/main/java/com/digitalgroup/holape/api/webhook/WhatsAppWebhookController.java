package com.digitalgroup.holape.api.webhook;

import com.digitalgroup.holape.integration.whatsapp.WhatsAppWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * WhatsApp Webhook Controller
 * Handles incoming webhooks from WhatsApp Cloud API
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WhatsAppWebhookController {

    private final WhatsAppWebhookService webhookService;

    @Value("${whatsapp.webhook-verify-token}")
    private String verifyToken;

    /**
     * Webhook verification endpoint (GET)
     * WhatsApp calls this to verify the webhook URL
     */
    @GetMapping("/whatsapp_webhook")
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(name = "hub.mode") String mode,
            @RequestParam(name = "hub.verify_token") String token,
            @RequestParam(name = "hub.challenge") String challenge) {

        log.info("WhatsApp webhook verification request received");

        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            log.info("WhatsApp webhook verified successfully");
            return ResponseEntity.ok(challenge);
        }

        log.warn("WhatsApp webhook verification failed");
        return ResponseEntity.status(403).body("Verification failed");
    }

    /**
     * Webhook event handler (POST)
     * Receives messages and status updates from WhatsApp
     */
    @PostMapping("/whatsapp_webhook")
    public ResponseEntity<String> handleWebhook(@RequestBody Map<String, Object> payload) {
        log.info("WhatsApp webhook event received");

        try {
            webhookService.processWebhook(payload);
            return ResponseEntity.ok("EVENT_RECEIVED");
        } catch (Exception e) {
            log.error("Error processing WhatsApp webhook: {}", e.getMessage());
            // Return 200 to prevent WhatsApp from retrying
            return ResponseEntity.ok("EVENT_RECEIVED");
        }
    }
}
