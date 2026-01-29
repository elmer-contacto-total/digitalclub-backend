package com.digitalgroup.holape.job;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.repository.ClientRepository;
import com.digitalgroup.holape.domain.kpi.service.KpiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * KPI Calculation Job
 * Calculates and aggregates KPIs on a schedule
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KpiCalculationJob {

    private final ClientRepository clientRepository;
    private final KpiService kpiService;

    /**
     * Calculate daily KPI aggregates
     * Runs at 1:00 AM every day
     */
    @Scheduled(cron = "0 0 1 * * *") // 1:00 AM daily
    @Transactional
    public void calculateDailyKpis() {
        log.info("Starting daily KPI calculation");

        LocalDate yesterday = LocalDate.now().minusDays(1);
        List<Client> clients = clientRepository.findByActiveTrue();

        for (Client client : clients) {
            try {
                kpiService.calculateDailyKpis(client.getId(), yesterday);
                log.debug("Calculated KPIs for client {} for date {}",
                        client.getId(), yesterday);
            } catch (Exception e) {
                log.error("Failed to calculate KPIs for client {}", client.getId(), e);
            }
        }

        log.info("Daily KPI calculation completed for {} clients", clients.size());
    }

    /**
     * Calculate weekly KPI aggregates
     * Runs at 2:00 AM every Monday
     */
    @Scheduled(cron = "0 0 2 * * MON") // 2:00 AM every Monday
    @Transactional
    public void calculateWeeklyKpis() {
        log.info("Starting weekly KPI calculation");

        LocalDate lastWeekStart = LocalDate.now().minusWeeks(1).with(java.time.DayOfWeek.MONDAY);
        LocalDate lastWeekEnd = lastWeekStart.plusDays(6);

        List<Client> clients = clientRepository.findByActiveTrue();

        for (Client client : clients) {
            try {
                kpiService.calculateWeeklyKpis(client.getId(), lastWeekStart, lastWeekEnd);
                log.debug("Calculated weekly KPIs for client {} for week starting {}",
                        client.getId(), lastWeekStart);
            } catch (Exception e) {
                log.error("Failed to calculate weekly KPIs for client {}", client.getId(), e);
            }
        }

        log.info("Weekly KPI calculation completed for {} clients", clients.size());
    }

    /**
     * Calculate monthly KPI aggregates
     * Runs at 3:00 AM on the 1st of each month
     */
    @Scheduled(cron = "0 0 3 1 * *") // 3:00 AM on 1st of month
    @Transactional
    public void calculateMonthlyKpis() {
        log.info("Starting monthly KPI calculation");

        LocalDate lastMonth = LocalDate.now().minusMonths(1);
        LocalDate monthStart = lastMonth.withDayOfMonth(1);
        LocalDate monthEnd = lastMonth.withDayOfMonth(lastMonth.lengthOfMonth());

        List<Client> clients = clientRepository.findByActiveTrue();

        for (Client client : clients) {
            try {
                kpiService.calculateMonthlyKpis(client.getId(), monthStart, monthEnd);
                log.debug("Calculated monthly KPIs for client {} for month {}",
                        client.getId(), lastMonth.getMonth());
            } catch (Exception e) {
                log.error("Failed to calculate monthly KPIs for client {}", client.getId(), e);
            }
        }

        log.info("Monthly KPI calculation completed for {} clients", clients.size());
    }

    /**
     * Real-time KPI update for dashboard
     * Runs every 5 minutes during business hours
     */
    @Scheduled(cron = "0 */5 9-18 * * MON-FRI") // Every 5 min, 9AM-6PM, Mon-Fri
    @Transactional
    public void updateRealtimeKpis() {
        log.debug("Updating realtime KPIs");

        List<Client> clients = clientRepository.findByActiveTrue();

        for (Client client : clients) {
            try {
                kpiService.updateRealtimeKpis(client.getId());
            } catch (Exception e) {
                log.error("Failed to update realtime KPIs for client {}", client.getId(), e);
            }
        }
    }
}
