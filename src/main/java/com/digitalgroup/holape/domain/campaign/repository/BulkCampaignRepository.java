package com.digitalgroup.holape.domain.campaign.repository;

import com.digitalgroup.holape.domain.campaign.entity.BulkCampaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BulkCampaignRepository extends JpaRepository<BulkCampaign, Long> {

    Page<BulkCampaign> findByClientIdOrderByCreatedAtDesc(Long clientId, Pageable pageable);

    Page<BulkCampaign> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<BulkCampaign> findByClientIdAndStatusOrderByCreatedAtDesc(Long clientId, String status, Pageable pageable);

    List<BulkCampaign> findByStatus(String status);

    List<BulkCampaign> findByClientIdAndStatus(Long clientId, String status);

    @Query("SELECT COUNT(c) FROM BulkCampaign c WHERE c.user.id = :userId AND c.createdAt >= :since AND c.status IN ('PROCESSING', 'COMPLETED')")
    long countRecentByUser(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    @Query("SELECT COALESCE(SUM(c.sentCount), 0) FROM BulkCampaign c WHERE c.user.id = :userId AND c.createdAt >= :since")
    long sumSentByUserSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);
}
