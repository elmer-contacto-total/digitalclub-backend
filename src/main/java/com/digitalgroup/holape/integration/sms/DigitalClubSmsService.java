package com.digitalgroup.holape.integration.sms;

import com.digitalgroup.holape.util.PhoneUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Digital Club SMS Service for Peru (Intico)
 * Equivalent to Rails Services::DigitalClubSms
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(name = "digitalclub.user", matchIfMissing = false)
public class DigitalClubSmsService implements SmsService {

    @Value("${digitalclub.url}")
    private String apiUrl;

    @Value("${digitalclub.user}")
    private String user;

    @Value("${digitalclub.password}")
    private String password;

    @Value("${digitalclub.sender-id}")
    private String senderId;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public boolean sendSms(String phone, String message) {
        try {
            // Convert to local format (remove country code)
            String localPhone = PhoneUtils.toLocalFormat(phone);

            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("usuario", user);
            requestBody.put("clave", password);
            requestBody.put("celular", localPhone);
            requestBody.put("mensaje", message);
            requestBody.put("senderId", senderId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, String>> entity = new HttpEntity<>(requestBody, headers);

            String response = restTemplate.postForObject(apiUrl, entity, String.class);

            log.info("SMS sent via Digital Club to {}: {}", localPhone, response);
            return true;

        } catch (Exception e) {
            log.error("Failed to send SMS via Digital Club to {}: {}", phone, e.getMessage());
            return false;
        }
    }
}
