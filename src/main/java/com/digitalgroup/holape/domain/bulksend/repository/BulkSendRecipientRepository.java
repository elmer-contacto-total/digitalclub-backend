package com.digitalgroup.holape.domain.bulksend.repository;

import com.digitalgroup.holape.domain.bulksend.entity.BulkSendRecipient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BulkSendRecipientRepository extends JpaRepository<BulkSendRecipient, Long> {

    Page<BulkSendRecipient> findByBulkSendId(Long bulkSendId, Pageable pageable);

    List<BulkSendRecipient> findByBulkSendIdAndStatus(Long bulkSendId, String status);

    Optional<BulkSendRecipient> findFirstByBulkSendIdAndStatusOrderByIdAsc(Long bulkSendId, String status);

    long countByBulkSendIdAndStatus(Long bulkSendId, String status);
}
