package com.digitalgroup.holape.integration.email;

import com.digitalgroup.holape.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;

/**
 * Email Service
 * Equivalent to Rails UserMailer and other mailers
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${app.mail.from:noreply@holape.com}")
    private String fromEmail;

    @Value("${app.mail.from-name:Holape}")
    private String fromName;

    @Value("${app.base-url:https://app.holape.com}")
    private String baseUrl;

    /**
     * Send user invitation email
     * Equivalent to Rails: UserMailer.invitation
     */
    @Async
    public void sendInvitation(User user, String tempPassword) {
        try {
            Context context = new Context();
            context.setVariable("user", user);
            context.setVariable("tempPassword", tempPassword);
            context.setVariable("loginUrl", baseUrl + "/login");
            context.setVariable("clientName", user.getClient().getName());

            String htmlContent = templateEngine.process("email/invitation", context);

            sendHtmlEmail(
                    user.getEmail(),
                    "Bienvenido a " + user.getClient().getName() + " - Holape",
                    htmlContent
            );

            log.info("Invitation email sent to {}", user.getEmail());
        } catch (Exception e) {
            log.error("Failed to send invitation email to {}: {}", user.getEmail(), e.getMessage());
        }
    }

    /**
     * Send password reset email
     * Equivalent to Rails: Devise::Mailer.reset_password_instructions
     */
    @Async
    public void sendPasswordReset(User user, String resetToken) {
        try {
            Context context = new Context();
            context.setVariable("user", user);
            context.setVariable("resetUrl", baseUrl + "/reset-password?token=" + resetToken);

            String htmlContent = templateEngine.process("email/password-reset", context);

            sendHtmlEmail(
                    user.getEmail(),
                    "Restablecer contraseña - Holape",
                    htmlContent
            );

            log.info("Password reset email sent to {}", user.getEmail());
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}: {}", user.getEmail(), e.getMessage());
        }
    }

    /**
     * Send OTP code via email
     */
    @Async
    public void sendOtpCode(User user, String otpCode) {
        try {
            Context context = new Context();
            context.setVariable("user", user);
            context.setVariable("otpCode", otpCode);

            String htmlContent = templateEngine.process("email/otp-code", context);

            sendHtmlEmail(
                    user.getEmail(),
                    "Tu código de verificación - Holape",
                    htmlContent
            );

            log.info("OTP code email sent to {}", user.getEmail());
        } catch (Exception e) {
            log.error("Failed to send OTP email to {}: {}", user.getEmail(), e.getMessage());
        }
    }

    /**
     * Send import completion notification
     */
    @Async
    public void sendImportComplete(User user, int totalRecords, int successCount, int errorCount) {
        try {
            Context context = new Context();
            context.setVariable("user", user);
            context.setVariable("totalRecords", totalRecords);
            context.setVariable("successCount", successCount);
            context.setVariable("errorCount", errorCount);

            String htmlContent = templateEngine.process("email/import-complete", context);

            sendHtmlEmail(
                    user.getEmail(),
                    "Importación completada - Holape",
                    htmlContent
            );

            log.info("Import complete email sent to {}", user.getEmail());
        } catch (Exception e) {
            log.error("Failed to send import complete email to {}: {}", user.getEmail(), e.getMessage());
        }
    }

    /**
     * Send generic notification
     */
    @Async
    public void sendNotification(String to, String subject, String message) {
        try {
            Context context = new Context();
            context.setVariable("message", message);

            String htmlContent = templateEngine.process("email/notification", context);

            sendHtmlEmail(to, subject, htmlContent);

            log.info("Notification email sent to {}", to);
        } catch (Exception e) {
            log.error("Failed to send notification email to {}: {}", to, e.getMessage());
        }
    }

    /**
     * Send HTML email
     */
    private void sendHtmlEmail(String to, String subject, String htmlContent) throws MessagingException, java.io.UnsupportedEncodingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(fromEmail, fromName);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlContent, true);

        mailSender.send(message);
    }

    /**
     * Send simple text email
     */
    public void sendSimpleEmail(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);

        mailSender.send(message);
        log.info("Simple email sent to {}", to);
    }
}
