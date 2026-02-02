package com.digitalgroup.holape.security.otp;

import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.integration.sms.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private final UserRepository userRepository;
    private final SmsService smsService;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @Value("${app.universal-otp:}")
    private String universalOtp;

    private static final String DEV_OTP = "123456";
    private static final int OTP_MIN = 100000;
    private static final int OTP_MAX = 999999;

    /**
     * Generates and sends OTP to user's phone
     */
    @Transactional
    public String generateAndSendOtp(User user) {
        String otp = generateOtp();

        // Save OTP to user
        user.setOtp(otp);
        userRepository.save(user);

        // Send OTP via SMS (skip in dev mode)
        if (!"dev".equals(activeProfile)) {
            String message = String.format("%s es su código de seguridad de MWS", otp);
            smsService.sendSms(user.getPhone(), message);
            log.info("OTP sent to user: {}", user.getEmail());
        } else {
            log.info("DEV MODE - OTP for user {}: {}", user.getEmail(), otp);
        }

        return otp;
    }

    /**
     * Validates OTP for user
     */
    @Transactional
    public boolean validateOtp(User user, String candidateOtp) {
        if (candidateOtp == null || candidateOtp.isEmpty()) {
            return false;
        }

        // Accept universal OTP if configured (works in all environments)
        if (universalOtp != null && !universalOtp.isBlank() && universalOtp.equals(candidateOtp)) {
            clearOtp(user);
            return true;
        }

        // In dev mode, also accept default OTP
        if ("dev".equals(activeProfile) && DEV_OTP.equals(candidateOtp)) {
            clearOtp(user);
            return true;
        }

        // Validate actual OTP
        if (user.getOtp() != null && user.getOtp().equals(candidateOtp)) {
            clearOtp(user);
            return true;
        }

        return false;
    }

    /**
     * Clears OTP after successful validation
     */
    @Transactional
    public void clearOtp(User user) {
        user.setOtp(null);
        userRepository.save(user);
    }

    /**
     * Generates a 6-digit random OTP
     */
    private String generateOtp() {
        Random random = new Random();
        int otp = random.nextInt(OTP_MAX - OTP_MIN + 1) + OTP_MIN;
        return String.valueOf(otp);
    }
}
