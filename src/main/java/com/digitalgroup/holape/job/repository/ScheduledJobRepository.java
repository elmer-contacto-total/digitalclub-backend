package com.digitalgroup.holape.job.repository;

import com.digitalgroup.holape.job.entity.ScheduledJob;
import com.digitalgroup.holape.job.entity.ScheduledJob.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for scheduled jobs persistence
 * PARIDAD RAILS: Equivalente a Sidekiq Redis storage
 */
@Repository
public interface ScheduledJobRepository extends JpaRepository<ScheduledJob, Long> {

    /**
     * Find pending jobs that should be executed
     */
    List<ScheduledJob> findByStatusAndExecuteAtBeforeOrderByExecuteAtAsc(
            JobStatus status, LocalDateTime threshold);

    /**
     * Find pending jobs ready to execute now
     */
    default List<ScheduledJob> findReadyToExecute() {
        return findByStatusAndExecuteAtBeforeOrderByExecuteAtAsc(
                JobStatus.PENDING, LocalDateTime.now());
    }

    /**
     * Find all pending jobs for restart recovery
     */
    List<ScheduledJob> findByStatusOrderByExecuteAtAsc(JobStatus status);

    /**
     * Clean up old completed/failed jobs
     */
    @Modifying
    @Query("DELETE FROM ScheduledJob j WHERE j.status IN ('COMPLETED', 'FAILED') AND j.createdAt < :threshold")
    int deleteOldJobs(@Param("threshold") LocalDateTime threshold);

    /**
     * Mark stuck jobs as failed (running for more than 1 hour)
     */
    @Modifying
    @Query("UPDATE ScheduledJob j SET j.status = 'FAILED', j.errorMessage = 'Timeout - job stuck' " +
           "WHERE j.status = 'RUNNING' AND j.createdAt < :threshold")
    int markStuckJobsAsFailed(@Param("threshold") LocalDateTime threshold);

    /**
     * Count pending jobs by type
     */
    long countByJobTypeAndStatus(String jobType, JobStatus status);
}
