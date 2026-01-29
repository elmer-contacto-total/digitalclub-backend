package com.digitalgroup.holape.job;

import com.digitalgroup.holape.domain.common.enums.MessageDirection;
import com.digitalgroup.holape.domain.common.enums.UserRole;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Reconstruct Require Response User Flag Job
 * Equivalent to Rails ReconstructRequireResponseUserFlagWorker
 *
 * This job runs 20 seconds after a message is created to ensure
 * all prior jobs (ticket assignment at 5s, KPI creation at 10s)
 * have completed before reconstructing the user's response flag.
 *
 * Also runs hourly to ensure data consistency across all users.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconstructRequireResponseUserFlagJob {

    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final DelayedJobService delayedJobService;

    /**
     * Schedule flag reconstruction for a user with 20 second delay
     * Equivalent to: ReconstructRequireResponseUserFlagWorker.perform_in(20.seconds, user_id)
     */
    public void scheduleReconstruction(Long userId) {
        delayedJobService.scheduleIn20Seconds(
                () -> reconstructForUser(userId),
                "ReconstructRequireResponseFlag-" + userId
        );
    }

    /**
     * Reconstruct require_response flag for a specific user
     */
    @Transactional
    public void reconstructForUser(Long userId) {
        try {
            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) {
                log.warn("User {} not found for flag reconstruction", userId);
                return;
            }

            User user = userOpt.get();
            reconstructRequireResponseFlag(user);

        } catch (Exception e) {
            log.error("Error reconstructing require_response flag for user {}: {}",
                    userId, e.getMessage(), e);
        }
    }

    /**
     * Reconstruct the require_response flag for a user based on their last message
     */
    private void reconstructRequireResponseFlag(User user) {
        // Find the last message involving this user
        Optional<Message> lastMessageOpt = messageRepository.findLastMessageByUser(user.getId());

        if (lastMessageOpt.isEmpty()) {
            // No messages, clear the flag
            // PARIDAD RAILS: requireResponseAt no existe en schema
            if (Boolean.TRUE.equals(user.getRequireResponse())) {
                user.setRequireResponse(false);
                userRepository.save(user);
                log.debug("Cleared require_response for user {} (no messages)", user.getId());
            }
            return;
        }

        Message lastMessage = lastMessageOpt.get();

        // Determine if this user needs a response
        boolean requiresResponse = false;

        if (user.getRole() == UserRole.STANDARD) {
            // For standard users (customers):
            // They require a response if their last message was incoming (they sent it)
            // and the agent hasn't responded yet
            if (lastMessage.getDirection() == MessageDirection.INCOMING &&
                    lastMessage.getSender() != null &&
                    lastMessage.getSender().getId().equals(user.getId())) {
                requiresResponse = true;
            }
        } else {
            // For agents/internal users:
            // They need to respond if the last message in their conversation
            // was from the customer (incoming to them)
            if (lastMessage.getDirection() == MessageDirection.INCOMING &&
                    lastMessage.getRecipient() != null &&
                    lastMessage.getRecipient().getId().equals(user.getId())) {
                requiresResponse = true;
            }
        }

        // Update the flag if changed
        // PARIDAD RAILS: requireResponseAt no existe, se infiere de lastMessageAt
        boolean currentFlag = Boolean.TRUE.equals(user.getRequireResponse());
        if (currentFlag != requiresResponse) {
            user.setRequireResponse(requiresResponse);
            if (requiresResponse) {
                user.setLastMessageAt(lastMessage.getCreatedAt());
            }
            userRepository.save(user);
            log.debug("Updated require_response for user {}: {} -> {}",
                    user.getId(), currentFlag, requiresResponse);
        }
    }

    /**
     * Scheduled task to reconstruct require_response for all users
     * Runs every hour to ensure data consistency
     * Equivalent to Rails: User.reconstruct_require_response_for_all_users
     *
     * PARIDAD RAILS: Rails procesa TODOS los usuarios estándar, no solo flag=true
     * Para eficiencia, procesamos en batch con paginación
     */
    @Scheduled(cron = "0 0 * * * *") // Every hour at minute 0
    @Transactional
    public void reconstructForAllUsers() {
        log.info("Starting hourly require_response reconstruction for all users");

        try {
            int updated = 0;
            int cleared = 0;
            int processed = 0;

            // PARIDAD RAILS: Procesar TODOS los usuarios estándar, no solo los con flag=true
            // Rails: User.where(role: 'standard').find_each { |u| u.reconstruct_require_response_for_user }
            // Usar paginación para evitar cargar todos en memoria
            int pageSize = 500;
            int page = 0;
            boolean hasMore = true;

            while (hasMore) {
                org.springframework.data.domain.Page<User> userPage = userRepository.findByRoleIn(
                        List.of(com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD),
                        org.springframework.data.domain.PageRequest.of(page, pageSize)
                );

                for (User user : userPage.getContent()) {
                    try {
                        boolean hadFlag = Boolean.TRUE.equals(user.getRequireResponse());
                        reconstructRequireResponseFlag(user);
                        processed++;

                        if (hadFlag && !Boolean.TRUE.equals(user.getRequireResponse())) {
                            cleared++;
                        } else if (!hadFlag && Boolean.TRUE.equals(user.getRequireResponse())) {
                            updated++;
                        }
                    } catch (Exception e) {
                        log.error("Error reconstructing flag for user {}: {}",
                                user.getId(), e.getMessage());
                    }
                }

                hasMore = userPage.hasNext();
                page++;

                // Log progress for large user bases
                if (page % 10 == 0) {
                    log.info("Processed {} users so far...", processed);
                }
            }

            log.info("Completed hourly require_response reconstruction: {} processed, {} set to true, {} cleared",
                    processed, updated, cleared);

        } catch (Exception e) {
            log.error("Error in hourly require_response reconstruction: {}", e.getMessage(), e);
        }
    }

    /**
     * Reconstruct flags for all users in a specific client
     * Used for manual data repair
     */
    @Transactional
    public void reconstructForClient(Long clientId) {
        log.info("Reconstructing require_response flags for client {}", clientId);

        List<User> clientUsers = userRepository.findByClient_Id(clientId,
                org.springframework.data.domain.Pageable.unpaged()).getContent();

        int count = 0;
        for (User user : clientUsers) {
            try {
                reconstructRequireResponseFlag(user);
                count++;
            } catch (Exception e) {
                log.error("Error reconstructing flag for user {}: {}",
                        user.getId(), e.getMessage());
            }
        }

        log.info("Reconstructed require_response flags for {} users in client {}",
                count, clientId);
    }
}
