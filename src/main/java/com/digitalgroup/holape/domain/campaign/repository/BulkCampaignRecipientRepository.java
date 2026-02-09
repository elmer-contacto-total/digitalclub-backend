package com.digitalgroup.holape.domain.campaign.repository;

import com.digitalgroup.holape.domain.campaign.entity.BulkCampaignRecipient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BulkCampaignRecipientRepository extends JpaRepository<BulkCampaignRecipient, Long> {

    Page<BulkCampaignRecipient> findByCampaignId(Long campaignId, Pageable pageable);

    List<BulkCampaignRecipient> findByCampaignIdAndStatus(Long campaignId, String status);

    Optional<BulkCampaignRecipient> findFirstByCampaignIdAndStatusOrderByIdAsc(Long campaignId, String status);

    long countByCampaignIdAndStatus(Long campaignId, String status);
}
