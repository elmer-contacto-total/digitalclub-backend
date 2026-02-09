package com.digitalgroup.holape.domain.campaign.repository;

import com.digitalgroup.holape.domain.campaign.entity.BulkSendRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BulkSendRuleRepository extends JpaRepository<BulkSendRule, Long> {

    Optional<BulkSendRule> findByClientId(Long clientId);
}
