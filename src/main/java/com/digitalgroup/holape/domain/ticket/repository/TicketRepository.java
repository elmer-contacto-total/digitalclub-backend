package com.digitalgroup.holape.domain.ticket.repository;

import com.digitalgroup.holape.domain.common.enums.TicketStatus;
import com.digitalgroup.holape.domain.ticket.entity.Ticket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {

    Page<Ticket> findByAgentIdAndStatus(Long agentId, TicketStatus status, Pageable pageable);

    Page<Ticket> findByUserIdAndStatus(Long userId, TicketStatus status, Pageable pageable);

    // Find first open ticket by user (for CRM panel - electron_clients)
    Optional<Ticket> findFirstByUserIdAndStatusOrderByCreatedAtDesc(Long userId, TicketStatus status);

    // Check if user has open ticket (for CRM panel - electron_clients)
    boolean existsByUserIdAndStatus(Long userId, TicketStatus status);

    // Find all tickets by status (for auto-close job)
    List<Ticket> findByStatus(TicketStatus status);

    List<Ticket> findByAgentId(Long agentId);

    @Query("SELECT t FROM Ticket t WHERE t.user.id = :userId AND t.agent.id = :agentId AND t.status = :status ORDER BY t.createdAt DESC")
    Optional<Ticket> findOpenTicketBetweenUsers(
            @Param("userId") Long userId,
            @Param("agentId") Long agentId,
            @Param("status") TicketStatus status);

    /**
     * Find last open ticket between two users (bidirectional search)
     * PARIDAD RAILS: create_or_assign_to_ticket_worker.rb línea 7
     * Ticket.where(user_id: sender_id, agent_id: recipient_id, status: 'open')
     *   .or(Ticket.where(user_id: recipient_id, agent_id: sender_id, status: 'open')).last
     */
    @Query("""
            SELECT t FROM Ticket t
            WHERE ((t.user.id = :senderId AND t.agent.id = :recipientId)
                OR (t.user.id = :recipientId AND t.agent.id = :senderId))
            AND t.status = :status
            ORDER BY t.createdAt DESC
            """)
    Optional<Ticket> findOpenTicketBidirectional(
            @Param("senderId") Long senderId,
            @Param("recipientId") Long recipientId,
            @Param("status") TicketStatus status);

    /**
     * Find last closed ticket between two users (bidirectional search)
     * PARIDAD RAILS: create_or_assign_to_ticket_worker.rb línea 8
     * Ticket.where(user_id: sender_id, agent_id: recipient_id, status: 'closed')
     *   .or(Ticket.where(user_id: recipient_id, agent_id: sender_id, status: 'closed')).last
     */
    @Query("""
            SELECT t FROM Ticket t
            WHERE ((t.user.id = :senderId AND t.agent.id = :recipientId)
                OR (t.user.id = :recipientId AND t.agent.id = :senderId))
            AND t.status = :status
            ORDER BY t.closedAt DESC NULLS LAST, t.updatedAt DESC
            """)
    Optional<Ticket> findClosedTicketBidirectional(
            @Param("senderId") Long senderId,
            @Param("recipientId") Long recipientId,
            @Param("status") TicketStatus status);

    /**
     * Find last closed ticket between user and agent (unidirectional - for MessageService)
     * PARIDAD RAILS: message.rb línea 111 - Ticket.where(...status: 'closed').last
     */
    @Query("""
            SELECT t FROM Ticket t
            WHERE ((t.user.id = :userId AND t.agent.id = :agentId)
                OR (t.user.id = :agentId AND t.agent.id = :userId))
            AND t.status = :status
            ORDER BY t.closedAt DESC NULLS LAST, t.updatedAt DESC
            """)
    Optional<Ticket> findLastClosedTicketBetweenUsers(
            @Param("userId") Long userId,
            @Param("agentId") Long agentId,
            @Param("status") TicketStatus status);

    @Query("SELECT t FROM Ticket t WHERE t.user.id = :userId AND t.agent.id = :agentId ORDER BY t.createdAt DESC")
    List<Ticket> findTicketsBetweenUsers(
            @Param("userId") Long userId,
            @Param("agentId") Long agentId,
            Pageable pageable);

    @Query("SELECT t FROM Ticket t WHERE t.status = com.digitalgroup.holape.domain.common.enums.TicketStatus.OPEN AND t.updatedAt < :cutoffTime")
    List<Ticket> findExpiredOpenTickets(@Param("cutoffTime") LocalDateTime cutoffTime);

    @Query("SELECT t FROM Ticket t WHERE t.agent.client.id = :clientId AND t.status = :status")
    Page<Ticket> findByClientAndStatus(
            @Param("clientId") Long clientId,
            @Param("status") TicketStatus status,
            Pageable pageable);

    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.agent.id = :agentId AND t.status = :status")
    long countByAgentAndStatus(@Param("agentId") Long agentId, @Param("status") TicketStatus status);

    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.agent.client.id = :clientId AND t.status = :status AND t.createdAt BETWEEN :startDate AND :endDate")
    long countByClientStatusAndDateRange(
            @Param("clientId") Long clientId,
            @Param("status") TicketStatus status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    // Find expired tickets by client for auto-close
    @Query("""
            SELECT t FROM Ticket t
            WHERE t.agent.client.id = :clientId
            AND t.status = com.digitalgroup.holape.domain.common.enums.TicketStatus.OPEN
            AND t.updatedAt < :threshold
            ORDER BY t.updatedAt ASC
            """)
    List<Ticket> findExpiredTickets(
            @Param("clientId") Long clientId,
            @Param("threshold") LocalDateTime threshold);

    // Find tickets about to expire (for warning notifications)
    @Query("""
            SELECT t FROM Ticket t
            WHERE t.agent.client.id = :clientId
            AND t.status = com.digitalgroup.holape.domain.common.enums.TicketStatus.OPEN
            AND t.updatedAt < :warningThreshold
            AND t.updatedAt >= :closeThreshold
            ORDER BY t.updatedAt ASC
            """)
    List<Ticket> findTicketsAboutToExpire(
            @Param("clientId") Long clientId,
            @Param("warningThreshold") LocalDateTime warningThreshold,
            @Param("closeThreshold") LocalDateTime closeThreshold);

    // Find all open tickets for a client
    @Query("SELECT t FROM Ticket t WHERE t.agent.client.id = :clientId AND t.status = com.digitalgroup.holape.domain.common.enums.TicketStatus.OPEN")
    List<Ticket> findOpenTicketsByClient(@Param("clientId") Long clientId);

    // Find tickets for export
    @Query("""
            SELECT t FROM Ticket t
            WHERE t.agent.client.id = :clientId
            AND t.createdAt BETWEEN :startDate AND :endDate
            ORDER BY t.createdAt DESC
            """)
    List<Ticket> findForExport(
            @Param("clientId") Long clientId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    // Find tickets requiring response (for RequireResponseAlertJob)
    // PARIDAD RAILS: requireResponseAt no existe en schema, usar lastMessageAt
    @Query("""
            SELECT t FROM Ticket t
            WHERE t.agent.client.id = :clientId
            AND t.status = com.digitalgroup.holape.domain.common.enums.TicketStatus.OPEN
            AND t.user.requireResponse = true
            AND t.user.lastMessageAt < :threshold
            ORDER BY t.user.lastMessageAt ASC
            """)
    List<Ticket> findTicketsRequiringResponse(
            @Param("clientId") Long clientId,
            @Param("threshold") LocalDateTime threshold);

    // Find all tickets by client for dashboard
    Page<Ticket> findByAgentClientId(Long clientId, Pageable pageable);

    // Count open tickets by client
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.agent.client.id = :clientId AND t.status = com.digitalgroup.holape.domain.common.enums.TicketStatus.OPEN")
    long countOpenByClient(@Param("clientId") Long clientId);

    // Count closed tickets by client
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.agent.client.id = :clientId AND t.status = com.digitalgroup.holape.domain.common.enums.TicketStatus.CLOSED")
    long countClosedByClient(@Param("clientId") Long clientId);

    // ==================== MANAGER MIGRATION QUERIES ====================

    // Update agent_id for all open tickets from old agent to new agent
    @Modifying
    @Query("UPDATE Ticket t SET t.agent.id = :newAgentId WHERE t.agent.id = :oldAgentId AND t.status = com.digitalgroup.holape.domain.common.enums.TicketStatus.OPEN")
    int updateAgentForOpenTickets(@Param("oldAgentId") Long oldAgentId, @Param("newAgentId") Long newAgentId);

    // Count open tickets by agent
    long countByAgentIdAndStatus(Long agentId, TicketStatus status);

    // Find open tickets by agent (for migration verification)
    List<Ticket> findByAgentIdAndStatus(Long agentId, TicketStatus status);

    // ==================== TICKET CLOSE PARITY ====================

    /**
     * Close all open tickets for a user
     * PARIDAD RAILS: Ticket.where(user_id: ticket.user_id, status: 'open')
     *   .update_all(status: 'closed', closed_at: Time.now, close_type: close_type)
     */
    @Modifying
    @Query("UPDATE Ticket t SET t.status = com.digitalgroup.holape.domain.common.enums.TicketStatus.CLOSED, " +
           "t.closedAt = CURRENT_TIMESTAMP, t.closeType = :closeType " +
           "WHERE t.user.id = :userId AND t.status = com.digitalgroup.holape.domain.common.enums.TicketStatus.OPEN")
    void closeAllOpenByUserId(@Param("userId") Long userId, @Param("closeType") String closeType);

    // ==================== RAILS PARITY QUERIES ====================

    /**
     * Count tickets by multiple agents and status
     * Used for calculating open_cases KPI for a list of agents
     */
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.agent.id IN :agentIds AND t.status = :status")
    long countByAgentIdInAndStatus(
            @Param("agentIds") List<Long> agentIds,
            @Param("status") TicketStatus status);

    /**
     * Count tickets by multiple agents, status, and date range
     * Used for calculating open_cases KPI filtered by period (Rails parity)
     */
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.agent.id IN :agentIds AND t.status = :status " +
           "AND t.createdAt BETWEEN :startDate AND :endDate")
    long countByAgentIdInAndStatusAndCreatedAtBetween(
            @Param("agentIds") List<Long> agentIds,
            @Param("status") TicketStatus status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    /**
     * Count tickets by agent, status, and date range
     * Used for calculating open cases in a specific period
     */
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.agent.id = :agentId AND t.status = :status " +
           "AND t.createdAt BETWEEN :startDate AND :endDate")
    long countByAgentIdAndStatusAndCreatedAtBetween(
            @Param("agentId") Long agentId,
            @Param("status") TicketStatus status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    // ==================== FILTERED QUERIES (Angular frontend support) ====================

    /**
     * Find tickets with advanced filters (paginated)
     * Supports: agentId, search (subject/phone/name), date range
     */
    @Query("""
            SELECT t FROM Ticket t WHERE t.agent.client.id = :clientId AND t.status = :status
            AND (:agentId IS NULL OR t.agent.id = :agentId)
            AND (:search IS NULL OR LOWER(t.subject) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(t.user.phone) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(t.user.firstName) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(t.user.lastName) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (:startDate IS NULL OR t.createdAt >= :startDate)
            AND (:endDate IS NULL OR t.createdAt <= :endDate)
            """)
    Page<Ticket> findTicketsFiltered(
            @Param("clientId") Long clientId,
            @Param("status") TicketStatus status,
            @Param("agentId") Long agentId,
            @Param("search") String search,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable);

    /**
     * Find ticket IDs for export with filters (returns IDs only, no full entity load)
     */
    @Query("""
            SELECT t.id FROM Ticket t WHERE t.agent.client.id = :clientId
            AND (CAST(:status AS string) IS NULL OR t.status = :status)
            AND (:agentId IS NULL OR t.agent.id = :agentId)
            AND (CAST(:startDate AS timestamp) IS NULL OR t.createdAt >= :startDate)
            AND (CAST(:endDate AS timestamp) IS NULL OR t.createdAt <= :endDate)
            ORDER BY t.createdAt DESC
            """)
    List<Long> findTicketIdsForExport(
            @Param("clientId") Long clientId,
            @Param("status") TicketStatus status,
            @Param("agentId") Long agentId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    /**
     * Batch-load tickets with user and agent eagerly fetched (for export)
     */
    @Query("""
            SELECT t FROM Ticket t
            LEFT JOIN FETCH t.user
            LEFT JOIN FETCH t.agent
            WHERE t.id IN :ids
            """)
    List<Ticket> findAllByIdWithUserAndAgent(@Param("ids") List<Long> ids);
}
