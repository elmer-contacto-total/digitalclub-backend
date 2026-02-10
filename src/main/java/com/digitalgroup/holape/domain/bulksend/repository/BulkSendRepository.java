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

    // --- Assigned agent queries ---

    Page<BulkSend> findByAssignedAgentIdOrderByCreatedAtDesc(Long agentId, Pageable pageable);

    Page<BulkSend> findByAssignedAgentIdAndStatusOrderByCreatedAtDesc(Long agentId, String status, Pageable pageable);

    @Query("SELECT COALESCE(SUM(b.sentCount), 0) FROM BulkSend b WHERE b.assignedAgent.id = :agentId AND b.createdAt >= :since")
    long sumSentByAssignedAgentSince(@Param("agentId") Long agentId, @Param("since") LocalDateTime since);
}
