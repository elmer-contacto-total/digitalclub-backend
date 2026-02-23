package com.digitalgroup.holape.util;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public final class DateTimeUtils {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Lima");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * Maps Rails ActiveSupport timezone names to IANA zone IDs.
     * Rails stores short names like "Lima", Java needs "America/Lima".
     */
    private static final Map<String, String> RAILS_TO_IANA = Map.ofEntries(
            Map.entry("Lima", "America/Lima"),
            Map.entry("Bogota", "America/Bogota"),
            Map.entry("Quito", "America/Guayaquil"),
            Map.entry("Mexico City", "America/Mexico_City"),
            Map.entry("Buenos Aires", "America/Argentina/Buenos_Aires"),
            Map.entry("Santiago", "America/Santiago"),
            Map.entry("Brasilia", "America/Sao_Paulo"),
            Map.entry("Eastern Time (US & Canada)", "America/New_York"),
            Map.entry("Central Time (US & Canada)", "America/Chicago"),
            Map.entry("Pacific Time (US & Canada)", "America/Los_Angeles"),
            Map.entry("Mountain Time (US & Canada)", "America/Denver"),
            Map.entry("UTC", "UTC")
    );

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

        ZoneId zoneId = resolveZoneId(timezone);
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

    /**
     * Resolves a timezone string to a ZoneId.
     * Handles Rails short names (e.g. "Lima") and IANA IDs (e.g. "America/Lima").
     */
    public static ZoneId resolveZoneId(String timezone) {
        if (timezone == null || timezone.isEmpty()) {
            return DEFAULT_ZONE;
        }
        String mapped = RAILS_TO_IANA.get(timezone);
        if (mapped != null) {
            return ZoneId.of(mapped);
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return DEFAULT_ZONE;
        }
    }

    /**
     * Converts start-of-day in user timezone to UTC LocalDateTime.
     * Rails parity: Time.use_zone(tz) { Time.zone.parse(date).beginning_of_day }.utc
     */
    public static LocalDateTime startOfDayInUtc(LocalDate date, String timezone) {
        ZoneId zone = resolveZoneId(timezone);
        return date.atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * Converts end-of-day in user timezone to UTC LocalDateTime.
     * Rails parity: Time.use_zone(tz) { Time.zone.parse(date).end_of_day }.utc
     */
    public static LocalDateTime endOfDayInUtc(LocalDate date, String timezone) {
        ZoneId zone = resolveZoneId(timezone);
        return date.atTime(LocalTime.MAX).atZone(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
