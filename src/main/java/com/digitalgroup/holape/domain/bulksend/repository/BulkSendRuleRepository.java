package com.digitalgroup.holape.domain.bulksend.repository;

import com.digitalgroup.holape.domain.bulksend.entity.BulkSendRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BulkSendRuleRepository extends JpaRepository<BulkSendRule, Long> {

    Optional<BulkSendRule> findByClientId(Long clientId);

    Optional<BulkSendRule> findByClientIdAndUserId(Long clientId, Long userId);
}
