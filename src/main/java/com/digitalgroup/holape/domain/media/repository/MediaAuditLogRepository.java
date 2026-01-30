package com.digitalgroup.holape.domain.media.repository;

import com.digitalgroup.holape.domain.media.entity.MediaAuditLog;
import com.digitalgroup.holape.domain.media.enums.MediaAuditAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
}
