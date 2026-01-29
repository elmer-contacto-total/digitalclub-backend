package com.digitalgroup.holape.util;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Utility class for accessing i18n messages
 * Equivalent to Rails I18n.t()
 */
@Component
@RequiredArgsConstructor
public class MessageUtils {

    private final MessageSource messageSource;

    /**
     * Get message for current locale
     * Equivalent to Rails: I18n.t('key')
     */
    public String getMessage(String key) {
        return getMessage(key, null, LocaleContextHolder.getLocale());
    }

    /**
     * Get message with arguments for current locale
     * Equivalent to Rails: I18n.t('key', count: 5)
     */
    public String getMessage(String key, Object[] args) {
        return getMessage(key, args, LocaleContextHolder.getLocale());
    }

    /**
     * Get message for specific locale
     */
    public String getMessage(String key, Object[] args, Locale locale) {
        try {
            return messageSource.getMessage(key, args, locale);
        } catch (Exception e) {
            return key; // Return key if not found
        }
    }

    /**
     * Get message with default value
     */
    public String getMessage(String key, String defaultMessage) {
        try {
            return messageSource.getMessage(key, null, defaultMessage, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return defaultMessage;
        }
    }

    /**
     * Check if message exists for key
     */
    public boolean hasMessage(String key) {
        try {
            String message = messageSource.getMessage(key, null, null, LocaleContextHolder.getLocale());
            return message != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Convenience methods ====================

    /**
     * Get error message
     * Equivalent to Rails: I18n.t('error.user.email.taken')
     */
    public String getError(String errorKey) {
        return getMessage("error." + errorKey);
    }

    /**
     * Get success message
     */
    public String getSuccess(String successKey) {
        return getMessage("success." + successKey);
    }

    /**
     * Get enum translation
     * Equivalent to Rails: I18n.t('enums.user.role.admin')
     */
    public String getEnumLabel(String enumType, String enumValue) {
        return getMessage("enum." + enumType + "." + enumValue.toLowerCase());
    }

    /**
     * Get validation error message
     */
    public String getValidationError(String entity, String field, String error) {
        return getMessage("error." + entity + "." + field + "." + error);
    }
}
