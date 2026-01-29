package com.digitalgroup.holape.domain.message.repository;

import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.message.entity.CannedMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for CannedMessage
 * PARIDAD RAILS: canned_message.rb
 * Campos: client_id, user_id, message, client_global, status
 */
@Repository
public interface CannedMessageRepository extends JpaRepository<CannedMessage, Long> {

    /**
     * Find by client and status
     */
    List<CannedMessage> findByClientIdAndStatus(Long clientId, Status status);

    /**
     * Find by client with active status
     */
    @Query("SELECT c FROM CannedMessage c WHERE c.client.id = :clientId AND c.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE ORDER BY c.id DESC")
    List<CannedMessage> findActiveByClient(@Param("clientId") Long clientId);

    /**
     * Find by client and user
     */
    List<CannedMessage> findByClientIdAndUserId(Long clientId, Long userId);

    /**
     * Find global canned messages for a client
     */
    @Query("SELECT c FROM CannedMessage c WHERE c.client.id = :clientId AND c.clientGlobal = true AND c.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE ORDER BY c.id DESC")
    List<CannedMessage> findByClientIdAndClientGlobalTrue(Long clientId);

    /**
     * Find visible to user (own messages OR client_global = true)
     * PARIDAD RAILS: canned_messages_controller.rb líneas 11-12
     */
    @Query("SELECT DISTINCT c FROM CannedMessage c WHERE c.client.id = :clientId AND c.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE AND (c.user.id = :userId OR c.clientGlobal = true) ORDER BY c.id DESC")
    List<CannedMessage> findByClientIdAndUserIdOrClientGlobal(
            @Param("clientId") Long clientId,
            @Param("userId") Long userId);

    /**
     * Search in message content
     */
    @Query("SELECT c FROM CannedMessage c WHERE c.client.id = :clientId AND c.status = com.digitalgroup.holape.domain.common.enums.Status.ACTIVE AND LOWER(c.message) LIKE LOWER(CONCAT('%', :search, '%'))")
    List<CannedMessage> search(@Param("clientId") Long clientId, @Param("search") String search);

    /**
     * Count by client
     */
    long countByClientId(Long clientId);

    /**
     * Count by client and status
     */
    long countByClientIdAndStatus(Long clientId, Status status);
}
