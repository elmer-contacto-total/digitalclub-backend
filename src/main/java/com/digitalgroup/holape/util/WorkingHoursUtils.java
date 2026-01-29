package com.digitalgroup.holape.util;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * Utility class for calculating working hours
 * Equivalent to working_hours_utils.rb
 */
public final class WorkingHoursUtils {

    private static final int DEFAULT_START_HOUR = 9;
    private static final int DEFAULT_END_HOUR = 18;

    private WorkingHoursUtils() {}

    /**
     * Calculates working minutes between two datetimes
     * Excludes weekends and non-working hours
     */
    public static long calculateWorkingMinutes(LocalDateTime start, LocalDateTime end) {
        return calculateWorkingMinutes(start, end, DEFAULT_START_HOUR, DEFAULT_END_HOUR);
    }

    /**
     * Calculates working minutes between two datetimes with custom hours
     */
    public static long calculateWorkingMinutes(LocalDateTime start, LocalDateTime end,
                                                int startHour, int endHour) {
        if (start == null || end == null || start.isAfter(end)) {
            return 0;
        }

        long totalMinutes = 0;
        LocalDateTime current = start;

        while (current.isBefore(end)) {
            if (isWorkingDay(current) && isWithinWorkingHours(current, startHour, endHour)) {
                LocalDateTime endOfWorkingPeriod = getEndOfWorkingPeriod(current, end, endHour);
                totalMinutes += ChronoUnit.MINUTES.between(current, endOfWorkingPeriod);
                current = endOfWorkingPeriod;
            } else {
                current = getNextWorkingPeriodStart(current, startHour);
            }
        }

        return totalMinutes;
    }

    /**
     * Checks if the given datetime falls on a working day (Mon-Fri)
     */
    public static boolean isWorkingDay(LocalDateTime dateTime) {
        DayOfWeek day = dateTime.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    /**
     * Checks if the given datetime is within working hours
     */
    public static boolean isWithinWorkingHours(LocalDateTime dateTime, int startHour, int endHour) {
        int hour = dateTime.getHour();
        int minute = dateTime.getMinute();

        LocalTime time = LocalTime.of(hour, minute);
        LocalTime workStart = LocalTime.of(startHour, 0);
        LocalTime workEnd = LocalTime.of(endHour, 0);

        return !time.isBefore(workStart) && time.isBefore(workEnd);
    }

    /**
     * Gets the end of the current working period
     */
    private static LocalDateTime getEndOfWorkingPeriod(LocalDateTime current,
                                                        LocalDateTime end, int endHour) {
        LocalDateTime endOfDay = current.toLocalDate().atTime(endHour, 0);

        if (end.isBefore(endOfDay)) {
            return end;
        }
        return endOfDay;
    }

    /**
     * Gets the start of the next working period
     */
    private static LocalDateTime getNextWorkingPeriodStart(LocalDateTime current, int startHour) {
        LocalDateTime next = current;

        // If we're past working hours today, move to tomorrow
        if (current.getHour() >= startHour) {
            next = current.plusDays(1).toLocalDate().atTime(startHour, 0);
        } else {
            next = current.toLocalDate().atTime(startHour, 0);
        }

        // Skip weekends
        while (!isWorkingDay(next)) {
            next = next.plusDays(1);
        }

        return next;
    }

    /**
     * Converts total minutes to hours and minutes format
     */
    public static String formatMinutesToHoursAndMinutes(long totalMinutes) {
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        }
        return String.format("%dm", minutes);
    }
}
