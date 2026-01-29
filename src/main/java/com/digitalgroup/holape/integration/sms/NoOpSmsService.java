package com.digitalgroup.holape.integration.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

/**
 * No-operation SMS Service used when no SMS provider is configured.
 * This is a fallback implementation that logs but doesn't send SMS.
 */
@Slf4j
@Service
@ConditionalOnMissingBean(value = SmsService.class, ignored = NoOpSmsService.class)
public class NoOpSmsService implements SmsService {

    @Override
    public boolean sendSms(String phone, String message) {
        log.warn("SMS sending is disabled. Would have sent to {}: {}", phone, message);
        return false;
    }
}
