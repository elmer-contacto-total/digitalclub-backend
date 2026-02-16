package com.digitalgroup.holape.domain.bulksend.repository;

import com.digitalgroup.holape.domain.bulksend.entity.BulkSend;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BulkSendRepository extends JpaRepository<BulkSend, Long> {

    Page<BulkSend> findByClientIdOrderByCreatedAtDesc(Long clientId, Pageable pageable);

    Page<BulkSend> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<BulkSend> findByClientIdAndStatusOrderByCreatedAtDesc(Long clientId, String status, Pageable pageable);

    List<BulkSend> findByStatus(String status);

    List<BulkSend> findByClientIdAndStatus(Long clientId, String status);

    @Query("SELECT COUNT(b) FROM BulkSend b WHERE b.user.id = :userId AND b.createdAt >= :since AND b.status IN ('PROCESSING', 'COMPLETED')")
    long countRecentByUser(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    @Query("SELECT COALESCE(SUM(b.sentCount), 0) FROM BulkSend b WHERE b.user.id = :userId AND b.createdAt >= :since")
    long sumSentByUserSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    // --- Supervisor-scoped queries (own sends + agents' sends) ---

    @Query(value = """
            SELECT b.* FROM bulk_sends b
            WHERE b.user_id = :supervisorId
               OR b.assigned_agent_id IN (
                   SELECT u.id FROM users u WHERE u.manager_id = :supervisorId
               )
            ORDER BY b.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(b.id) FROM bulk_sends b
            WHERE b.user_id = :supervisorId
               OR b.assigned_agent_id IN (
                   SELECT u.id FROM users u WHERE u.manager_id = :supervisorId
               )
            """,
            nativeQuery = true)
    Page<BulkSend> findBySupervisorScope(@Param("supervisorId") Long supervisorId, Pageable pageable);

    @Query(value = """
            SELECT b.* FROM bulk_sends b
            WHERE (b.user_id = :supervisorId
               OR b.assigned_agent_id IN (
                   SELECT u.id FROM users u WHERE u.manager_id = :supervisorId
               ))
            AND b.status = :status
            ORDER BY b.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(b.id) FROM bulk_sends b
            WHERE (b.user_id = :supervisorId
               OR b.assigned_agent_id IN (
                   SELECT u.id FROM users u WHERE u.manager_id = :supervisorId
               ))
            AND b.status = :status
            """,
            nativeQuery = true)
    Page<BulkSend> findBySupervisorScopeAndStatus(
            @Param("supervisorId") Long supervisorId,
            @Param("status") String status,
            Pageable pageable);

    // --- Assigned agent queries ---

    Page<BulkSend> findByAssignedAgentIdOrderByCreatedAtDesc(Long agentId, Pageable pageable);

    Page<BulkSend> findByAssignedAgentIdAndStatusOrderByCreatedAtDesc(Long agentId, String status, Pageable pageable);

    @Query("SELECT COALESCE(SUM(b.sentCount), 0) FROM BulkSend b WHERE b.assignedAgent.id = :agentId AND b.createdAt >= :since")
    long sumSentByAssignedAgentSince(@Param("agentId") Long agentId, @Param("since") LocalDateTime since);
}
