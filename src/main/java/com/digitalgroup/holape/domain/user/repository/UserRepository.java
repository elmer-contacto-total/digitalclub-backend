package com.digitalgroup.holape.domain.user.repository;

import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.common.enums.UserRole;
import com.digitalgroup.holape.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    Optional<User> findByPhone(String phone);

    Optional<User> findByUuidToken(String uuidToken);

    Optional<User> findByResetPasswordToken(String token);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    // Client-scoped queries
    Page<User> findByClient_IdAndStatus(Long clientId, Status status, Pageable pageable);

    Page<User> findByClient_Id(Long clientId, Pageable pageable);

    /**
     * Find all users by client with manager eagerly loaded
     * PARIDAD Rails: User.current_client(@current_client).includes([:client, :manager])
     */
    @Query("""
            SELECT u FROM User u
            LEFT JOIN FETCH u.manager
            WHERE u.client.id = :clientId
            """)
    Page<User> findAllByClientIdWithManager(@Param("clientId") Long clientId, Pageable pageable);

    List<User> findByClient_IdAndRole(Long clientId, UserRole role);

    List<User> findByClient_IdAndManager_IdAndRole(Long clientId, Long managerId, UserRole role);

    /**
     * Lightweight native query for contact export — returns only needed columns with manager name via JOIN.
     * Avoids loading full User entities and N+1 queries on manager relationship.
     * PARIDAD RAILS: Admin::DashboardController#export_contacts
     */
    @Query(value = "SELECT u.id, u.codigo, u.first_name, u.last_name, u.phone, u.email, " +
            "CONCAT(COALESCE(m.first_name,''), ' ', COALESCE(m.last_name,'')) as manager_name, " +
            "u.last_message_at, u.created_at " +
            "FROM users u LEFT JOIN users m ON u.manager_id = m.id " +
            "WHERE u.client_id = :clientId AND u.role = :role " +
            "ORDER BY u.id", nativeQuery = true)
    List<Object[]> findContactsForExport(@Param("clientId") Long clientId, @Param("role") int role);

    @Query(value = "SELECT u.id, u.codigo, u.first_name, u.last_name, u.phone, u.email, " +
            "CONCAT(COALESCE(m.first_name,''), ' ', COALESCE(m.last_name,'')) as manager_name, " +
            "u.last_message_at, u.created_at " +
            "FROM users u LEFT JOIN users m ON u.manager_id = m.id " +
            "WHERE u.client_id = :clientId AND u.manager_id = :managerId AND u.role = :role " +
            "ORDER BY u.id", nativeQuery = true)
    List<Object[]> findContactsForExportByManager(@Param("clientId") Long clientId, @Param("managerId") Long managerId, @Param("role") int role);

    @Query(value = "SELECT u.id, u.codigo, u.first_name, u.last_name, u.phone, u.email, " +
            "CONCAT(COALESCE(m.first_name,''), ' ', COALESCE(m.last_name,'')) as manager_name, " +
            "u.last_message_at, u.created_at " +
            "FROM users u LEFT JOIN users m ON u.manager_id = m.id " +
            "WHERE u.client_id = :clientId AND u.manager_id IN :managerIds AND u.role = :role " +
            "ORDER BY u.id", nativeQuery = true)
    List<Object[]> findContactsForExportByManagers(@Param("clientId") Long clientId,
            @Param("managerIds") List<Long> managerIds, @Param("role") int role);

    List<User> findByClient_IdAndRoleIn(Long clientId, List<UserRole> roles);

    // PARIDAD RAILS: Query para reconstruir flags de todos los usuarios por rol
    Page<User> findByRoleIn(List<UserRole> roles, Pageable pageable);

    // Manager-scoped queries
    List<User> findByManager_Id(Long managerId);

    Page<User> findByManager_IdAndRole(Long managerId, UserRole role, Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.client.id = :clientId AND u.role IN :roles AND u.status = :status")
    List<User> findActiveInternalUsers(
            @Param("clientId") Long clientId,
            @Param("roles") List<UserRole> roles,
            @Param("status") Status status);

    @Query("SELECT u FROM User u WHERE u.client.id = :clientId AND u.role IN :roles")
    Page<User> findInternalUsersPaged(
            @Param("clientId") Long clientId,
            @Param("roles") List<UserRole> roles,
            Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.client.id = :clientId AND u.role IN :roles " +
           "AND (LOWER(CONCAT(u.firstName, ' ', COALESCE(u.lastName, ''))) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR u.phone LIKE CONCAT('%', :search, '%'))")
    Page<User> searchInternalUsers(
            @Param("clientId") Long clientId,
            @Param("roles") List<UserRole> roles,
            @Param("search") String search,
            Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.client.id = :clientId AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.AGENT")
    List<User> findAgentsByClient(@Param("clientId") Long clientId);

    @Query("SELECT u FROM User u WHERE u.client.id = :clientId AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.WHATSAPP_BUSINESS")
    Optional<User> findWhatsAppBusinessUser(@Param("clientId") Long clientId);

    @Query("SELECT u FROM User u WHERE u.client.id = :clientId AND u.requireResponse = true")
    List<User> findUsersRequiringResponse(@Param("clientId") Long clientId);

    @Query("SELECT u FROM User u WHERE u.lastMessageAt >= :since AND u.client.id = :clientId")
    List<User> findActiveConversations(
            @Param("clientId") Long clientId,
            @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(u) FROM User u WHERE u.client.id = :clientId AND u.role = :role")
    long countByClientAndRole(@Param("clientId") Long clientId, @Param("role") UserRole role);

    // Subordinates query (recursive hierarchy)
    @Query("SELECT u FROM User u WHERE u.manager.id = :managerId AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE")
    List<User> findSubordinatesByManagerId(@Param("managerId") Long managerId);

    // Find all subordinates recursively (for bulk messages)
    @Query(value = """
            WITH RECURSIVE subordinates AS (
                SELECT id, manager_id, first_name, last_name, email, phone, role, status, client_id
                FROM users WHERE manager_id = :managerId AND status = 'active'
                UNION ALL
                SELECT u.id, u.manager_id, u.first_name, u.last_name, u.email, u.phone, u.role, u.status, u.client_id
                FROM users u
                INNER JOIN subordinates s ON u.manager_id = s.id
                WHERE u.status = 'active'
            )
            SELECT * FROM subordinates
            """, nativeQuery = true)
    List<User> findAllSubordinatesRecursive(@Param("managerId") Long managerId);

    // Supervisor: find clients under a supervisor's agents
    @Query("""
            SELECT DISTINCT u FROM User u
            WHERE u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.manager.id IN (
                SELECT a.id FROM User a
                WHERE a.manager.id = :supervisorId
                AND a.role = com.digitalgroup.holape.domain.common.enums.UserRole.AGENT
            )
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            """)
    Page<User> findClientsBySupervisor(@Param("supervisorId") Long supervisorId, Pageable pageable);

    // Supervisor: find agents under supervisor (with manager eagerly loaded)
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.manager WHERE u.manager.id = :supervisorId AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.AGENT AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE")
    List<User> findAgentsBySupervisor(@Param("supervisorId") Long supervisorId);

    // Find agents by client (used by message routing)
    @Query("SELECT u FROM User u WHERE u.client.id = :clientId AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.AGENT AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE")
    List<User> findActiveAgentsByClientId(@Param("clientId") Long clientId);

    // Find by sticky agent (for message routing)
    @Query("SELECT u FROM User u WHERE u.id = :agentId AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE")
    Optional<User> findActiveStickyAgent(@Param("agentId") Long agentId);

    // Count active users by client
    @Query("SELECT COUNT(u) FROM User u WHERE u.client.id = :clientId AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE")
    long countActiveByClient(@Param("clientId") Long clientId);

    // Find users with require_response flag
    // PARIDAD RAILS: requireResponseAt no existe en schema, usar lastMessageAt
    @Query("SELECT u FROM User u WHERE u.client.id = :clientId AND u.requireResponse = true AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE ORDER BY u.lastMessageAt ASC")
    Page<User> findRequiringResponseByClient(@Param("clientId") Long clientId, Pageable pageable);

    // Find prospects (users who haven't been assigned to an agent yet)
    @Query("""
            SELECT u FROM User u
            WHERE u.client.id = :clientId
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.manager IS NULL
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            ORDER BY u.createdAt DESC
            """)
    Page<User> findUnassignedProspects(@Param("clientId") Long clientId, Pageable pageable);

    // Find prospects assigned to a specific agent
    @Query("""
            SELECT u FROM User u
            WHERE u.manager.id = :agentId
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            ORDER BY u.lastMessageAt DESC NULLS LAST
            """)
    Page<User> findProspectsByAgent(@Param("agentId") Long agentId, Pageable pageable);

    // Find by phone and client (for imports and duplicate detection)
    @Query("SELECT u FROM User u WHERE u.phone = :phone AND u.client.id = :clientId")
    Optional<User> findByPhoneAndClientId(@Param("phone") String phone, @Param("clientId") Long clientId);

    // Find by email and client (for imports and manager lookup)
    @Query("SELECT u FROM User u WHERE LOWER(u.email) = LOWER(:email) AND u.client.id = :clientId")
    Optional<User> findByEmailAndClientId(@Param("email") String email, @Param("clientId") Long clientId);

    // Find by codigo and client (for FOH imports)
    @Query("SELECT u FROM User u WHERE u.codigo = :codigo AND u.client.id = :clientId")
    Optional<User> findByCodigoAndClientId(@Param("codigo") String codigo, @Param("clientId") Long clientId);

    // Find by import_string and client (case-insensitive, for agent linking)
    @Query("SELECT u FROM User u WHERE LOWER(u.importString) = LOWER(:importString) AND u.client.id = :clientId")
    Optional<User> findByImportStringAndClientId(@Param("importString") String importString, @Param("clientId") Long clientId);

    // ==================== RAILS SCOPE EQUIVALENTS ====================

    // Equivalent to Rails: clients_of(user) - Standard users assigned to a manager/agent
    @Query("""
            SELECT u FROM User u
            WHERE u.manager.id = :managerId
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            ORDER BY u.lastMessageAt DESC NULLS LAST
            """)
    List<User> findClientsOf(@Param("managerId") Long managerId);

    // Paginated version
    @Query("""
            SELECT u FROM User u
            WHERE u.manager.id = :managerId
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            ORDER BY u.lastMessageAt DESC NULLS LAST
            """)
    Page<User> findClientsOf(@Param("managerId") Long managerId, Pageable pageable);

    // Equivalent to Rails: with_active_conversations - Users with recent messages
    @Query("""
            SELECT u FROM User u
            WHERE u.client.id = :clientId
            AND u.lastMessageAt >= :since
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            ORDER BY u.lastMessageAt DESC
            """)
    List<User> findWithActiveConversations(
            @Param("clientId") Long clientId,
            @Param("since") LocalDateTime since);

    // Equivalent to Rails: require_response_for(agent) - Users requiring response from specific agent
    // PARIDAD RAILS: requireResponseAt no existe en schema, usar lastMessageAt
    @Query("""
            SELECT u FROM User u
            WHERE u.manager.id = :agentId
            AND u.requireResponse = true
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            ORDER BY u.lastMessageAt ASC
            """)
    List<User> findRequireResponseFor(@Param("agentId") Long agentId);

    // Equivalent to Rails: for_standard_with_managers - Standard users with assigned managers
    @Query("""
            SELECT u FROM User u
            WHERE u.client.id = :clientId
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.manager IS NOT NULL
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            """)
    List<User> findStandardWithManagers(@Param("clientId") Long clientId);

    // Equivalent to Rails: without_manager - Users without assigned manager
    @Query("""
            SELECT u FROM User u
            WHERE u.client.id = :clientId
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.manager IS NULL
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            ORDER BY u.createdAt DESC
            """)
    List<User> findWithoutManager(@Param("clientId") Long clientId);

    // PARIDAD RAILS: stickyAgentId no existe en schema Rails
    // Esta funcionalidad se maneja a través de la relación manager
    // El método se mantiene pero busca por manager_id en su lugar
    @Query("""
            SELECT u FROM User u
            WHERE u.manager.id = :agentId
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            """)
    List<User> findByStickyAgentId(@Param("agentId") Long agentId);

    // Find internal users (non-standard)
    @Query("""
            SELECT u FROM User u
            WHERE u.client.id = :clientId
            AND u.role != com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            ORDER BY u.role, u.firstName
            """)
    List<User> findInternalUsers(@Param("clientId") Long clientId);

    // Find users needing ticket close
    @Query("""
            SELECT u FROM User u
            WHERE u.client.id = :clientId
            AND u.requireCloseTicket = true
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            ORDER BY u.lastMessageAt DESC
            """)
    List<User> findRequireCloseTicket(@Param("clientId") Long clientId);

    // Search users by name or phone
    @Query("""
            SELECT u FROM User u
            WHERE u.client.id = :clientId
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            AND (
                LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE LOWER(CONCAT('%', :search, '%'))
                OR u.phone LIKE CONCAT('%', :search, '%')
                OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
            )
            ORDER BY u.lastMessageAt DESC NULLS LAST
            """)
    Page<User> searchUsers(
            @Param("clientId") Long clientId,
            @Param("search") String search,
            Pageable pageable);

    // Count users with active conversations in date range
    @Query("""
            SELECT COUNT(DISTINCT u) FROM User u
            WHERE u.client.id = :clientId
            AND u.lastMessageAt BETWEEN :startDate AND :endDate
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            """)
    long countWithActiveConversationsInRange(
            @Param("clientId") Long clientId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    // Find agents by client (includes AGENT and managers who can chat)
    @Query("""
            SELECT u FROM User u
            WHERE u.client.id = :clientId
            AND u.role IN (com.digitalgroup.holape.domain.common.enums.UserRole.AGENT, com.digitalgroup.holape.domain.common.enums.UserRole.MANAGER_LEVEL_1, com.digitalgroup.holape.domain.common.enums.UserRole.MANAGER_LEVEL_2, com.digitalgroup.holape.domain.common.enums.UserRole.MANAGER_LEVEL_3, com.digitalgroup.holape.domain.common.enums.UserRole.MANAGER_LEVEL_4)
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            ORDER BY u.firstName
            """)
    List<User> findAgentsByClientId(@Param("clientId") Long clientId);

    // Count all subordinates recursively
    @Query(value = """
            WITH RECURSIVE subordinates AS (
                SELECT id, manager_id
                FROM users
                WHERE manager_id = :managerId

                UNION ALL

                SELECT u.id, u.manager_id
                FROM users u
                INNER JOIN subordinates s ON u.manager_id = s.id
            )
            SELECT COUNT(*) FROM subordinates
            """, nativeQuery = true)
    long countAllSubordinatesRecursive(@Param("managerId") Long managerId);

    // ==================== TICKET STATUS SCOPES (Rails equivalents) ====================

    /**
     * Find standard users with open tickets
     * Equivalent to Rails: scope :with_open_tickets
     */
    @Query(value = """
            SELECT DISTINCT u.* FROM users u
            INNER JOIN tickets t ON t.user_id = u.id
            WHERE u.role = 0
            AND t.status = 0
            AND u.status = 0
            ORDER BY u.last_message_at DESC NULLS LAST
            """, nativeQuery = true)
    List<User> findWithOpenTickets();

    /**
     * Find standard users with open tickets (paginated)
     */
    @Query(value = """
            SELECT DISTINCT u.* FROM users u
            INNER JOIN tickets t ON t.user_id = u.id
            WHERE u.role = 0
            AND t.status = 0
            AND u.status = 0
            ORDER BY u.last_message_at DESC NULLS LAST
            """,
            countQuery = """
            SELECT COUNT(DISTINCT u.id) FROM users u
            INNER JOIN tickets t ON t.user_id = u.id
            WHERE u.role = 0 AND t.status = 0 AND u.status = 0
            """,
            nativeQuery = true)
    Page<User> findWithOpenTickets(Pageable pageable);

    /**
     * Find standard users without open tickets
     * Equivalent to Rails: scope :without_open_tickets
     */
    @Query(value = """
            SELECT u.* FROM users u
            WHERE u.role = 0
            AND u.status = 0
            AND u.id NOT IN (
                SELECT DISTINCT t.user_id FROM tickets t
                WHERE t.status = 0
            )
            ORDER BY u.last_message_at DESC NULLS LAST
            """, nativeQuery = true)
    List<User> findWithoutOpenTickets();

    /**
     * Find standard users with open tickets for a specific agent
     * Equivalent to Rails: scope :with_open_tickets_for_agent
     */
    @Query(value = """
            SELECT DISTINCT u.* FROM users u
            INNER JOIN tickets t ON t.user_id = u.id
            WHERE u.role = 0
            AND t.status = 0
            AND t.agent_id = :agentId
            AND u.status = 0
            ORDER BY u.last_message_at DESC NULLS LAST
            """, nativeQuery = true)
    List<User> findWithOpenTicketsForAgent(@Param("agentId") Long agentId);

    /**
     * Find standard users with open tickets for a specific agent (paginated)
     */
    @Query(value = """
            SELECT DISTINCT u.* FROM users u
            INNER JOIN tickets t ON t.user_id = u.id
            WHERE u.role = 0
            AND t.status = 0
            AND t.agent_id = :agentId
            AND u.status = 0
            ORDER BY u.last_message_at DESC NULLS LAST
            """,
            countQuery = """
            SELECT COUNT(DISTINCT u.id) FROM users u
            INNER JOIN tickets t ON t.user_id = u.id
            WHERE u.role = 0 AND t.status = 0 AND t.agent_id = :agentId AND u.status = 0
            """,
            nativeQuery = true)
    Page<User> findWithOpenTicketsForAgent(@Param("agentId") Long agentId, Pageable pageable);

    /**
     * Find standard users without open tickets for a specific agent
     * Equivalent to Rails: scope :without_open_tickets_for_agent
     */
    @Query(value = """
            SELECT u.* FROM users u
            WHERE u.role = 0
            AND u.manager_id = :agentId
            AND u.status = 0
            AND u.id NOT IN (
                SELECT DISTINCT t.user_id FROM tickets t
                WHERE t.status = 0 AND t.agent_id = :agentId
            )
            ORDER BY u.last_message_at DESC NULLS LAST
            """, nativeQuery = true)
    List<User> findWithoutOpenTicketsForAgent(@Param("agentId") Long agentId);

    // ==================== MESSAGE STATUS SCOPES (Rails equivalents) ====================

    /**
     * Find standard users with unresponded messages (require_response = true)
     * Equivalent to Rails: scope :with_unresponded_messages
     * PARIDAD RAILS: requireResponseAt no existe, usar lastMessageAt
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.requireResponse = true
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            ORDER BY u.lastMessageAt ASC
            """)
    List<User> findWithUnrespondedMessages();

    /**
     * Find standard users with responded messages (require_response = false)
     * Equivalent to Rails: scope :responded_messages
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.requireResponse = false
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            ORDER BY u.lastMessageAt DESC NULLS LAST
            """)
    List<User> findRespondedMessages();

    /**
     * Find standard users with unresponded messages for a specific agent
     * Equivalent to Rails: scope :with_unresponded_messages_for_agent
     * PARIDAD RAILS: requireResponseAt no existe, usar lastMessageAt
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.manager.id = :agentId
            AND u.requireResponse = true
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            ORDER BY u.lastMessageAt ASC
            """)
    List<User> findWithUnrespondedMessagesForAgent(@Param("agentId") Long agentId);

    /**
     * Find standard users with responded messages for a specific agent
     * Equivalent to Rails: scope :responded_messages_for_agent
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.manager.id = :agentId
            AND u.requireResponse = false
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            ORDER BY u.lastMessageAt DESC NULLS LAST
            """)
    List<User> findRespondedMessagesForAgent(@Param("agentId") Long agentId);

    // ==================== COMBINED FILTER SCOPES ====================

    /**
     * Find users by manager with filters for ticket status and message status
     * Used by supervisor_clients endpoint
     */
    @Query(value = """
            SELECT DISTINCT u.* FROM users u
            WHERE u.manager_id IN :managerIds
            AND u.role = 0
            AND u.status = 0
            AND (:ticketStatus IS NULL OR
                 (:ticketStatus = 'open' AND u.id IN (SELECT t.user_id FROM tickets t WHERE t.status = 0)) OR
                 (:ticketStatus = 'closed' AND u.id NOT IN (SELECT t.user_id FROM tickets t WHERE t.status = 0)))
            AND (:messageStatus IS NULL OR
                 (:messageStatus = 'to_respond' AND u.require_response = true) OR
                 (:messageStatus = 'responded' AND u.require_response = false))
            ORDER BY u.last_message_at DESC NULLS LAST
            """, nativeQuery = true)
    List<User> findByManagersWithFilters(
            @Param("managerIds") List<Long> managerIds,
            @Param("ticketStatus") String ticketStatus,
            @Param("messageStatus") String messageStatus);

    /**
     * Find users by manager with filters (paginated)
     */
    @Query(value = """
            SELECT DISTINCT u.* FROM users u
            WHERE u.manager_id IN :managerIds
            AND u.role = 0
            AND u.status = 0
            AND (:ticketStatus IS NULL OR
                 (:ticketStatus = 'open' AND u.id IN (SELECT t.user_id FROM tickets t WHERE t.status = 0)) OR
                 (:ticketStatus = 'closed' AND u.id NOT IN (SELECT t.user_id FROM tickets t WHERE t.status = 0)))
            AND (:messageStatus IS NULL OR
                 (:messageStatus = 'to_respond' AND u.require_response = true) OR
                 (:messageStatus = 'responded' AND u.require_response = false))
            ORDER BY u.last_message_at DESC NULLS LAST
            """,
            countQuery = """
            SELECT COUNT(DISTINCT u.id) FROM users u
            WHERE u.manager_id IN :managerIds
            AND u.role = 0 AND u.status = 0
            AND (:ticketStatus IS NULL OR
                 (:ticketStatus = 'open' AND u.id IN (SELECT t.user_id FROM tickets t WHERE t.status = 0)) OR
                 (:ticketStatus = 'closed' AND u.id NOT IN (SELECT t.user_id FROM tickets t WHERE t.status = 0)))
            AND (:messageStatus IS NULL OR
                 (:messageStatus = 'to_respond' AND u.require_response = true) OR
                 (:messageStatus = 'responded' AND u.require_response = false))
            """,
            nativeQuery = true)
    Page<User> findByManagersWithFilters(
            @Param("managerIds") List<Long> managerIds,
            @Param("ticketStatus") String ticketStatus,
            @Param("messageStatus") String messageStatus,
            Pageable pageable);

    /**
     * Search users by name, phone, or codigo with filters
     */
    @Query(value = """
            SELECT DISTINCT u.* FROM users u
            WHERE u.manager_id IN :managerIds
            AND u.role = 0
            AND u.status = 0
            AND (
                LOWER(CONCAT(u.first_name, ' ', u.last_name)) LIKE LOWER(CONCAT('%', :search, '%'))
                OR u.phone LIKE CONCAT('%', :search, '%')
                OR u.codigo LIKE CONCAT('%', :search, '%')
            )
            ORDER BY u.last_message_at DESC NULLS LAST
            """, nativeQuery = true)
    List<User> searchByManagersAndTerm(
            @Param("managerIds") List<Long> managerIds,
            @Param("search") String search);

    // Get first user by client (for default user assignment)
    Optional<User> findFirstByClient_IdOrderByIdAsc(Long clientId);

    /**
     * Find users by client and name (first_name + last_name contains search term)
     * PARIDAD ELECTRON: CRM panel search by name fallback
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.client.id = :clientId
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            AND LOWER(CONCAT(u.firstName, ' ', COALESCE(u.lastName, ''))) LIKE LOWER(CONCAT('%', :name, '%'))
            ORDER BY u.lastMessageAt DESC NULLS LAST
            """)
    List<User> findByClientIdAndNameContaining(@Param("clientId") Long clientId, @Param("name") String name);

    // ==================== AGENT CLIENTS FILTER QUERIES (Rails Parity) ====================

    /**
     * Find all clients (subordinates) for a specific manager (agent) - DEFAULT view
     * PARIDAD RAILS: current_user.subordinates
     * Uses native query with explicit countQuery to avoid Hibernate 6.x count generation bug
     */
    @Query(value = """
            SELECT u.* FROM users u
            WHERE u.manager_id = :managerId
            AND u.role = 0
            AND u.status = 0
            ORDER BY u.last_message_at DESC NULLS LAST
            """,
            countQuery = """
            SELECT COUNT(u.id) FROM users u
            WHERE u.manager_id = :managerId
            AND u.role = 0
            AND u.status = 0
            """,
            nativeQuery = true)
    Page<User> findClientsOfNative(@Param("managerId") Long managerId, Pageable pageable);

    /**
     * Find clients with open tickets for a specific manager (agent)
     * PARIDAD: Rails scope :with_open_tickets_for_agent
     */
    @Query(value = """
            SELECT DISTINCT u.* FROM users u
            INNER JOIN tickets t ON t.user_id = u.id
            WHERE u.manager_id = :managerId
            AND u.role = 0
            AND u.status = 0
            AND t.status = 0
            ORDER BY u.last_message_at DESC NULLS LAST
            """,
            countQuery = """
            SELECT COUNT(DISTINCT u.id) FROM users u
            INNER JOIN tickets t ON t.user_id = u.id
            WHERE u.manager_id = :managerId AND u.role = 0 AND u.status = 0 AND t.status = 0
            """,
            nativeQuery = true)
    Page<User> findClientsWithOpenTicketsByManager(@Param("managerId") Long managerId, Pageable pageable);

    /**
     * Find clients without open tickets for a specific manager (agent)
     * PARIDAD: Rails scope :without_open_tickets_for_agent
     */
    @Query(value = """
            SELECT u.* FROM users u
            WHERE u.manager_id = :managerId
            AND u.role = 0
            AND u.status = 0
            AND u.id NOT IN (
                SELECT DISTINCT t.user_id FROM tickets t WHERE t.status = 0
            )
            ORDER BY u.last_message_at DESC NULLS LAST
            """,
            countQuery = """
            SELECT COUNT(u.id) FROM users u
            WHERE u.manager_id = :managerId AND u.role = 0 AND u.status = 0
            AND u.id NOT IN (SELECT DISTINCT t.user_id FROM tickets t WHERE t.status = 0)
            """,
            nativeQuery = true)
    Page<User> findClientsWithoutOpenTicketsByManager(@Param("managerId") Long managerId, Pageable pageable);

    /**
     * Find clients requiring response for a specific manager (agent)
     * PARIDAD: Rails scope :with_unresponded_messages_for_agent
     * Native query with explicit countQuery to avoid Hibernate 6.x count generation bug
     */
    @Query(value = """
            SELECT u.* FROM users u
            WHERE u.manager_id = :managerId
            AND u.role = 0
            AND u.require_response = true
            AND u.status = 0
            ORDER BY u.last_message_at ASC NULLS LAST
            """,
            countQuery = """
            SELECT COUNT(u.id) FROM users u
            WHERE u.manager_id = :managerId AND u.role = 0
            AND u.require_response = true AND u.status = 0
            """,
            nativeQuery = true)
    Page<User> findClientsRequiringResponseByManager(@Param("managerId") Long managerId, Pageable pageable);

    /**
     * Find clients that have been responded to for a specific manager (agent)
     * PARIDAD: Rails scope :responded_messages_for_agent
     * Native query with explicit countQuery to avoid Hibernate 6.x count generation bug
     */
    @Query(value = """
            SELECT u.* FROM users u
            WHERE u.manager_id = :managerId
            AND u.role = 0
            AND (u.require_response = false OR u.require_response IS NULL)
            AND u.status = 0
            ORDER BY u.last_message_at DESC NULLS LAST
            """,
            countQuery = """
            SELECT COUNT(u.id) FROM users u
            WHERE u.manager_id = :managerId AND u.role = 0
            AND (u.require_response = false OR u.require_response IS NULL) AND u.status = 0
            """,
            nativeQuery = true)
    Page<User> findClientsRespondedByManager(@Param("managerId") Long managerId, Pageable pageable);

    /**
     * Find clients with active conversations (messages) for a specific manager (agent)
     * PARIDAD: Rails scope :with_active_conversation
     * Active conversation = has at least one message to/from the agent
     */
    @Query(value = """
            SELECT DISTINCT u.* FROM users u
            INNER JOIN messages m ON (m.sender_id = u.id OR m.recipient_id = u.id)
            WHERE u.manager_id = :managerId
            AND u.role = 0
            AND u.status = 0
            AND (m.sender_id = :managerId OR m.recipient_id = :managerId)
            ORDER BY u.last_message_at DESC NULLS LAST
            """,
            countQuery = """
            SELECT COUNT(DISTINCT u.id) FROM users u
            INNER JOIN messages m ON (m.sender_id = u.id OR m.recipient_id = u.id)
            WHERE u.manager_id = :managerId AND u.role = 0 AND u.status = 0
            AND (m.sender_id = :managerId OR m.recipient_id = :managerId)
            """,
            nativeQuery = true)
    Page<User> findClientsWithActiveConversationByManager(@Param("managerId") Long managerId, Pageable pageable);

    // ==================== PARIDAD RAILS: update_columns equivalents ====================
    // Estos métodos hacen UPDATE directo sin disparar callbacks/listeners (como Rails update_columns)

    /**
     * Update require_response flag directly without callbacks
     * PARIDAD RAILS: User.find(...).update_columns(require_response: true)
     * deferred_require_response_kpi_creation_worker.rb línea 7
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE User u SET u.requireResponse = :value WHERE u.id = :userId")
    void updateRequireResponseColumns(@Param("userId") Long userId, @Param("value") Boolean value);

    /**
     * Update require_response and last_message_at directly without callbacks
     * PARIDAD RAILS: Para cuando se necesita actualizar ambos campos
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE User u SET u.requireResponse = :requireResponse, u.lastMessageAt = :lastMessageAt WHERE u.id = :userId")
    void updateRequireResponseAndLastMessageColumns(
            @Param("userId") Long userId,
            @Param("requireResponse") Boolean requireResponse,
            @Param("lastMessageAt") LocalDateTime lastMessageAt);

    // ==================== RAILS PARITY QUERIES ====================

    /**
     * Count users by client and created date range
     * Used for calculating users_created KPI (contact ratio)
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.client.id = :clientId AND u.createdAt BETWEEN :startDate AND :endDate")
    long countByClientIdAndCreatedAtBetween(
            @Param("clientId") Long clientId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    // ==================== MANAGER ASSIGNMENTS QUERIES ====================

    /**
     * Find clients (standard role) where manager_id is in the given list
     * PARIDAD RAILS: managers#index - subordinates of subordinates
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.manager.id IN :managerIds
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            ORDER BY u.id DESC
            """)
    Page<User> findClientsByManagerIds(@Param("managerIds") List<Long> managerIds, Pageable pageable);

    /**
     * Find clients (standard role) where manager_id is in the given list with search
     * PARIDAD RAILS: managers#index with search filter
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.manager.id IN :managerIds
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            AND (
                LOWER(CONCAT(u.firstName, ' ', COALESCE(u.lastName, ''))) LIKE LOWER(CONCAT('%', :search, '%'))
                OR u.phone LIKE CONCAT('%', :search, '%')
                OR LOWER(COALESCE(u.codigo, '')) LIKE LOWER(CONCAT('%', :search, '%'))
            )
            ORDER BY u.id DESC
            """)
    Page<User> findClientsByManagerIdsWithSearch(
            @Param("managerIds") List<Long> managerIds,
            @Param("search") String search,
            Pageable pageable);

    // ==================== SUPERVISOR CLIENTS FILTER METHODS ====================

    /**
     * Find clients with open tickets for multiple managers (supervisor view)
     * PARIDAD: Rails scope :with_open_tickets for supervisor_clients
     */
    @Query(value = """
            SELECT u.* FROM users u
            WHERE u.manager_id IN :managerIds
            AND u.role = 0
            AND u.status = 0
            AND u.id IN (
                SELECT DISTINCT t.user_id FROM tickets t WHERE t.status = 0
            )
            ORDER BY u.last_message_at DESC NULLS LAST
            """,
            nativeQuery = true)
    Page<User> findClientsWithOpenTicketsByManagerIds(@Param("managerIds") List<Long> managerIds, Pageable pageable);

    /**
     * Find clients without open tickets for multiple managers (supervisor view)
     * PARIDAD: Rails scope :without_open_tickets for supervisor_clients
     */
    @Query(value = """
            SELECT u.* FROM users u
            WHERE u.manager_id IN :managerIds
            AND u.role = 0
            AND u.status = 0
            AND u.id NOT IN (
                SELECT DISTINCT t.user_id FROM tickets t WHERE t.status = 0
            )
            ORDER BY u.last_message_at DESC NULLS LAST
            """,
            nativeQuery = true)
    Page<User> findClientsWithoutOpenTicketsByManagerIds(@Param("managerIds") List<Long> managerIds, Pageable pageable);

    /**
     * Find clients requiring response for multiple managers (supervisor view)
     * PARIDAD: Rails scope :with_unresponded_messages for supervisor_clients
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.manager.id IN :managerIds
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND u.requireResponse = true
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            ORDER BY u.lastMessageAt ASC NULLS LAST
            """)
    Page<User> findClientsRequiringResponseByManagerIds(@Param("managerIds") List<Long> managerIds, Pageable pageable);

    /**
     * Find clients that have been responded to for multiple managers (supervisor view)
     * PARIDAD: Rails scope :responded_messages for supervisor_clients
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.manager.id IN :managerIds
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND (u.requireResponse = false OR u.requireResponse IS NULL)
            AND u.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE
            ORDER BY u.lastMessageAt DESC NULLS LAST
            """)
    Page<User> findClientsRespondedByManagerIds(@Param("managerIds") List<Long> managerIds, Pageable pageable);

    /**
     * Find clients with active conversations for multiple managers (supervisor view)
     * PARIDAD: Rails scope :with_active_conversation — checks if ANY message exists
     * between client and agent (no time limit), using UNION of sender/recipient
     */
    @Query(value = """
            SELECT DISTINCT u.* FROM users u
            WHERE u.manager_id IN :managerIds
            AND u.role = 0
            AND u.status = 0
            AND u.id IN (
                SELECT received_messages.recipient_id FROM messages received_messages
                WHERE received_messages.sender_id IN :managerIds
                UNION
                SELECT sent_messages.sender_id FROM messages sent_messages
                WHERE sent_messages.recipient_id IN :managerIds
            )
            ORDER BY u.last_message_at DESC NULLS LAST
            """,
            countQuery = """
            SELECT COUNT(DISTINCT u.id) FROM users u
            WHERE u.manager_id IN :managerIds
            AND u.role = 0
            AND u.status = 0
            AND u.id IN (
                SELECT received_messages.recipient_id FROM messages received_messages
                WHERE received_messages.sender_id IN :managerIds
                UNION
                SELECT sent_messages.sender_id FROM messages sent_messages
                WHERE sent_messages.recipient_id IN :managerIds
            )
            """,
            nativeQuery = true)
    Page<User> findClientsWithActiveConversationByManagerIds(@Param("managerIds") List<Long> managerIds, Pageable pageable);

    /**
     * Find active conversation clients requiring response (activeOnly + to_respond)
     */
    @Query(value = """
            SELECT DISTINCT u.* FROM users u
            WHERE u.manager_id IN :managerIds
            AND u.role = 0
            AND u.status = 0
            AND u.require_response = true
            AND u.id IN (
                SELECT received_messages.recipient_id FROM messages received_messages
                WHERE received_messages.sender_id IN :managerIds
                UNION
                SELECT sent_messages.sender_id FROM messages sent_messages
                WHERE sent_messages.recipient_id IN :managerIds
            )
            ORDER BY u.last_message_at ASC NULLS LAST
            """,
            countQuery = """
            SELECT COUNT(DISTINCT u.id) FROM users u
            WHERE u.manager_id IN :managerIds
            AND u.role = 0
            AND u.status = 0
            AND u.require_response = true
            AND u.id IN (
                SELECT received_messages.recipient_id FROM messages received_messages
                WHERE received_messages.sender_id IN :managerIds
                UNION
                SELECT sent_messages.sender_id FROM messages sent_messages
                WHERE sent_messages.recipient_id IN :managerIds
            )
            """,
            nativeQuery = true)
    Page<User> findActiveClientsRequiringResponseByManagerIds(@Param("managerIds") List<Long> managerIds, Pageable pageable);

    /**
     * Find active conversation clients already responded (activeOnly + responded)
     */
    @Query(value = """
            SELECT DISTINCT u.* FROM users u
            WHERE u.manager_id IN :managerIds
            AND u.role = 0
            AND u.status = 0
            AND (u.require_response = false OR u.require_response IS NULL)
            AND u.id IN (
                SELECT received_messages.recipient_id FROM messages received_messages
                WHERE received_messages.sender_id IN :managerIds
                UNION
                SELECT sent_messages.sender_id FROM messages sent_messages
                WHERE sent_messages.recipient_id IN :managerIds
            )
            ORDER BY u.last_message_at DESC NULLS LAST
            """,
            countQuery = """
            SELECT COUNT(DISTINCT u.id) FROM users u
            WHERE u.manager_id IN :managerIds
            AND u.role = 0
            AND u.status = 0
            AND (u.require_response = false OR u.require_response IS NULL)
            AND u.id IN (
                SELECT received_messages.recipient_id FROM messages received_messages
                WHERE received_messages.sender_id IN :managerIds
                UNION
                SELECT sent_messages.sender_id FROM messages sent_messages
                WHERE sent_messages.recipient_id IN :managerIds
            )
            """,
            nativeQuery = true)
    Page<User> findActiveClientsRespondedByManagerIds(@Param("managerIds") List<Long> managerIds, Pageable pageable);

    /**
     * Find active conversation clients with open tickets (activeOnly + open tickets)
     */
    @Query(value = """
            SELECT DISTINCT u.* FROM users u
            WHERE u.manager_id IN :managerIds
            AND u.role = 0
            AND u.status = 0
            AND u.id IN (
                SELECT DISTINCT t.user_id FROM tickets t WHERE t.status = 0
            )
            AND u.id IN (
                SELECT received_messages.recipient_id FROM messages received_messages
                WHERE received_messages.sender_id IN :managerIds
                UNION
                SELECT sent_messages.sender_id FROM messages sent_messages
                WHERE sent_messages.recipient_id IN :managerIds
            )
            ORDER BY u.last_message_at DESC NULLS LAST
            """,
            countQuery = """
            SELECT COUNT(DISTINCT u.id) FROM users u
            WHERE u.manager_id IN :managerIds
            AND u.role = 0
            AND u.status = 0
            AND u.id IN (
                SELECT DISTINCT t.user_id FROM tickets t WHERE t.status = 0
            )
            AND u.id IN (
                SELECT received_messages.recipient_id FROM messages received_messages
                WHERE received_messages.sender_id IN :managerIds
                UNION
                SELECT sent_messages.sender_id FROM messages sent_messages
                WHERE sent_messages.recipient_id IN :managerIds
            )
            """,
            nativeQuery = true)
    Page<User> findActiveClientsWithOpenTicketsByManagerIds(@Param("managerIds") List<Long> managerIds, Pageable pageable);

    /**
     * Find active conversation clients without open tickets (activeOnly + closed tickets)
     */
    @Query(value = """
            SELECT DISTINCT u.* FROM users u
            WHERE u.manager_id IN :managerIds
            AND u.role = 0
            AND u.status = 0
            AND u.id NOT IN (
                SELECT DISTINCT t.user_id FROM tickets t WHERE t.status = 0
            )
            AND u.id IN (
                SELECT received_messages.recipient_id FROM messages received_messages
                WHERE received_messages.sender_id IN :managerIds
                UNION
                SELECT sent_messages.sender_id FROM messages sent_messages
                WHERE sent_messages.recipient_id IN :managerIds
            )
            ORDER BY u.last_message_at DESC NULLS LAST
            """,
            countQuery = """
            SELECT COUNT(DISTINCT u.id) FROM users u
            WHERE u.manager_id IN :managerIds
            AND u.role = 0
            AND u.status = 0
            AND u.id NOT IN (
                SELECT DISTINCT t.user_id FROM tickets t WHERE t.status = 0
            )
            AND u.id IN (
                SELECT received_messages.recipient_id FROM messages received_messages
                WHERE received_messages.sender_id IN :managerIds
                UNION
                SELECT sent_messages.sender_id FROM messages sent_messages
                WHERE sent_messages.recipient_id IN :managerIds
            )
            """,
            nativeQuery = true)
    Page<User> findActiveClientsWithoutOpenTicketsByManagerIds(@Param("managerIds") List<Long> managerIds, Pageable pageable);

    // ==================== USERS INDEX QUERIES (Rails UsersController#index) ====================

    /**
     * Find all standard users by client ID (for manager_level_4)
     * PARIDAD Rails: User.current_client(@current_client).includes([:manager]).where(role: 'standard')
     */
    @Query("""
            SELECT u FROM User u
            LEFT JOIN FETCH u.manager
            WHERE u.client.id = :clientId
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            """)
    Page<User> findStandardUsersByClientId(@Param("clientId") Long clientId, Pageable pageable);

    /**
     * Search all users by client (for super_admin, admin, staff)
     * PARIDAD Rails: users_scope.includes([:client, :manager]).datatable_search
     */
    @Query("""
            SELECT u FROM User u
            LEFT JOIN FETCH u.manager
            WHERE u.client.id = :clientId
            AND (
                LOWER(CONCAT(u.firstName, ' ', COALESCE(u.lastName, ''))) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
                OR u.phone LIKE CONCAT('%', :search, '%')
            )
            """)
    Page<User> searchUsersByClient(@Param("clientId") Long clientId, @Param("search") String search, Pageable pageable);

    /**
     * Search standard users by client (for manager_level_4)
     * PARIDAD Rails: User.where(role: 'standard').includes([:manager]).datatable_search
     */
    @Query("""
            SELECT u FROM User u
            LEFT JOIN FETCH u.manager
            WHERE u.client.id = :clientId
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.STANDARD
            AND (
                LOWER(CONCAT(u.firstName, ' ', COALESCE(u.lastName, ''))) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
                OR u.phone LIKE CONCAT('%', :search, '%')
            )
            """)
    Page<User> searchStandardUsersByClient(@Param("clientId") Long clientId, @Param("search") String search, Pageable pageable);

    /**
     * Find subordinates of a manager (for manager_level_1,2,3)
     * PARIDAD Rails: current_user.subordinates.includes([:manager])
     */
    @Query("""
            SELECT u FROM User u
            LEFT JOIN FETCH u.manager
            WHERE u.manager.id = :managerId
            """)
    Page<User> findByManager_IdOrderByIdDesc(@Param("managerId") Long managerId, Pageable pageable);

    /**
     * Search subordinates of a manager (for manager_level_1,2,3)
     * PARIDAD Rails: current_user.subordinates.includes([:manager]).datatable_search
     */
    @Query("""
            SELECT u FROM User u
            LEFT JOIN FETCH u.manager
            WHERE u.manager.id = :managerId
            AND (
                LOWER(CONCAT(u.firstName, ' ', COALESCE(u.lastName, ''))) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
                OR u.phone LIKE CONCAT('%', :search, '%')
            )
            """)
    Page<User> searchSubordinates(@Param("managerId") Long managerId, @Param("search") String search, Pageable pageable);
}
