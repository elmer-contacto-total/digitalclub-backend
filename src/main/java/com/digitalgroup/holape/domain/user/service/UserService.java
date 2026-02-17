package com.digitalgroup.holape.domain.user.service;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.repository.ClientRepository;
import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.common.enums.TicketStatus;
import com.digitalgroup.holape.domain.common.enums.UserRole;
import com.digitalgroup.holape.domain.kpi.repository.KpiRepository;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import com.digitalgroup.holape.domain.ticket.repository.TicketRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.entity.UserManagerHistory;
import com.digitalgroup.holape.domain.user.repository.UserManagerHistoryRepository;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.exception.BusinessException;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import com.digitalgroup.holape.integration.email.EmailService;
import com.digitalgroup.holape.security.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final MessageRepository messageRepository;
    private final TicketRepository ticketRepository;
    private final KpiRepository kpiRepository;
    private final EmailService emailService;
    private final UserManagerHistoryRepository userManagerHistoryRepository;

    @Transactional(readOnly = true)
    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    @Transactional(readOnly = true)
    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
    }

    @Transactional(readOnly = true)
    public User findByPhone(String phone) {
        return userRepository.findByPhone(phone)
                .orElseThrow(() -> new ResourceNotFoundException("User", "phone", phone));
    }

    @Transactional(readOnly = true)
    public Page<User> findByClient(Long clientId, Pageable pageable) {
        return userRepository.findByClient_Id(clientId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<User> findByClientAndStatus(Long clientId, Status status, Pageable pageable) {
        return userRepository.findByClient_IdAndStatus(clientId, status, pageable);
    }

    @Transactional(readOnly = true)
    public List<User> findAgentsByClient(Long clientId) {
        return userRepository.findAgentsByClient(clientId);
    }

    @Transactional(readOnly = true)
    public List<User> findSubordinates(Long managerId) {
        return userRepository.findByManager_Id(managerId);
    }

    @Transactional
    public User create(User user, String rawPassword) {
        if (userRepository.existsByEmail(user.getEmail())) {
            throw new BusinessException("Email already exists");
        }

        if (userRepository.existsByPhone(user.getPhone())) {
            throw new BusinessException("Phone already exists");
        }

        // PARIDAD RAILS: set_fields callback - copiar country_id y time_zone del client
        // Rails líneas 253-254: self.country_id = self.client.country_id; self.time_zone = "Lima"
        if (user.getClient() != null) {
            // Cargar el Client de forma fresca para evitar LazyInitializationException
            // cuando el Client viene como proxy de otra transacción
            Client client = clientRepository.findById(user.getClient().getId())
                    .orElse(user.getClient());
            user.setClient(client);

            if (user.getCountry() == null && client.getCountry() != null) {
                user.setCountry(client.getCountry());
            }
            if (user.getTimeZone() == null || user.getTimeZone().isEmpty()) {
                user.setTimeZone("America/Lima"); // PARIDAD RAILS: default "Lima" -> "America/Lima"
            }
        }

        // PARIDAD RAILS: Prepend country code to phone if not present (líneas 242-246)
        if (user.getPhone() != null && user.getCountry() != null) {
            String countryCode = user.getCountry().getDefaultPhoneCountryCode();
            if (countryCode != null && !countryCode.isEmpty()) {
                String phone = user.getPhone().replaceAll("[^0-9]", "");
                if (!phone.startsWith(countryCode)) {
                    user.setPhone(countryCode + phone);
                }
            }
        }

        user.setEncryptedPassword(passwordEncoder.encode(rawPassword));
        user.setTempPassword(rawPassword);
        user.setInitialPasswordChanged(false);
        user.setUuidToken(UUID.randomUUID().toString());

        User savedUser = userRepository.save(user);

        // Send invitation email for internal users (not standard or whatsapp_business)
        // Equivalent to Rails: after_commit :send_invite_email, on: :create
        sendInviteEmailIfApplicable(savedUser, rawPassword);

        return savedUser;
    }

    /**
     * Send invitation email for internal users
     * Equivalent to Rails: send_invite_email callback
     */
    private void sendInviteEmailIfApplicable(User user, String tempPassword) {
        // Only send for internal users (not standard or whatsapp_business)
        if (user.getRole() != UserRole.STANDARD && user.getRole() != UserRole.WHATSAPP_BUSINESS) {
            try {
                emailService.sendInvitation(user, tempPassword);
                log.info("Invitation email sent to user {}", user.getEmail());
            } catch (Exception e) {
                log.error("Failed to send invitation email to {}: {}", user.getEmail(), e.getMessage());
                // Don't fail the user creation if email fails
            }
        }
    }

    @Transactional
    public User update(Long id, User updatedUser) {
        User existingUser = findById(id);

        if (updatedUser.getFirstName() != null) {
            existingUser.setFirstName(updatedUser.getFirstName());
        }
        if (updatedUser.getLastName() != null) {
            existingUser.setLastName(updatedUser.getLastName());
        }
        if (updatedUser.getPhone() != null && !updatedUser.getPhone().equals(existingUser.getPhone())) {
            if (userRepository.existsByPhone(updatedUser.getPhone())) {
                throw new BusinessException("Phone already exists");
            }
            existingUser.setPhone(updatedUser.getPhone());
        }
        if (updatedUser.getRole() != null) {
            existingUser.setRole(updatedUser.getRole());
        }
        if (updatedUser.getStatus() != null) {
            existingUser.setStatus(updatedUser.getStatus());
        }
        if (updatedUser.getManager() != null) {
            existingUser.setManager(updatedUser.getManager());
        }
        if (updatedUser.getImportString() != null) {
            existingUser.setImportString(updatedUser.getImportString());
        }

        return userRepository.save(existingUser);
    }

    @Transactional
    public void changePassword(Long userId, String newPassword) {
        User user = findById(userId);
        user.setEncryptedPassword(passwordEncoder.encode(newPassword));
        user.setTempPassword(null);
        user.setInitialPasswordChanged(true);
        userRepository.save(user);
    }

    @Transactional
    public void updateLastMessageAt(Long userId) {
        User user = findById(userId);
        user.setLastMessageAt(LocalDateTime.now());
        userRepository.save(user);
    }

    @Transactional
    public void updateRequireResponse(Long userId, boolean requireResponse) {
        User user = findById(userId);
        user.setRequireResponse(requireResponse);
        userRepository.save(user);
    }

    @Transactional
    public void assignManager(Long userId, Long managerId) {
        User user = findById(userId);
        User manager = findById(managerId);

        if (!manager.isManager() && !manager.isAgent()) {
            throw new BusinessException("Assigned user must be a manager or agent");
        }

        user.setManager(manager);
        userRepository.save(user);
        log.info("Assigned manager {} to user {}", managerId, userId);
    }

    @Transactional
    public void deactivate(Long id) {
        User user = findById(id);
        user.setStatus(Status.INACTIVE);
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public long countByClientAndRole(Long clientId, UserRole role) {
        return userRepository.countByClientAndRole(clientId, role);
    }

    /**
     * Generate JWT token for user
     */
    public String generateToken(User user) {
        return jwtTokenProvider.generateTokenWithClientId(
                user.getEmail(),
                user.getClient().getId(),
                user.getId()
        );
    }

    /**
     * Generate impersonation token
     */
    public String generateImpersonationToken(User targetUser, Long originalUserId) {
        return jwtTokenProvider.generateImpersonationToken(
                targetUser.getEmail(),
                targetUser.getClient().getId(),
                targetUser.getId(),
                originalUserId
        );
    }

    /**
     * Update user's require_response flag with timestamp
     * PARIDAD RAILS: requireResponseAt no existe en schema, usar lastMessageAt para tracking
     */
    @Transactional
    public void setRequireResponse(Long userId, boolean requireResponse) {
        User user = findById(userId);
        user.setRequireResponse(requireResponse);
        if (requireResponse && user.getLastMessageAt() == null) {
            // PARIDAD RAILS: usar lastMessageAt para tracking de cuándo se requiere respuesta
            user.setLastMessageAt(LocalDateTime.now());
        }
        userRepository.save(user);
    }

    /**
     * Find users by phone with partial match
     */
    @Transactional(readOnly = true)
    public List<User> searchByPhone(String phonePattern, Long clientId) {
        return userRepository.findByClient_IdAndRole(clientId, UserRole.STANDARD).stream()
                .filter(u -> u.getPhone() != null && u.getPhone().contains(phonePattern))
                .toList();
    }

    /**
     * Find user by UUID token
     */
    @Transactional(readOnly = true)
    public User findByUuidToken(String uuidToken) {
        return userRepository.findByUuidToken(uuidToken)
                .orElseThrow(() -> new ResourceNotFoundException("User", "uuidToken", uuidToken));
    }

    /**
     * Set sticky agent for a user
     * PARIDAD RAILS: stickyAgentId no existe en schema, usar manager_id para asignación de agente
     */
    @Transactional
    public void setStickyAgent(Long userId, Long agentId) {
        User user = findById(userId);
        // PARIDAD RAILS: stickyAgentId no existe, usar manager como asignación permanente
        if (agentId != null) {
            User agent = findById(agentId);
            user.setManager(agent);
        }
        userRepository.save(user);
        log.info("Set sticky agent (manager) {} for user {}", agentId, userId);
    }

    /**
     * Clear sticky agent for a user
     * PARIDAD RAILS: stickyAgentId no existe en schema
     */
    @Transactional
    public void clearStickyAgent(Long userId) {
        // PARIDAD RAILS: stickyAgentId no existe, no hacer nada o limpiar manager
        // No limpiamos manager porque podría ser intencional
        log.info("clearStickyAgent called for user {} - no-op (stickyAgentId no existe)", userId);
    }

    // ==================== RAILS SCOPE EQUIVALENTS ====================

    /**
     * Get clients (standard users) assigned to a manager/agent
     * Equivalent to Rails: User.clients_of(user)
     */
    @Transactional(readOnly = true)
    public List<User> findClientsOf(Long managerId) {
        return userRepository.findClientsOf(managerId);
    }

    /**
     * Get clients with pagination
     */
    @Transactional(readOnly = true)
    public Page<User> findClientsOf(Long managerId, Pageable pageable) {
        return userRepository.findClientsOf(managerId, pageable);
    }

    /**
     * Get all subordinates recursively for a user
     * Equivalent to Rails: user.all_subordinates
     */
    @Transactional(readOnly = true)
    public List<User> findAllSubordinates(Long userId) {
        // Use native recursive query for better performance
        return userRepository.findAllSubordinatesRecursive(userId);
    }

    /**
     * Get users with active conversations (messaged recently)
     * Equivalent to Rails: User.with_active_conversations
     */
    @Transactional(readOnly = true)
    public List<User> findWithActiveConversations(Long clientId, int hoursBack) {
        LocalDateTime since = LocalDateTime.now().minusHours(hoursBack);
        return userRepository.findWithActiveConversations(clientId, since);
    }

    /**
     * Default: 24 hours back
     */
    @Transactional(readOnly = true)
    public List<User> findWithActiveConversations(Long clientId) {
        return findWithActiveConversations(clientId, 24);
    }

    /**
     * Get users requiring response from a specific agent
     * Equivalent to Rails: User.require_response_for(agent)
     */
    @Transactional(readOnly = true)
    public List<User> findRequireResponseFor(Long agentId) {
        return userRepository.findRequireResponseFor(agentId);
    }

    /**
     * Get standard users with assigned managers
     * Equivalent to Rails: User.for_standard_with_managers
     */
    @Transactional(readOnly = true)
    public List<User> findStandardWithManagers(Long clientId) {
        return userRepository.findStandardWithManagers(clientId);
    }

    /**
     * Get users without assigned manager (new prospects)
     * Equivalent to Rails: User.without_manager
     */
    @Transactional(readOnly = true)
    public List<User> findWithoutManager(Long clientId) {
        return userRepository.findWithoutManager(clientId);
    }

    /**
     * Get users by sticky agent
     */
    @Transactional(readOnly = true)
    public List<User> findByStickyAgent(Long agentId) {
        return userRepository.findByStickyAgentId(agentId);
    }

    /**
     * Get internal users (non-standard)
     */
    @Transactional(readOnly = true)
    public List<User> findInternalUsers(Long clientId) {
        return userRepository.findInternalUsers(clientId);
    }

    /**
     * Get users needing ticket closure
     */
    @Transactional(readOnly = true)
    public List<User> findRequireCloseTicket(Long clientId) {
        return userRepository.findRequireCloseTicket(clientId);
    }

    /**
     * Search users by name, phone, or email
     */
    @Transactional(readOnly = true)
    public Page<User> searchUsers(Long clientId, String searchTerm, Pageable pageable) {
        return userRepository.searchUsers(clientId, searchTerm, pageable);
    }

    /**
     * Get agents by client (includes managers who can chat)
     */
    @Transactional(readOnly = true)
    public List<User> findAgentsByClientId(Long clientId) {
        return userRepository.findAgentsByClientId(clientId);
    }

    /**
     * Count users with active conversations in date range
     */
    @Transactional(readOnly = true)
    public long countWithActiveConversationsInRange(Long clientId, LocalDateTime startDate, LocalDateTime endDate) {
        return userRepository.countWithActiveConversationsInRange(clientId, startDate, endDate);
    }

    /**
     * Mark user as requiring response
     * PARIDAD RAILS: requireResponseAt no existe, usar lastMessageAt para tracking
     */
    @Transactional
    public void markRequireResponse(Long userId) {
        User user = findById(userId);
        user.setRequireResponse(true);
        // PARIDAD RAILS: usar lastMessageAt para tracking de tiempo
        if (user.getLastMessageAt() == null) {
            user.setLastMessageAt(LocalDateTime.now());
        }
        userRepository.save(user);
    }

    /**
     * Clear require response flag
     * PARIDAD RAILS: requireResponseAt no existe
     */
    @Transactional
    public void clearRequireResponse(Long userId) {
        User user = findById(userId);
        user.setRequireResponse(false);
        // PARIDAD RAILS: no limpiar lastMessageAt ya que es el timestamp del último mensaje real
        userRepository.save(user);
    }

    /**
     * Mark user as requiring ticket close
     */
    @Transactional
    public void markRequireCloseTicket(Long userId) {
        User user = findById(userId);
        user.setRequireCloseTicket(true);
        userRepository.save(user);
    }

    /**
     * Clear require close ticket flag
     */
    @Transactional
    public void clearRequireCloseTicket(Long userId) {
        User user = findById(userId);
        user.setRequireCloseTicket(false);
        userRepository.save(user);
    }

    /**
     * Get user hierarchy info (for admin display)
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getUserHierarchyInfo(Long userId) {
        User user = findById(userId);

        Map<String, Object> info = new HashMap<>();
        info.put("id", user.getId());
        info.put("name", user.getFullName());
        info.put("role", user.getRole().name());
        info.put("depth", user.getHierarchyDepth());

        // Get direct subordinates count
        List<User> directSubordinates = userRepository.findByManager_Id(userId);
        info.put("directSubordinatesCount", directSubordinates.size());

        // Get all subordinates count (recursive)
        List<User> allSubordinates = userRepository.findAllSubordinatesRecursive(userId);
        info.put("allSubordinatesCount", allSubordinates.size());

        // Get manager info
        if (user.getManager() != null) {
            Map<String, Object> managerInfo = new HashMap<>();
            managerInfo.put("id", user.getManager().getId());
            managerInfo.put("name", user.getManager().getFullName());
            managerInfo.put("role", user.getManager().getRole().name());
            info.put("manager", managerInfo);
        }

        return info;
    }

    /**
     * Bulk assign users to manager
     */
    @Transactional
    public int bulkAssignManager(List<Long> userIds, Long managerId) {
        User manager = findById(managerId);

        if (!manager.isManager() && !manager.isAgent()) {
            throw new BusinessException("Assigned user must be a manager or agent");
        }

        int count = 0;
        for (Long userId : userIds) {
            try {
                User user = findById(userId);
                user.setManager(manager);
                userRepository.save(user);
                count++;
            } catch (Exception e) {
                log.warn("Failed to assign manager to user {}: {}", userId, e.getMessage());
            }
        }

        log.info("Bulk assigned {} users to manager {}", count, managerId);
        return count;
    }

    // ==================== MANAGER MIGRATION ====================

    /**
     * Migrate all data from old agent/manager to new one
     * Equivalent to Rails: User#migrate_kpis_and_tickets_to_new_manager
     *
     * This method is called when a user's manager changes. It:
     * 1. Updates all sent messages: sender_id → new_manager_id
     * 2. Updates all received messages: recipient_id → new_manager_id
     * 3. Updates open tickets: agent_id → new_manager_id
     * 4. Migrates KPIs from old agent to new agent
     *
     * @param oldAgentId The old agent/manager ID
     * @param newAgentId The new agent/manager ID
     * @return MigrationResult with counts of migrated entities
     */
    @Transactional
    public MigrationResult migrateKpisAndTicketsToNewManager(Long oldAgentId, Long newAgentId) {
        if (oldAgentId == null || newAgentId == null) {
            log.warn("Cannot migrate: oldAgentId={}, newAgentId={}", oldAgentId, newAgentId);
            return new MigrationResult(0, 0, 0, 0);
        }

        if (oldAgentId.equals(newAgentId)) {
            log.debug("Same agent, no migration needed: {}", oldAgentId);
            return new MigrationResult(0, 0, 0, 0);
        }

        log.info("Starting migration from agent {} to agent {}", oldAgentId, newAgentId);

        // 1. Migrate sent messages
        int sentMessagesUpdated = messageRepository.updateSenderForAllMessages(oldAgentId, newAgentId);
        log.debug("Migrated {} sent messages", sentMessagesUpdated);

        // 2. Migrate received messages
        int receivedMessagesUpdated = messageRepository.updateRecipientForAllMessages(oldAgentId, newAgentId);
        log.debug("Migrated {} received messages", receivedMessagesUpdated);

        // 3. Migrate open tickets and their KPIs
        // PARIDAD RAILS: user.rb líneas 278-290 - Migra tickets y sus KPIs específicamente
        List<Ticket> openTickets = ticketRepository.findByAgentIdAndStatus(
                oldAgentId, com.digitalgroup.holape.domain.common.enums.TicketStatus.OPEN);

        int ticketsUpdated = 0;
        int kpisUpdated = 0;

        User newAgent = userRepository.findById(newAgentId).orElse(null);
        if (newAgent == null) {
            log.warn("New agent {} not found, cannot complete migration", newAgentId);
            return new MigrationResult(sentMessagesUpdated, receivedMessagesUpdated, 0, 0);
        }

        for (Ticket ticket : openTickets) {
            // Migrate KPIs for this specific ticket (kpi_type = "new_ticket" with ticket_id in data_hash)
            // PARIDAD RAILS: Kpi.where(kpi_type: "new_ticket", user_id: old_agent_id).where("data_hash @> ?", { ticket_id: ticket.id }.to_json)
            String ticketIdJson = "{\"ticket_id\":" + ticket.getId() + "}";
            int kpisForTicket = kpiRepository.updateUserForKpisByTicketId(
                    oldAgentId, newAgentId,
                    com.digitalgroup.holape.domain.common.enums.KpiType.NEW_TICKET.ordinal(),
                    ticketIdJson);
            kpisUpdated += kpisForTicket;

            // Update ticket agent
            ticket.setAgent(newAgent);
            ticketRepository.save(ticket);
            ticketsUpdated++;
        }

        log.debug("Migrated {} open tickets and {} specific KPIs", ticketsUpdated, kpisUpdated);

        log.info("Migration complete: {} sent messages, {} received messages, {} tickets, {} KPIs (ticket-specific)",
                sentMessagesUpdated, receivedMessagesUpdated, ticketsUpdated, kpisUpdated);

        return new MigrationResult(sentMessagesUpdated, receivedMessagesUpdated, ticketsUpdated, kpisUpdated);
    }

    /**
     * Change user's manager and optionally migrate data
     *
     * @param userId The user whose manager is changing
     * @param newManagerId The new manager ID
     * @param migrateData If true, migrates messages, tickets, and KPIs to the new manager
     */
    @Transactional
    public User changeManager(Long userId, Long newManagerId, boolean migrateData) {
        User user = findById(userId);
        User newManager = findById(newManagerId);

        if (!newManager.isManager() && !newManager.isAgent()) {
            throw new BusinessException("Assigned user must be a manager or agent");
        }

        User oldManager = user.getManager();
        Long oldManagerId = oldManager != null ? oldManager.getId() : null;

        user.setManager(newManager);
        User savedUser = userRepository.save(user);

        // Save manager history - Equivalent to Rails: after_commit :save_manager_history
        if (oldManagerId != null || newManagerId != null) {
            saveManagerHistory(savedUser, oldManager, newManager);
        }

        if (migrateData && oldManagerId != null) {
            migrateKpisAndTicketsToNewManager(oldManagerId, newManagerId);
        }

        log.info("Changed manager for user {} from {} to {}, migrateData={}",
                userId, oldManagerId, newManagerId, migrateData);

        return savedUser;
    }

    /**
     * Save manager change history
     * Equivalent to Rails: save_manager_history callback
     */
    private void saveManagerHistory(User user, User oldManager, User newManager) {
        UserManagerHistory history = UserManagerHistory.create(user, oldManager, newManager, null);
        userManagerHistoryRepository.save(history);
        log.debug("Saved manager history for user {}: {} -> {}",
                user.getId(),
                oldManager != null ? oldManager.getId() : "null",
                newManager != null ? newManager.getId() : "null");
    }

    /**
     * Result of migration operation
     */
    public record MigrationResult(
            int sentMessagesUpdated,
            int receivedMessagesUpdated,
            int ticketsUpdated,
            int kpisUpdated
    ) {
        public int totalUpdated() {
            return sentMessagesUpdated + receivedMessagesUpdated + ticketsUpdated + kpisUpdated;
        }
    }

    // ==================== PASSWORD MANAGEMENT ====================

    /**
     * Send reset password instructions via email
     * Equivalent to Rails: User#send_reset_password_instructions
     */
    @Transactional
    public void sendResetPasswordInstructions(Long userId) {
        User user = findById(userId);

        // Generate reset token
        String resetToken = UUID.randomUUID().toString();
        user.setResetPasswordToken(resetToken);
        user.setResetPasswordSentAt(LocalDateTime.now());
        userRepository.save(user);

        // Send email with reset token
        try {
            emailService.sendPasswordReset(user, resetToken);
            log.info("Password reset instructions sent for user {}", userId);
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}: {}", user.getEmail(), e.getMessage());
            throw new BusinessException("Failed to send password reset email");
        }
    }

    /**
     * Update temporary password
     * Equivalent to Rails: UsersController#update_temp_password
     */
    @Transactional
    public void updateTempPassword(Long userId, String newPassword) {
        User user = findById(userId);

        user.setEncryptedPassword(passwordEncoder.encode(newPassword));
        user.setTempPassword(null);
        user.setInitialPasswordChanged(true);
        userRepository.save(user);

        log.info("Temporary password updated for user {}", userId);
    }


    /**
     * Validate and reset password using token
     */
    @Transactional
    public void resetPasswordWithToken(String token, String newPassword) {
        User user = userRepository.findByResetPasswordToken(token)
                .orElseThrow(() -> new BusinessException("Invalid or expired reset token"));

        // Check if token is expired (valid for 6 hours)
        if (user.getResetPasswordSentAt() == null ||
            user.getResetPasswordSentAt().plusHours(6).isBefore(LocalDateTime.now())) {
            throw new BusinessException("Reset token has expired");
        }

        user.setEncryptedPassword(passwordEncoder.encode(newPassword));
        user.setResetPasswordToken(null);
        user.setResetPasswordSentAt(null);
        userRepository.save(user);

        log.info("Password reset successfully for user {}", user.getId());
    }

    /**
     * Change password for authenticated user (validates current password)
     */
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = findById(userId);

        if (!passwordEncoder.matches(currentPassword, user.getEncryptedPassword())) {
            throw new BusinessException("Current password is incorrect");
        }

        user.setEncryptedPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        log.info("Password changed for user {}", userId);
    }

    /**
     * Update password directly without validating current password
     * PARIDAD RAILS: Equivalent to Rails update_temp_password which doesn't validate current password
     */
    @Transactional
    public void updatePasswordDirectly(Long userId, String newPassword) {
        User user = findById(userId);

        user.setEncryptedPassword(passwordEncoder.encode(newPassword));
        user.setTempPassword(null);
        user.setInitialPasswordChanged(true);
        userRepository.save(user);

        log.info("Password updated directly for user {}", userId);
    }
}
