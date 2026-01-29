package com.digitalgroup.holape.domain.kpi.repository;

import com.digitalgroup.holape.domain.common.enums.KpiType;
import com.digitalgroup.holape.domain.kpi.entity.Kpi;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface KpiRepository extends JpaRepository<Kpi, Long> {

    List<Kpi> findByClientIdAndKpiType(Long clientId, KpiType kpiType);

    List<Kpi> findByUserIdAndKpiType(Long userId, KpiType kpiType);

    List<Kpi> findByTicketId(Long ticketId);

    @Query("SELECT k FROM Kpi k WHERE k.client.id = :clientId AND k.kpiType = :kpiType " +
           "AND k.createdAt BETWEEN :startDate AND :endDate")
    List<Kpi> findByClientKpiTypeAndDateRange(
            @Param("clientId") Long clientId,
            @Param("kpiType") KpiType kpiType,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    @Query("SELECT k FROM Kpi k WHERE k.user.id = :userId AND k.kpiType = :kpiType " +
           "AND k.createdAt BETWEEN :startDate AND :endDate")
    List<Kpi> findByUserKpiTypeAndDateRange(
            @Param("userId") Long userId,
            @Param("kpiType") KpiType kpiType,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    @Query("SELECT SUM(k.value) FROM Kpi k WHERE k.client.id = :clientId AND k.kpiType = :kpiType " +
           "AND k.createdAt BETWEEN :startDate AND :endDate")
    Long sumValueByClientKpiTypeAndDateRange(
            @Param("clientId") Long clientId,
            @Param("kpiType") KpiType kpiType,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    @Query("SELECT AVG(k.value) FROM Kpi k WHERE k.client.id = :clientId AND k.kpiType = :kpiType " +
           "AND k.createdAt BETWEEN :startDate AND :endDate")
    Double avgValueByClientKpiTypeAndDateRange(
            @Param("clientId") Long clientId,
            @Param("kpiType") KpiType kpiType,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    @Query("SELECT COUNT(k) FROM Kpi k WHERE k.client.id = :clientId AND k.kpiType = :kpiType " +
           "AND k.createdAt BETWEEN :startDate AND :endDate")
    long countByClientKpiTypeAndDateRange(
            @Param("clientId") Long clientId,
            @Param("kpiType") KpiType kpiType,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    void deleteByTicketId(Long ticketId);

    // Pageable methods for admin controller
    @Query("SELECT k FROM Kpi k WHERE k.client.id = :clientId AND k.createdAt BETWEEN :startDate AND :endDate")
    Page<Kpi> findByClientIdAndCreatedAtBetween(
            @Param("clientId") Long clientId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable);

    @Query("SELECT k FROM Kpi k WHERE k.client.id = :clientId AND k.kpiType = :kpiType AND k.createdAt BETWEEN :startDate AND :endDate")
    Page<Kpi> findByClientIdAndKpiTypeAndCreatedAtBetween(
            @Param("clientId") Long clientId,
            @Param("kpiType") KpiType kpiType,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable);

    @Query("SELECT k FROM Kpi k WHERE k.user.id = :userId AND k.createdAt BETWEEN :startDate AND :endDate")
    Page<Kpi> findByUserIdAndCreatedAtBetween(
            @Param("userId") Long userId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable);

    @Query("SELECT k FROM Kpi k WHERE k.user.id = :userId AND k.kpiType = :kpiType AND k.createdAt BETWEEN :startDate AND :endDate")
    Page<Kpi> findByUserIdAndKpiTypeAndCreatedAtBetween(
            @Param("userId") Long userId,
            @Param("kpiType") KpiType kpiType,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable);

    // Count by kpi type for user
    @Query("SELECT k.kpiType, COUNT(k) FROM Kpi k WHERE k.user.id = :userId AND k.createdAt BETWEEN :startDate AND :endDate GROUP BY k.kpiType")
    List<Object[]> countByUserGroupedByType(
            @Param("userId") Long userId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    // Agent ranking query
    @Query("""
            SELECT k.user.id, k.user.firstName, k.user.lastName, COUNT(k), SUM(CASE WHEN k.kpiType = com.digitalgroup.holape.domain.common.enums.KpiType.CLOSED_TICKET THEN 1 ELSE 0 END)
            FROM Kpi k
            WHERE k.client.id = :clientId
            AND k.createdAt BETWEEN :startDate AND :endDate
            AND k.user IS NOT NULL
            GROUP BY k.user.id, k.user.firstName, k.user.lastName
            ORDER BY COUNT(k) DESC
            """)
    List<Object[]> findAgentRanking(
            @Param("clientId") Long clientId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable);

    // Find all KPIs by client and date range (for export)
    @Query("SELECT k FROM Kpi k WHERE k.client.id = :clientId AND k.createdAt BETWEEN :startDate AND :endDate ORDER BY k.createdAt DESC")
    List<Kpi> findByClientAndDateRange(
            @Param("clientId") Long clientId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    // Get KPI summary grouped by type (for dashboard export)
    @Query("""
            SELECT k.kpiType, SUM(k.value)
            FROM Kpi k
            WHERE k.client.id = :clientId
            AND k.createdAt BETWEEN :startDate AND :endDate
            GROUP BY k.kpiType
            ORDER BY k.kpiType
            """)
    List<Object[]> getKpiSummaryByClient(
            @Param("clientId") Long clientId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    // Get agent performance metrics (for agent report export)
    @Query("""
            SELECT
                u.id,
                CONCAT(u.firstName, ' ', u.lastName),
                COUNT(DISTINCT k.ticket.id),
                SUM(CASE WHEN k.kpiType = com.digitalgroup.holape.domain.common.enums.KpiType.CLOSED_TICKET THEN 1 ELSE 0 END),
                AVG(CASE WHEN k.kpiType = com.digitalgroup.holape.domain.common.enums.KpiType.FIRST_RESPONSE_TIME THEN k.value ELSE NULL END),
                SUM(CASE WHEN k.kpiType = com.digitalgroup.holape.domain.common.enums.KpiType.SENT_MESSAGE THEN k.value ELSE 0 END),
                SUM(CASE WHEN k.kpiType = com.digitalgroup.holape.domain.common.enums.KpiType.FIRST_RESPONSE_TIME THEN 1 ELSE 0 END)
            FROM Kpi k
            JOIN k.user u
            WHERE k.client.id = :clientId
            AND k.createdAt BETWEEN :startDate AND :endDate
            AND u.role = com.digitalgroup.holape.domain.common.enums.UserRole.AGENT
            GROUP BY u.id, u.firstName, u.lastName
            ORDER BY COUNT(DISTINCT k.ticket.id) DESC
            """)
    List<Object[]> getAgentPerformance(
            @Param("clientId") Long clientId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    // ==================== MANAGER MIGRATION QUERIES ====================

    // Update user_id for all KPIs from old agent to new agent
    @Modifying
    @Query("UPDATE Kpi k SET k.user.id = :newAgentId WHERE k.user.id = :oldAgentId")
    int updateUserForAllKpis(@Param("oldAgentId") Long oldAgentId, @Param("newAgentId") Long newAgentId);

    /**
     * Update user_id for KPIs filtered by ticket_id
     * PARIDAD RAILS: user.rb líneas 283-286
     * Kpi.where(kpi_type: "new_ticket", user_id: old_agent_id)
     *    .where("data_hash @> ?", { ticket_id: ticket.id }.to_json)
     *    .update_all(user_id: new_manager_id)
     *
     * Note: Uses native PostgreSQL JSONB containment operator for data_hash filtering
     */
    @Modifying
    @Query(value = "UPDATE kpis SET user_id = :newAgentId " +
                   "WHERE user_id = :oldAgentId " +
                   "AND kpi_type = :kpiType " +
                   "AND data_hash @> CAST(:ticketIdJson AS jsonb)",
           nativeQuery = true)
    int updateUserForKpisByTicketId(
            @Param("oldAgentId") Long oldAgentId,
            @Param("newAgentId") Long newAgentId,
            @Param("kpiType") int kpiType,
            @Param("ticketIdJson") String ticketIdJson);

    // Count KPIs by user
    long countByUserId(Long userId);

    // Find KPIs by user (for migration verification)
    List<Kpi> findByUserId(Long userId);

    // ==================== SCHEDULED KPI CALCULATION QUERIES ====================

    // Count KPIs grouped by type for a date range
    @Query("""
            SELECT k.kpiType, COUNT(k)
            FROM Kpi k
            WHERE k.client.id = :clientId
            AND k.createdAt BETWEEN :startDate AND :endDate
            GROUP BY k.kpiType
            """)
    List<Object[]> countGroupedByKpiType(
            @Param("clientId") Long clientId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    // Count by client, kpi type, and date range
    @Query("SELECT COUNT(k) FROM Kpi k WHERE k.client.id = :clientId AND k.kpiType = :kpiType AND k.createdAt BETWEEN :startDate AND :endDate")
    long countByClientIdAndKpiTypeAndCreatedAtBetween(
            @Param("clientId") Long clientId,
            @Param("kpiType") KpiType kpiType,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    // ==================== RAILS PARITY QUERIES ====================

    /**
     * Count KPIs by multiple users, kpi type, and date range
     * Used for calculating overall KPIs for a list of agents
     */
    @Query("SELECT COUNT(k) FROM Kpi k WHERE k.user.id IN :userIds AND k.kpiType = :kpiType " +
           "AND k.createdAt BETWEEN :startDate AND :endDate")
    long countByUsersKpiTypeAndDateRange(
            @Param("userIds") List<Long> userIds,
            @Param("kpiType") KpiType kpiType,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    /**
     * Average value by multiple users, kpi type, and date range
     */
    @Query("SELECT AVG(k.value) FROM Kpi k WHERE k.user.id IN :userIds AND k.kpiType = :kpiType " +
           "AND k.createdAt BETWEEN :startDate AND :endDate")
    Double avgValueByUsersKpiTypeAndDateRange(
            @Param("userIds") List<Long> userIds,
            @Param("kpiType") KpiType kpiType,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    /**
     * Count distinct users (for unique clients contacted calculation)
     */
    @Query("SELECT COUNT(DISTINCT k.user.id) FROM Kpi k WHERE k.user.id IN :userIds AND k.kpiType = :kpiType " +
           "AND k.createdAt BETWEEN :startDate AND :endDate")
    long countDistinctUsersByKpiTypeAndDateRange(
            @Param("userIds") List<Long> userIds,
            @Param("kpiType") KpiType kpiType,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    /**
     * Count KPIs by single user, kpi type, and date range
     */
    @Query("SELECT COUNT(k) FROM Kpi k WHERE k.user.id = :userId AND k.kpiType = :kpiType " +
           "AND k.createdAt BETWEEN :startDate AND :endDate")
    long countByUserKpiTypeAndDateRange(
            @Param("userId") Long userId,
            @Param("kpiType") KpiType kpiType,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);
}
