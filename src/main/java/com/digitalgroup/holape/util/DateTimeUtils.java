package com.digitalgroup.holape.util;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public final class DateTimeUtils {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Lima");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private DateTimeUtils() {}

    /**
     * Gets current datetime in Lima timezone
     */
    public static LocalDateTime now() {
        return LocalDateTime.now(DEFAULT_ZONE);
    }

    /**
     * Gets current date in Lima timezone
     */
    public static LocalDate today() {
        return LocalDate.now(DEFAULT_ZONE);
    }

    /**
     * Converts LocalDateTime to specific timezone
     */
    public static LocalDateTime toTimezone(LocalDateTime dateTime, String timezone) {
        if (timezone == null || timezone.isEmpty()) {
            return dateTime;
        }

        ZoneId zoneId = ZoneId.of(timezone);
        ZonedDateTime zonedDateTime = dateTime.atZone(DEFAULT_ZONE);
        return zonedDateTime.withZoneSameInstant(zoneId).toLocalDateTime();
    }

    /**
     * Calculates minutes between two datetimes (capped at max value)
     */
    public static long minutesBetween(LocalDateTime start, LocalDateTime end, long maxMinutes) {
        if (start == null || end == null) {
            return 0;
        }

        long minutes = ChronoUnit.MINUTES.between(start, end);
        return Math.min(Math.max(minutes, 0), maxMinutes);
    }

    /**
     * Checks if datetime is within working hours
     */
    public static boolean isWithinWorkingHours(LocalDateTime dateTime, int startHour, int endHour) {
        int hour = dateTime.getHour();
        return hour >= startHour && hour < endHour;
    }

    /**
     * Gets start of day
     */
    public static LocalDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay();
    }

    /**
     * Gets end of day
     */
    public static LocalDateTime endOfDay(LocalDate date) {
        return date.atTime(LocalTime.MAX);
    }

    /**
     * Parses ISO date string
     */
    public static LocalDateTime parseIso(String dateString) {
        if (dateString == null || dateString.isEmpty()) {
            return null;
        }
        return LocalDateTime.parse(dateString, ISO_FORMATTER);
    }

    /**
     * Formats datetime to ISO string
     */
    public static String formatIso(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(ISO_FORMATTER);
    }

    /**
     * Gets datetime from days ago
     */
    public static LocalDateTime daysAgo(int days) {
        return now().minusDays(days);
    }
}
