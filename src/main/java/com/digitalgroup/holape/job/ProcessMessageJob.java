package com.digitalgroup.holape.job;

import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.domain.message.service.MessageService;
import com.digitalgroup.holape.domain.common.enums.MessageStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Process Message Job
 * Equivalent to Rails ProcessMessageWorker
 * Processes incoming messages asynchronously
 *
 * PARIDAD RAILS: retryCount no existe en schema, los mensajes se procesan una vez
 * y se marcan como FAILED si hay error
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessMessageJob {

    private final MessageRepository messageRepository;
    private final MessageService messageService;

    /**
     * Process a single message asynchronously
     */
    @Async
    public void processAsync(Long messageId) {
        try {
            log.debug("Processing message {} asynchronously", messageId);
            messageService.processIncomingMessage(messageId);
        } catch (Exception e) {
            log.error("Error processing message {}", messageId, e);
        }
    }

    /**
     * Scheduled job to process unprocessed messages
     * Runs every minute to pick up any messages that weren't processed immediately
     * PARIDAD RAILS: retryCount no existe, simplemente procesa mensajes pendientes
     */
    @Scheduled(fixedDelay = 60000) // Every 1 minute
    @Transactional
    public void processUnprocessedMessages() {
        List<Message> unprocessedMessages = messageRepository
                .findByStatusOrderByCreatedAtAsc(MessageStatus.PENDING);

        if (!unprocessedMessages.isEmpty()) {
            log.info("Found {} unprocessed messages", unprocessedMessages.size());

            for (Message message : unprocessedMessages) {
                try {
                    messageService.processIncomingMessage(message.getId());
                } catch (Exception e) {
                    log.error("Failed to process message {}", message.getId(), e);
                    // PARIDAD RAILS: no hay retryCount, marcar como FAILED directamente
                    message.setStatus(MessageStatus.FAILED);
                    messageRepository.save(message);
                }
            }
        }
    }

    /**
     * Retry failed messages
     * Runs every 5 minutes
     * PARIDAD RAILS: sin retryCount, simplemente re-intenta mensajes fallidos una vez
     */
    @Scheduled(fixedDelay = 300000) // Every 5 minutes
    @Transactional
    public void retryFailedMessages() {
        List<Message> failedMessages = messageRepository
                .findByStatusOrderByCreatedAtAsc(MessageStatus.FAILED);

        int retried = 0;
        for (Message message : failedMessages) {
            try {
                // PARIDAD RAILS: sin retryCount, simplemente volver a PENDING
                message.setStatus(MessageStatus.PENDING);
                messageRepository.save(message);
                retried++;
            } catch (Exception e) {
                log.error("Failed to retry message {}", message.getId(), e);
            }
        }

        if (retried > 0) {
            log.info("Queued {} failed messages for retry", retried);
        }
    }
}
