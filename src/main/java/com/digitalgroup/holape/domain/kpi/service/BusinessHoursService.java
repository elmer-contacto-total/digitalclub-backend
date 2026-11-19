package com.digitalgroup.holape.domain.kpi.service;

import com.digitalgroup.holape.domain.client.repository.ClientSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calcula "minutos laborables" entre dos instantes, descontando noches, días no
 * laborables y (según la config) fines de semana. El horario se lee de la config
 * del cliente: client_settings.online_monday ... online_sunday, con valores como
 * "9am - 6pm" (vacío = día cerrado). Es la misma fuente que ya edita el cliente.
 *
 * Si un cliente no tiene ningún horario parseable, se cae a tiempo corrido 24/7
 * (ChronoUnit.MINUTES) para no romper ni poner todo en cero.
 *
 * Los timestamps llegan en UTC (como se guardan) y se convierten a la zona del
 * negocio (America/Lima) para compararlos contra el horario.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessHoursService {

    private final ClientSettingRepository clientSettingRepository;

    // Zona del negocio. FOH/InFinance opera en Perú. Si a futuro se necesita por
    // cliente, se puede mover a client_settings.
    private static final ZoneId ZONE = ZoneId.of("America/Lima");
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static final String[] DAY_SETTING = {
            null, // index 0 unused (DayOfWeek es 1..7)
            "online_monday", "online_tuesday", "online_wednesday", "online_thursday",
            "online_friday", "online_saturday", "online_sunday"
    };

    // "9am", "6pm", "9:00", "18:00", "9 am", "6:30pm"
    private static final Pattern TIME = Pattern.compile(
            "(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", Pattern.CASE_INSENSITIVE);

    /** Horario parseado de un cliente: ventana [open,close] por día (1..7), null = cerrado. */
    public static final class Schedule {
        final LocalTime[] open = new LocalTime[8];
        final LocalTime[] close = new LocalTime[8];
        boolean hasAnyOpen = false;
    }

    /** Carga y parsea el horario del cliente desde client_settings. */
    public Schedule getSchedule(Long clientId) {
        Schedule s = new Schedule();
        if (clientId == null) return s;
        for (int d = 1; d <= 7; d++) {
            String raw = clientSettingRepository
                    .findStringValueByClientAndName(clientId, DAY_SETTING[d])
                    .orElse(null);
            LocalTime[] win = parseWindow(raw);
            if (win != null) {
                s.open[d] = win[0];
                s.close[d] = win[1];
                s.hasAnyOpen = true;
            }
        }
        return s;
    }

    /** Minutos laborables entre dos instantes UTC, usando el horario ya cargado. */
    public long businessMinutes(Schedule s, LocalDateTime startUtc, LocalDateTime endUtc) {
        if (startUtc == null || endUtc == null || !endUtc.isAfter(startUtc)) return 0;

        // Sin horario parseable → tiempo corrido (fallback seguro).
        if (s == null || !s.hasAnyOpen) {
            return Math.max(0, ChronoUnit.MINUTES.between(startUtc, endUtc));
        }

        LocalDateTime start = startUtc.atZone(UTC).withZoneSameInstant(ZONE).toLocalDateTime();
        LocalDateTime end = endUtc.atZone(UTC).withZoneSameInstant(ZONE).toLocalDateTime();

        long totalMinutes = 0;
        for (LocalDate day = start.toLocalDate(); !day.isAfter(end.toLocalDate()); day = day.plusDays(1)) {
            int dow = day.getDayOfWeek().getValue(); // 1=Mon..7=Sun
            LocalTime open = s.open[dow];
            LocalTime close = s.close[dow];
            if (open == null || close == null || !close.isAfter(open)) continue; // cerrado

            LocalDateTime dayOpen = LocalDateTime.of(day, open);
            LocalDateTime dayClose = LocalDateTime.of(day, close);

            LocalDateTime segStart = start.isAfter(dayOpen) ? start : dayOpen;
            LocalDateTime segEnd = end.isBefore(dayClose) ? end : dayClose;
            if (segEnd.isAfter(segStart)) {
                totalMinutes += ChronoUnit.MINUTES.between(segStart, segEnd);
            }
        }
        return totalMinutes;
    }

    /** Conveniencia: carga el horario y calcula (para llamadas puntuales). */
    public long businessMinutes(Long clientId, LocalDateTime startUtc, LocalDateTime endUtc) {
        return businessMinutes(getSchedule(clientId), startUtc, endUtc);
    }

    /** Parsea "9am - 6pm" → [09:00, 18:00]. Vacío/no parseable → null (cerrado). */
    static LocalTime[] parseWindow(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        String[] parts = v.split("-");
        if (parts.length != 2) return null;
        LocalTime open = parseTime(parts[0]);
        LocalTime close = parseTime(parts[1]);
        if (open == null || close == null) return null;
        return new LocalTime[]{open, close};
    }

    static LocalTime parseTime(String token) {
        if (token == null) return null;
        Matcher m = TIME.matcher(token.trim());
        if (!m.find()) return null;
        try {
            int hour = Integer.parseInt(m.group(1));
            int minute = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
            String ampm = m.group(3);
            if (ampm != null) {
                ampm = ampm.toLowerCase();
                if ("am".equals(ampm)) {
                    if (hour == 12) hour = 0;
                } else { // pm
                    if (hour != 12) hour += 12;
                }
            }
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
            return LocalTime.of(hour, minute);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
