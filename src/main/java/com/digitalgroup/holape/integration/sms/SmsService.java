package com.digitalgroup.holape.integration.sms;

public interface SmsService {

    /**
     * Sends an SMS to the specified phone number
     * @param phone Phone number (with or without country code)
     * @param message Message content
     * @return true if sent successfully
     */
    boolean sendSms(String phone, String message);
}
