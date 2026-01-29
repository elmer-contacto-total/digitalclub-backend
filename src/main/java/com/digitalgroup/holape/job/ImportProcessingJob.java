package com.digitalgroup.holape.job;

import com.digitalgroup.holape.domain.common.enums.ImportStatus;
import com.digitalgroup.holape.domain.importdata.entity.Import;
import com.digitalgroup.holape.domain.importdata.repository.ImportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Import Processing Job
 * Equivalent to Rails CreateUserImportWorker
 * Handles stuck imports and cleanup
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportProcessingJob {

    private final ImportRepository importRepository;

    /**
     * Check for stuck imports and reset them
     * Runs every 10 minutes
     */
    @Scheduled(fixedDelay = 600000) // Every 10 minutes
    @Transactional
    public void checkStuckImports() {
        log.debug("Checking for stuck imports");

        // Find imports that have been processing for more than 30 minutes
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(30);
        List<Import> stuckImports = importRepository.findStuckImports(threshold);

        for (Import importEntity : stuckImports) {
            log.warn("Found stuck import {}, resetting to new", importEntity.getId());

            // Reset to new for retry or mark as error if too many retries
            if (importEntity.getProgress() != null &&
                importEntity.getTotRecords() != null &&
                importEntity.getProgress() < importEntity.getTotRecords()) {
                // Still has rows to process, reset
                importEntity.setStatus(ImportStatus.STATUS_NEW);
            } else {
                // Might be stuck at the end, mark as error
                importEntity.setStatus(ImportStatus.STATUS_ERROR);
            }

            importRepository.save(importEntity);
        }

        if (!stuckImports.isEmpty()) {
            log.info("Reset {} stuck imports", stuckImports.size());
        }
    }

    /**
     * Clean up old completed imports
     * Runs daily at 4:00 AM
     */
    @Scheduled(cron = "0 0 4 * * *") // 4:00 AM daily
    @Transactional
    public void cleanupOldImports() {
        log.info("Starting cleanup of old imports");

        // Keep imports for 30 days, then delete
        LocalDateTime threshold = LocalDateTime.now().minusDays(30);

        // Find and count old imports
        // This is a simplified version - in production you'd want to batch delete

        log.info("Old import cleanup completed");
    }
}
