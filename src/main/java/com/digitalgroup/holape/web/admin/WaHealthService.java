package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.integration.email.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado en memoria del diagnóstico de WhatsApp Web por asesor.
 *
 * No persiste nada (por diseño): los reportes viven en un mapa en memoria y se
 * purgan por TTL. La única acción "saliente" es un email a los super admins
 * cuando se detecta un cambio en la versión de WhatsApp Web, que es la causa
 * raíz capaz de romper los selectores de golpe para todos los asesores.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaHealthService {

    private final UserRepository userRepository;
    private final EmailService emailService;

    @Value("${app.base-url:https://app.holape.com}")
    private String baseUrl;

    /** Reportes más viejos que esto se descartan al leer/escribir. */
    private static final long STALE_TTL_MS = 24 * 60 * 60 * 1000L;

    private record ReportEntry(Long clientId, long receivedAt, Map<String, Object> payload) {}

    private final ConcurrentHashMap<Long, ReportEntry> reports = new ConcurrentHashMap<>();

    // Versiones de WhatsApp Web ya vistas (dedup del email) + última conocida (para el "de X a Y").
    private final Set<String> seenWaVersions = ConcurrentHashMap.newKeySet();
    private String lastWaVersion = null;

    /** Guarda el reporte de un asesor y evalúa si cambió la versión de WhatsApp. */
    public void ingest(Long userId, Long clientId, Map<String, Object> payload) {
        long now = System.currentTimeMillis();
        payload.put("receivedAt", now);
        reports.put(userId, new ReportEntry(clientId, now, payload));
        prune(now);

        Object waVersion = payload.get("waVersion");
        if (waVersion instanceof String s && !s.isBlank()) {
            detectVersionChange(s);
        }
    }

    /**
     * Devuelve el estado de los asesores que efectivamente usan la app de escritorio,
     * es decir, los que reportaron (haber reportado prueba que usan la app).
     *
     * Por defecto NO incluye al resto del padrón: la mayoría de los asesores activos
     * en BD no corren la app, y marcarlos como "caídos" ahoga la señal real.
     *
     * Con includeCoverage=true se agregan los asesores activos del cliente que NUNCA
     * reportaron, como entradas `no_report` — vista opcional de cobertura, no de error.
     */
    public List<Map<String, Object>> reportsForScope(Long clientId, boolean includeCoverage) {
        prune(System.currentTimeMillis());

        List<Map<String, Object>> result = new ArrayList<>();
        for (ReportEntry entry : reports.values()) {
            if (clientId == null || clientId.equals(entry.clientId())) {
                result.add(entry.payload());
            }
        }

        if (includeCoverage && clientId != null) {
            for (User agent : userRepository.findAgentsByClientId(clientId)) {
                if (!reports.containsKey(agent.getId())) {
                    Map<String, Object> synthetic = new HashMap<>();
                    synthetic.put("userId", agent.getId());
                    synthetic.put("userName", agent.getFirstName() + " " + agent.getLastName());
                    synthetic.put("userEmail", agent.getEmail());
                    synthetic.put("status", "no_report");
                    result.add(synthetic);
                }
            }
        }
        return result;
    }

    private void prune(long now) {
        reports.entrySet().removeIf(e -> now - e.getValue().receivedAt() > STALE_TTL_MS);
    }

    /** Sincronizado: garantiza un solo email por versión aunque reporten N asesores a la vez. */
    private synchronized void detectVersionChange(String version) {
        if (seenWaVersions.contains(version)) {
            return;
        }
        boolean firstEver = seenWaVersions.isEmpty();
        String previous = lastWaVersion;
        seenWaVersions.add(version);
        lastWaVersion = version;

        // Al arrancar el backend (set vacío) solo sembramos: no avisamos del "primer" valor.
        if (!firstEver) {
            notifyVersionChange(previous, version);
        }
    }

    private void notifyVersionChange(String previous, String current) {
        List<User> admins = userRepository.findActiveSuperAdmins();
        if (admins.isEmpty()) {
            log.warn("[WaHealth] WhatsApp cambió de versión ({} -> {}) pero no hay super admins a quien avisar", previous, current);
            return;
        }
        String subject = "WhatsApp Web cambió de versión — revisá el diagnóstico";
        String url = baseUrl + "/app/wa_health";
        String body = String.format(
                "WhatsApp Web cambió de la versión %s a la versión %s.%n%n" +
                "Un cambio de versión puede romper los selectores del DOM y caer la captura para todos los asesores. " +
                "Entrá al panel de diagnóstico para ver si hay algo caído:%n%s",
                previous == null ? "desconocida" : previous, current, url);

        for (User admin : admins) {
            try {
                emailService.sendNotification(admin.getEmail(), subject, body);
            } catch (Exception e) {
                log.error("[WaHealth] Error enviando alerta de versión a {}: {}", admin.getEmail(), e.getMessage());
            }
        }
        log.info("[WaHealth] Alerta de cambio de versión WA {} -> {} enviada a {} super admins", previous, current, admins.size());
    }
}
