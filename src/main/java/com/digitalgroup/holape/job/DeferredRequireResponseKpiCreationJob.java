package com.digitalgroup.holape.job;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.entity.ClientSetting;
import com.digitalgroup.holape.domain.client.repository.ClientSettingRepository;
import com.digitalgroup.holape.domain.common.enums.KpiType;
import com.digitalgroup.holape.domain.common.enums.MessageDirection;
import com.digitalgroup.holape.domain.kpi.entity.Kpi;
import com.digitalgroup.holape.domain.kpi.repository.KpiRepository;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Deferred Require Response KPI Creation Job
 * Equivalent to Rails DeferredRequireResponseKpiCreationWorker
 *
 * This job runs 10 seconds after a message is created to allow
 * the ticket assignment job (5 seconds) to complete first.
 * It creates the require_response KPI and schedules the alert check.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeferredRequireResponseKpiCreationJob {

    private final MessageRepository messageRepository;
    private final KpiRepository kpiRepository;
    private final UserRepository userRepository;
    private final ClientSettingRepository clientSettingRepository;
    private final DelayedJobService delayedJobService;
    private final RequireResponseAlertJob requireResponseAlertJob;

    private static final int DEFAULT_ALERT_DELAY_MINUTES = 30;

    /**
     * Schedule KPI creation for a message with 10 second delay
     * Equivalent to: DeferredRequireResponseKpiCreationWorker.perform_in(10.seconds, message_id)
     */
    public void scheduleKpiCreation(Long messageId) {
        delayedJobService.scheduleIn10Seconds(
                () -> createRequireResponseKpi(messageId),
                "DeferredRequireResponseKpiCreation-" + messageId
        );
    }

    /**
     * Create require_response KPI for an incoming message
     */
    @Transactional
    public void createRequireResponseKpi(Long messageId) {
        try {
            Optional<Message> messageOpt = messageRepository.findById(messageId);
            if (messageOpt.isEmpty()) {
                log.warn("Message {} not found for KPI creation", messageId);
                return;
            }

            Message message = messageOpt.get();

            // Only process incoming messages
            if (message.getDirection() != MessageDirection.INCOMING) {
                log.debug("Skipping require_response KPI for non-incoming message {}", messageId);
                return;
            }

            User sender = message.getSender();
            User recipient = message.getRecipient();

            if (sender == null || recipient == null) {
                log.warn("Message {} missing sender or recipient", messageId);
                return;
            }

            Client client = sender.getClient();
            if (client == null) {
                log.warn("Sender {} has no client", sender.getId());
                return;
            }

            // Create require_response KPI
            Map<String, Object> dataHash = new HashMap<>();
            dataHash.put("message_id", messageId);
            dataHash.put("deferred", true);
            if (message.getTicket() != null) {
                dataHash.put("ticket_id", message.getTicket().getId());
            }

            Kpi kpi = new Kpi();
            kpi.setKpiType(KpiType.REQUIRE_RESPONSE);
            kpi.setValue(1);
            kpi.setClient(client);
            kpi.setUser(recipient); // The agent who needs to respond
            // PARIDAD RAILS: messageId no existe como campo, almacenado en dataHash
            if (message.getTicket() != null) {
                kpi.setTicket(message.getTicket());
            }
            kpi.setDataHash(dataHash);

            // PARIDAD RAILS: Copiar timestamps del message (líneas 16-17)
            // created_at: message.created_at, updated_at: message.updated_at
            kpi.setCreatedAt(message.getCreatedAt());
            kpi.setUpdatedAt(message.getUpdatedAt() != null ? message.getUpdatedAt() : message.getCreatedAt());

            kpiRepository.save(kpi);
            log.debug("Created require_response KPI for message {}", messageId);

            // Update sender's require_response flag
            // PARIDAD RAILS: Usar update directo sin callbacks (equivalente a update_columns)
            // Rails: User.find(message.sender_id).update_columns(require_response: true)
            userRepository.updateRequireResponseColumns(sender.getId(), true);

            // Schedule alert check based on client settings
            int alertDelayMinutes = getAlertDelayMinutes(client.getId());
            scheduleAlertCheck(message, sender, recipient, alertDelayMinutes);

        } catch (Exception e) {
            log.error("Error creating require_response KPI for message {}: {}", messageId, e.getMessage(), e);
        }
    }

    /**
     * Get the alert delay minutes from client settings
     */
    private int getAlertDelayMinutes(Long clientId) {
        try {
            Optional<ClientSetting> setting = clientSettingRepository.findByClientIdAndName(
                    clientId, "alert_time_not_responded_conversation");

            if (setting.isPresent() && setting.get().getIntegerValue() != null) {
                return setting.get().getIntegerValue();
            }
        } catch (Exception e) {
            log.warn("Error getting alert delay for client {}: {}", clientId, e.getMessage());
        }

        return DEFAULT_ALERT_DELAY_MINUTES;
    }

    /**
     * Schedule the require response alert check
     */
    private void scheduleAlertCheck(Message message, User sender, User recipient, int delayMinutes) {
        log.debug("Scheduling require_response alert check for message {} in {} minutes",
                message.getId(), delayMinutes);

        delayedJobService.scheduleInMinutes(
                () -> requireResponseAlertJob.checkAndCreateAlert(
                        message.getId(),
                        sender.getId(),
                        recipient.getId(),
                        delayMinutes
                ),
                delayMinutes,
                "RequireResponseAlert-" + message.getId()
        );
    }
}
