package com.digitalgroup.holape.domain.message.repository;

import com.digitalgroup.holape.domain.message.entity.PushedMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PARIDAD RAILS: pushed_messages solo tiene los campos definidos en schema.rb
 * NO tiene: ticket_id, scheduled_at, retry_count, max_retries, whatsapp_message_id
 */
@Repository
public interface PushedMessageRepository extends JpaRepository<PushedMessage, Long> {

    // Find unprocessed messages ready to process
    // PARIDAD RAILS: Solo usa campos que existen en la tabla
    @Query("""
            SELECT pm FROM PushedMessage pm
            WHERE pm.processed = false
            ORDER BY pm.createdAt ASC
            """)
    List<PushedMessage> findUnprocessed(Pageable pageable);

    // Find by sender
    List<PushedMessage> findBySenderId(Long senderId);

    // Find by recipient
    List<PushedMessage> findByRecipientId(Long recipientId);

    // Find by sender and recipient (for duplicate detection)
    @Query("""
            SELECT pm FROM PushedMessage pm
            WHERE pm.sender.id = :senderId
            AND pm.recipient.id = :recipientId
            AND pm.processed = false
            ORDER BY pm.createdAt DESC
            """)
    List<PushedMessage> findBySenderIdAndRecipientIdUnprocessed(
            @Param("senderId") Long senderId,
            @Param("recipientId") Long recipientId);

    // Find by content and direction for duplicate detection
    // PARIDAD RAILS: index on (new_sender_phone, direction, content, sent_at)
    @Query("""
            SELECT pm FROM PushedMessage pm
            WHERE pm.newSenderPhone = :phone
            AND pm.direction = :direction
            AND pm.content = :content
            AND pm.sentAt = :sentAt
            """)
    List<PushedMessage> findDuplicates(
            @Param("phone") String phone,
            @Param("direction") com.digitalgroup.holape.domain.common.enums.MessageDirection direction,
            @Param("content") String content,
            @Param("sentAt") LocalDateTime sentAt);

    // Count pending messages
    @Query("SELECT COUNT(pm) FROM PushedMessage pm WHERE pm.processed = false")
    long countPending();

    // Find stuck messages (not processed and older than X minutes)
    @Query("""
            SELECT pm FROM PushedMessage pm
            WHERE pm.processed = false
            AND pm.createdAt < :cutoff
            """)
    List<PushedMessage> findStuckMessages(@Param("cutoff") LocalDateTime cutoff);

    // Mark as processed
    @Modifying
    @Query("UPDATE PushedMessage pm SET pm.processed = true, pm.updatedAt = :now WHERE pm.id = :id")
    void markAsProcessed(@Param("id") Long id, @Param("now") LocalDateTime now);

    // Mark as ignored
    @Modifying
    @Query("UPDATE PushedMessage pm SET pm.alreadyIgnored = true, pm.updatedAt = :now WHERE pm.id = :id")
    void markAsIgnored(@Param("id") Long id, @Param("now") LocalDateTime now);

    // Delete old processed messages
    @Modifying
    @Query("DELETE FROM PushedMessage pm WHERE pm.processed = true AND pm.createdAt < :cutoff")
    int deleteOldProcessed(@Param("cutoff") LocalDateTime cutoff);

    // Find by processed status
    List<PushedMessage> findByProcessed(Boolean processed);

    // Find by WhatsApp business routed
    List<PushedMessage> findByWhatsappBusinessRouted(Boolean whatsappBusinessRouted);
}
