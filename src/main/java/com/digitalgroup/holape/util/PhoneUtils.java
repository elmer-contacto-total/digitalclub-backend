package com.digitalgroup.holape.util;

public final class PhoneUtils {

    private PhoneUtils() {}

    /**
     * Normalizes phone number to international format
     * @param phone Phone number
     * @return Normalized phone with + prefix
     */
    public static String normalize(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }

        String cleaned = phone.replaceAll("[^0-9]", "");

        if (!cleaned.startsWith("+")) {
            return "+" + cleaned;
        }

        return cleaned;
    }

    /**
     * Normalizes phone for Peru (adds 51 prefix if needed)
     * @param phone Phone number
     * @return Phone with Peru country code
     */
    public static String normalizeForPeru(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }

        String cleaned = phone.replaceAll("[^0-9]", "");

        if (cleaned.startsWith("51")) {
            return cleaned;
        }

        return "51" + cleaned;
    }

    /**
     * Removes country code prefix for local SMS services
     * @param phone Phone number with country code
     * @return Local phone number
     */
    public static String toLocalFormat(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }

        String cleaned = phone.replaceAll("[^0-9]", "");

        if (cleaned.startsWith("51") && cleaned.length() > 9) {
            return cleaned.substring(2);
        }

        return cleaned;
    }

    /**
     * Validates if phone number format is correct
     * @param phone Phone number
     * @return true if valid
     */
    public static boolean isValid(String phone) {
        if (phone == null || phone.isEmpty()) {
            return false;
        }

        String cleaned = phone.replaceAll("[^0-9]", "");
        return cleaned.length() >= 9 && cleaned.length() <= 15;
    }
}
