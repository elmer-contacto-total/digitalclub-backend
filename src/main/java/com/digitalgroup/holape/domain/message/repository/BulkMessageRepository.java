package com.digitalgroup.holape.domain.message.repository;

import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.message.entity.BulkMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BulkMessageRepository extends JpaRepository<BulkMessage, Long> {

    Page<BulkMessage> findByClientId(Long clientId, Pageable pageable);

    Page<BulkMessage> findByClientIdAndStatus(Long clientId, Status status, Pageable pageable);

    List<BulkMessage> findByUserId(Long userId);

    Page<BulkMessage> findByUserId(Long userId, Pageable pageable);

    long countByClientId(Long clientId);
}
