package com.digitalgroup.holape.domain.bulksend.repository;

import com.digitalgroup.holape.domain.bulksend.entity.BulkSendRecipient;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BulkSendRecipientRepository extends JpaRepository<BulkSendRecipient, Long> {

    Page<BulkSendRecipient> findByBulkSendId(Long bulkSendId, Pageable pageable);

    List<BulkSendRecipient> findByBulkSendIdAndStatus(Long bulkSendId, String status);

    Optional<BulkSendRecipient> findFirstByBulkSendIdAndStatusOrderByIdAsc(Long bulkSendId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM BulkSendRecipient r WHERE r.bulkSend.id = :bulkSendId AND r.status = 'PENDING' ORDER BY r.id ASC LIMIT 1")
    Optional<BulkSendRecipient> findNextPendingRecipientForUpdate(@Param("bulkSendId") Long bulkSendId);

    long countByBulkSendIdAndStatus(Long bulkSendId, String status);

    @Modifying
    @Query("UPDATE BulkSendRecipient r SET r.status = 'PENDING' WHERE r.bulkSend.id = :bulkSendId AND r.status = 'IN_PROGRESS'")
    void resetInProgressToPending(@Param("bulkSendId") Long bulkSendId);
}
