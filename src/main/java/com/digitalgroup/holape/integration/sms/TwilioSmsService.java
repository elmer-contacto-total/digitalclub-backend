package com.digitalgroup.holape.integration.sms;

import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.util.PhoneUtils;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "twilio.account-sid", matchIfMissing = false)
public class TwilioSmsService implements SmsService {

    private final UserRepository userRepository;

    @Value("${twilio.account-sid}")
    private String accountSid;

    @Value("${twilio.auth-token}")
    private String authToken;

    @Value("${twilio.phone-number}")
    private String fromPhoneNumber;

    @PostConstruct
    public void init() {
        if (accountSid != null && !accountSid.isEmpty()) {
            Twilio.init(accountSid, authToken);
            log.info("Twilio SMS service initialized");
        }
    }

    @Override
    public boolean sendSms(String phone, String message) {
        try {
            String normalizedPhone = PhoneUtils.normalize(phone);

            Message twilioMessage = Message.creator(
                    new PhoneNumber(normalizedPhone),
                    new PhoneNumber(fromPhoneNumber),
                    message
            ).create();

            log.info("SMS sent via Twilio. SID: {}, To: {}", twilioMessage.getSid(), normalizedPhone);
            return true;

        } catch (Exception e) {
            log.error("Failed to send SMS via Twilio to {}: {}", phone, e.getMessage());
            return false;
        }
    }

    /**
     * Send SMS to a user by their ID
     * PARIDAD RAILS: TwilioClient.send_sms_to_user(message, user_id)
     *
     * @param message the message content
     * @param userId the user ID to send to
     * @return true if sent successfully
     */
    public boolean sendSmsToUser(String message, Long userId) {
        return userRepository.findById(userId)
                .map(user -> {
                    if (user.getPhone() == null || user.getPhone().isEmpty()) {
                        log.warn("User {} has no phone number, cannot send SMS", userId);
                        return false;
                    }
                    return sendSms(user.getPhone(), message);
                })
                .orElseGet(() -> {
                    log.warn("User {} not found, cannot send SMS", userId);
                    return false;
                });
    }
}
