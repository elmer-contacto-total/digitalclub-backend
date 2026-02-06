package com.digitalgroup.holape.domain.media.repository;

import com.digitalgroup.holape.domain.media.entity.MediaAuditLog;
import com.digitalgroup.holape.domain.media.enums.MediaAuditAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MediaAuditLogRepository extends JpaRepository<MediaAuditLog, Long> {

    Page<MediaAuditLog> findByUserFingerprintOrderByEventTimestampDesc(
            String userFingerprint, Pageable pageable);

    Page<MediaAuditLog> findByActionOrderByEventTimestampDesc(
            MediaAuditAction action, Pageable pageable);

    Page<MediaAuditLog> findByUserFingerprintAndActionOrderByEventTimestampDesc(
            String userFingerprint, MediaAuditAction action, Pageable pageable);

    Page<MediaAuditLog> findByEventTimestampBetweenOrderByEventTimestampDesc(
            LocalDateTime from, LocalDateTime to, Pageable pageable);

    List<MediaAuditLog> findByEventTimestampBetweenOrderByEventTimestampDesc(
            LocalDateTime from, LocalDateTime to);

    long countByUserFingerprint(String userFingerprint);

    long countByAction(MediaAuditAction action);

    Page<MediaAuditLog> findAllByOrderByEventTimestampDesc(Pageable pageable);

    // Agent-scoped queries for supervisor view (with eager fetch of agent/clientUser)

    @EntityGraph(attributePaths = {"agent", "clientUser"})
    Page<MediaAuditLog> findByAgentIdInOrderByEventTimestampDesc(List<Long> agentIds, Pageable pageable);

    @EntityGraph(attributePaths = {"agent", "clientUser"})
    Page<MediaAuditLog> findByAgentIdInAndActionOrderByEventTimestampDesc(List<Long> agentIds, MediaAuditAction action, Pageable pageable);

    @EntityGraph(attributePaths = {"agent", "clientUser"})
    Page<MediaAuditLog> findByAgentIdInAndEventTimestampBetweenOrderByEventTimestampDesc(
            List<Long> agentIds, LocalDateTime from, LocalDateTime to, Pageable pageable);

    @EntityGraph(attributePaths = {"agent", "clientUser"})
    Page<MediaAuditLog> findByAgentIdInAndActionAndEventTimestampBetweenOrderByEventTimestampDesc(
            List<Long> agentIds, MediaAuditAction action, LocalDateTime from, LocalDateTime to, Pageable pageable);

    @EntityGraph(attributePaths = {"agent", "clientUser"})
    Page<MediaAuditLog> findByActionAndEventTimestampBetweenOrderByEventTimestampDesc(
            MediaAuditAction action, LocalDateTime from, LocalDateTime to, Pageable pageable);

    // Overloaded versions with EntityGraph for admin view (no agent scoping)

    @EntityGraph(attributePaths = {"agent", "clientUser"})
    Page<MediaAuditLog> findWithGraphAllByOrderByEventTimestampDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"agent", "clientUser"})
    Page<MediaAuditLog> findWithGraphByActionOrderByEventTimestampDesc(MediaAuditAction action, Pageable pageable);

    @EntityGraph(attributePaths = {"agent", "clientUser"})
    Page<MediaAuditLog> findWithGraphByEventTimestampBetweenOrderByEventTimestampDesc(
            LocalDateTime from, LocalDateTime to, Pageable pageable);

    long countByAgentIdIn(List<Long> agentIds);

    long countByAgentIdInAndAction(List<Long> agentIds, MediaAuditAction action);
}
