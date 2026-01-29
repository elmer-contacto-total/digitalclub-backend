package com.digitalgroup.holape.job;

import com.digitalgroup.holape.job.entity.ScheduledJob;
import com.digitalgroup.holape.job.entity.ScheduledJob.JobStatus;
import com.digitalgroup.holape.job.repository.ScheduledJobRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Delayed Job Service
 * Equivalent to Rails Sidekiq perform_in functionality
 * Allows scheduling jobs with specific delays (5s, 10s, 20s, etc.)
 *
 * PARIDAD RAILS: Jobs are persisted to database for restart recovery
 * Similar to Sidekiq Redis persistence
 */
@Slf4j
@Service
public class DelayedJobService {

    private ScheduledExecutorService scheduler;
    private final ScheduledJobRepository scheduledJobRepository;
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;

    public DelayedJobService(ScheduledJobRepository scheduledJobRepository,
                            ApplicationContext applicationContext,
                            ObjectMapper objectMapper) {
        this.scheduledJobRepository = scheduledJobRepository;
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        // Create a scheduler with a thread pool
        scheduler = Executors.newScheduledThreadPool(10, r -> {
            Thread t = new Thread(r, "delayed-job-");
            t.setDaemon(true);
            return t;
        });
        log.info("DelayedJobService initialized with 10 worker threads");

        // PARIDAD RAILS: Recover pending jobs from database (like Sidekiq loads from Redis)
        recoverPendingJobs();
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("DelayedJobService shutdown complete");
    }

    /**
     * PARIDAD RAILS: Recover pending jobs on startup (like Sidekiq loading from Redis)
     */
    private void recoverPendingJobs() {
        try {
            List<ScheduledJob> pendingJobs = scheduledJobRepository.findByStatusOrderByExecuteAtAsc(JobStatus.PENDING);
            if (pendingJobs.isEmpty()) {
                log.info("No pending jobs to recover");
                return;
            }

            log.info("Recovering {} pending jobs from database", pendingJobs.size());
            for (ScheduledJob job : pendingJobs) {
                schedulePersistedJob(job);
            }
        } catch (Exception e) {
            log.error("Error recovering pending jobs: {}", e.getMessage(), e);
        }
    }

    /**
     * Schedule a persisted job for execution
     */
    private void schedulePersistedJob(ScheduledJob job) {
        long delayMs = java.time.Duration.between(LocalDateTime.now(), job.getExecuteAt()).toMillis();
        if (delayMs < 0) delayMs = 0; // Execute immediately if past due

        scheduler.schedule(() -> executePersistedJob(job), delayMs, TimeUnit.MILLISECONDS);
        log.debug("Scheduled recovery job {} ({}) in {}ms", job.getId(), job.getJobName(), delayMs);
    }

    /**
     * Execute a persisted job
     */
    @Transactional
    public void executePersistedJob(ScheduledJob job) {
        try {
            job.setStatus(JobStatus.RUNNING);
            scheduledJobRepository.save(job);

            // Execute based on job type
            executeJobByType(job);

            job.setStatus(JobStatus.COMPLETED);
            job.setExecutedAt(LocalDateTime.now());
            scheduledJobRepository.save(job);

            log.debug("Completed persisted job {} ({})", job.getId(), job.getJobName());

        } catch (Exception e) {
            log.error("Error executing persisted job {} ({}): {}", job.getId(), job.getJobName(), e.getMessage());
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            scheduledJobRepository.save(job);
        }
    }

    /**
     * Execute job based on its type
     */
    private void executeJobByType(ScheduledJob job) {
        Map<String, Object> data = parseJobData(job.getJobData());

        switch (job.getJobType()) {
            case ScheduledJob.TYPE_TICKET_ASSIGNMENT -> {
                Long messageId = getLongValue(data, "messageId");
                if (messageId != null) {
                    applicationContext.getBean(CreateOrAssignToTicketJob.class).assignToTicket(messageId);
                }
            }
            case ScheduledJob.TYPE_REQUIRE_RESPONSE_KPI -> {
                Long messageId = getLongValue(data, "messageId");
                if (messageId != null) {
                    applicationContext.getBean(DeferredRequireResponseKpiCreationJob.class).createRequireResponseKpi(messageId);
                }
            }
            case ScheduledJob.TYPE_REQUIRE_RESPONSE_ALERT -> {
                Long messageId = getLongValue(data, "messageId");
                Long senderId = getLongValue(data, "senderId");
                Long recipientId = getLongValue(data, "recipientId");
                Integer delayMinutes = getIntValue(data, "delayMinutes");
                if (messageId != null && senderId != null && recipientId != null && delayMinutes != null) {
                    applicationContext.getBean(RequireResponseAlertJob.class)
                            .checkAndCreateAlert(messageId, senderId, recipientId, delayMinutes);
                }
            }
            case ScheduledJob.TYPE_RECONSTRUCT_USER_FLAG -> {
                Long userId = getLongValue(data, "userId");
                if (userId != null) {
                    applicationContext.getBean(ReconstructRequireResponseUserFlagJob.class).reconstructForUser(userId);
                }
            }
            default -> log.warn("Unknown job type: {}", job.getJobType());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJobData(String jobData) {
        if (jobData == null || jobData.isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(jobData, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse job data: {}", e.getMessage());
            return Map.of();
        }
    }

    private Long getLongValue(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private Integer getIntValue(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    /**
     * Schedule a job to run after a delay with persistence
     * @param task The task to execute
     * @param delaySeconds Delay in seconds before execution
     * @param jobName Name for logging purposes
     */
    public void scheduleWithDelay(Runnable task, long delaySeconds, String jobName) {
        scheduler.schedule(() -> {
            try {
                log.debug("Executing delayed job: {} (after {}s delay)", jobName, delaySeconds);
                task.run();
                log.debug("Completed delayed job: {}", jobName);
            } catch (Exception e) {
                log.error("Error executing delayed job {}: {}", jobName, e.getMessage(), e);
            }
        }, delaySeconds, TimeUnit.SECONDS);

        log.debug("Scheduled job: {} to run in {}s", jobName, delaySeconds);
    }

    /**
     * Schedule a job with persistence (survives restart)
     * PARIDAD RAILS: Like Sidekiq perform_in with Redis persistence
     */
    @Transactional
    public void scheduleWithPersistence(String jobType, String jobName, Map<String, Object> data,
                                        long delaySeconds) {
        try {
            ScheduledJob job = ScheduledJob.builder()
                    .jobName(jobName)
                    .jobType(jobType)
                    .jobData(objectMapper.writeValueAsString(data))
                    .executeAt(LocalDateTime.now().plusSeconds(delaySeconds))
                    .status(JobStatus.PENDING)
                    .build();

            job = scheduledJobRepository.save(job);

            // Also schedule in memory for immediate execution
            schedulePersistedJob(job);

            log.debug("Persisted job: {} ({}) to run in {}s", job.getId(), jobName, delaySeconds);

        } catch (JsonProcessingException e) {
            log.error("Error persisting job {}: {}", jobName, e.getMessage());
            // Fallback to non-persisted execution
            scheduleWithDelay(() -> executeJobByType(ScheduledJob.builder()
                    .jobType(jobType)
                    .jobData("{}")
                    .build()), delaySeconds, jobName);
        }
    }

    /**
     * Schedule a job with millisecond precision (not persisted)
     */
    public void scheduleWithDelayMs(Runnable task, long delayMs, String jobName) {
        scheduler.schedule(() -> {
            try {
                log.debug("Executing delayed job: {} (after {}ms delay)", jobName, delayMs);
                task.run();
            } catch (Exception e) {
                log.error("Error executing delayed job {}: {}", jobName, e.getMessage(), e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Schedule ticket assignment job after 5 seconds (persisted)
     */
    public void scheduleTicketAssignment(Long messageId) {
        scheduleWithPersistence(
                ScheduledJob.TYPE_TICKET_ASSIGNMENT,
                "TicketAssignment-" + messageId,
                Map.of("messageId", messageId),
                5
        );
    }

    /**
     * Schedule require response KPI creation after 10 seconds (persisted)
     */
    public void scheduleRequireResponseKpi(Long messageId) {
        scheduleWithPersistence(
                ScheduledJob.TYPE_REQUIRE_RESPONSE_KPI,
                "RequireResponseKpi-" + messageId,
                Map.of("messageId", messageId),
                10
        );
    }

    /**
     * Schedule require response alert check (persisted)
     */
    public void scheduleRequireResponseAlert(Long messageId, Long senderId, Long recipientId, int delayMinutes) {
        scheduleWithPersistence(
                ScheduledJob.TYPE_REQUIRE_RESPONSE_ALERT,
                "RequireResponseAlert-" + messageId,
                Map.of(
                        "messageId", messageId,
                        "senderId", senderId,
                        "recipientId", recipientId,
                        "delayMinutes", delayMinutes
                ),
                delayMinutes * 60L
        );
    }

    /**
     * Schedule user flag reconstruction after 20 seconds (persisted)
     */
    public void scheduleUserFlagReconstruction(Long userId) {
        scheduleWithPersistence(
                ScheduledJob.TYPE_RECONSTRUCT_USER_FLAG,
                "ReconstructUserFlag-" + userId,
                Map.of("userId", userId),
                20
        );
    }

    /**
     * Schedule a job to run after 5 seconds (for ticket assignment) - legacy method
     */
    public void scheduleIn5Seconds(Runnable task, String jobName) {
        scheduleWithDelay(task, 5, jobName);
    }

    /**
     * Schedule a job to run after 10 seconds (for deferred KPI creation) - legacy method
     */
    public void scheduleIn10Seconds(Runnable task, String jobName) {
        scheduleWithDelay(task, 10, jobName);
    }

    /**
     * Schedule a job to run after 20 seconds (for require_response reconstruction) - legacy method
     */
    public void scheduleIn20Seconds(Runnable task, String jobName) {
        scheduleWithDelay(task, 20, jobName);
    }

    /**
     * Schedule a job with custom minutes delay (for alert notifications) - legacy method
     */
    public void scheduleInMinutes(Runnable task, int minutes, String jobName) {
        scheduleWithDelay(task, minutes * 60L, jobName);
    }

    /**
     * Get the number of pending tasks (approximate)
     */
    public int getPendingTaskCount() {
        if (scheduler instanceof ScheduledThreadPoolExecutor) {
            return ((ScheduledThreadPoolExecutor) scheduler).getQueue().size();
        }
        return -1;
    }

    /**
     * Get count of persisted pending jobs
     */
    public long getPersistedPendingCount() {
        return scheduledJobRepository.findByStatusOrderByExecuteAtAsc(JobStatus.PENDING).size();
    }

    /**
     * Check if the service is running
     */
    public boolean isRunning() {
        return scheduler != null && !scheduler.isShutdown();
    }

    /**
     * Cleanup old completed/failed jobs (runs daily at 3 AM)
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupOldJobs() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(7);
        int deleted = scheduledJobRepository.deleteOldJobs(threshold);
        if (deleted > 0) {
            log.info("Cleaned up {} old scheduled jobs", deleted);
        }
    }

    /**
     * Mark stuck jobs as failed (runs every 30 minutes)
     */
    @Scheduled(fixedDelay = 1800000)
    @Transactional
    public void markStuckJobs() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(1);
        int marked = scheduledJobRepository.markStuckJobsAsFailed(threshold);
        if (marked > 0) {
            log.warn("Marked {} stuck jobs as failed", marked);
        }
    }

    /**
     * Process any pending jobs that are ready (runs every 10 seconds)
     */
    @Scheduled(fixedDelay = 10000)
    @Transactional
    public void processPendingJobs() {
        List<ScheduledJob> readyJobs = scheduledJobRepository.findReadyToExecute();
        for (ScheduledJob job : readyJobs) {
            // Check if not already scheduled in memory
            if (job.getStatus() == JobStatus.PENDING) {
                schedulePersistedJob(job);
            }
        }
    }
}
