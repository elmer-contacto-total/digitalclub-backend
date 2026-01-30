package com.digitalgroup.holape.domain.message.repository;

import com.digitalgroup.holape.domain.common.enums.MessageDirection;
import com.digitalgroup.holape.domain.common.enums.MessageStatus;
import com.digitalgroup.holape.domain.message.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long>, JpaSpecificationExecutor<Message> {

    List<Message> findByTicketIdOrderByCreatedAtAsc(Long ticketId);

    Page<Message> findBySenderIdOrRecipientIdOrderByCreatedAtDesc(Long senderId, Long recipientId, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.ticket.id = :ticketId ORDER BY m.createdAt DESC")
    List<Message> findLatestMessagesByTicket(@Param("ticketId") Long ticketId, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE " +
           "(m.sender.id = :userId1 AND m.recipient.id = :userId2) OR " +
           "(m.sender.id = :userId2 AND m.recipient.id = :userId1) " +
           "ORDER BY m.createdAt DESC")
    Page<Message> findConversationBetweenUsers(
            @Param("userId1") Long userId1,
            @Param("userId2") Long userId2,
            Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.processed = false ORDER BY m.createdAt ASC")
    List<Message> findUnprocessedMessages(Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.ticket.id = :ticketId AND m.direction = :direction AND m.createdAt > :since")
    List<Message> findMessagesByTicketAndDirectionSince(
            @Param("ticketId") Long ticketId,
            @Param("direction") MessageDirection direction,
            @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.ticket.id = :ticketId AND m.direction = :direction")
    long countByTicketAndDirection(@Param("ticketId") Long ticketId, @Param("direction") MessageDirection direction);

    @Query("SELECT m FROM Message m WHERE m.sender.client.id = :clientId AND m.createdAt BETWEEN :startDate AND :endDate")
    List<Message> findByClientAndDateRange(
            @Param("clientId") Long clientId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    Optional<Message> findFirstByTicketIdAndDirectionOrderByCreatedAtAsc(Long ticketId, MessageDirection direction);

    @Query("SELECT m FROM Message m WHERE m.recipient.id = :recipientId AND m.direction = com.digitalgroup.holape.domain.common.enums.MessageDirection.INCOMING " +
           "AND m.ticket.id IS NULL ORDER BY m.createdAt DESC")
    List<Message> findUnassignedIncomingMessages(@Param("recipientId") Long recipientId);

    // Find messages in a time range for KPI calculations (by ticket)
    @Query("""
            SELECT m FROM Message m
            WHERE m.ticket.id = :ticketId
            AND m.createdAt BETWEEN :startTime AND :endTime
            ORDER BY m.createdAt ASC
            """)
    List<Message> findMessagesInTimeRange(
            @Param("ticketId") Long ticketId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // Find messages in a time range between two users (for ordering)
    @Query("""
            SELECT m FROM Message m
            WHERE ((m.sender.id = :senderId AND m.recipient.id = :recipientId)
            OR (m.sender.id = :recipientId AND m.recipient.id = :senderId))
            AND m.sentAt BETWEEN :startTime AND :endTime
            ORDER BY m.sentAt ASC
            """)
    List<Message> findMessagesInTimeRangeBetweenUsers(
            @Param("senderId") Long senderId,
            @Param("recipientId") Long recipientId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // Find recent messages by user (for require_response logic)
    @Query("""
            SELECT m FROM Message m
            WHERE (m.sender.id = :userId OR m.recipient.id = :userId)
            AND m.createdAt > :since
            ORDER BY m.createdAt DESC
            """)
    List<Message> findRecentByUser(
            @Param("userId") Long userId,
            @Param("since") LocalDateTime since);

    // Find recent messages by user with Pageable (for getting last N messages)
    @Query("""
            SELECT m FROM Message m
            WHERE (m.sender.id = :userId OR m.recipient.id = :userId)
            ORDER BY m.createdAt DESC
            """)
    List<Message> findRecentByUser(@Param("userId") Long userId, Pageable pageable);

    // Find last message for user (any direction)
    @Query("""
            SELECT m FROM Message m
            WHERE (m.sender.id = :userId OR m.recipient.id = :userId)
            ORDER BY m.createdAt DESC
            LIMIT 1
            """)
    Optional<Message> findLastMessageByUser(@Param("userId") Long userId);

    // Find the last outgoing message for a user
    @Query("""
            SELECT m FROM Message m
            WHERE m.sender.id = :senderId
            AND m.direction = com.digitalgroup.holape.domain.common.enums.MessageDirection.OUTGOING
            ORDER BY m.createdAt DESC
            LIMIT 1
            """)
    Optional<Message> findLastOutgoingBySender(@Param("senderId") Long senderId);

    // Find the last incoming message for a recipient
    @Query("""
            SELECT m FROM Message m
            WHERE m.recipient.id = :recipientId
            AND m.direction = com.digitalgroup.holape.domain.common.enums.MessageDirection.INCOMING
            ORDER BY m.createdAt DESC
            LIMIT 1
            """)
    Optional<Message> findLastIncomingByRecipient(@Param("recipientId") Long recipientId);

    // Count messages by user in time range (for analytics)
    @Query("""
            SELECT COUNT(m) FROM Message m
            WHERE m.sender.id = :userId
            AND m.direction = :direction
            AND m.createdAt BETWEEN :startTime AND :endTime
            """)
    long countByUserAndDirectionInRange(
            @Param("userId") Long userId,
            @Param("direction") MessageDirection direction,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    // Find first response to ticket (for first_response_time KPI)
    @Query("""
            SELECT m FROM Message m
            WHERE m.ticket.id = :ticketId
            AND m.direction = com.digitalgroup.holape.domain.common.enums.MessageDirection.OUTGOING
            ORDER BY m.createdAt ASC
            LIMIT 1
            """)
    Optional<Message> findFirstResponseToTicket(@Param("ticketId") Long ticketId);

    // Find messages needing WhatsApp delivery
    // PARIDAD RAILS: messageType no existe en schema, todos los mensajes son WhatsApp
    @Query("""
            SELECT m FROM Message m
            WHERE m.direction = com.digitalgroup.holape.domain.common.enums.MessageDirection.OUTGOING
            AND m.processed = false
            ORDER BY m.createdAt ASC
            """)
    List<Message> findPendingWhatsAppMessages(Pageable pageable);

    // Search messages by content
    @Query("""
            SELECT m FROM Message m
            WHERE m.ticket.id = :ticketId
            AND LOWER(m.content) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
            ORDER BY m.createdAt DESC
            """)
    Page<Message> searchByContentInTicket(
            @Param("ticketId") Long ticketId,
            @Param("searchTerm") String searchTerm,
            Pageable pageable);

    // Count by recipient and status
    long countByRecipientIdAndStatus(Long recipientId, MessageStatus status);

    // PARIDAD RAILS: whatsappMessageId no existe en schema
    // El tracking de mensajes WhatsApp se hace por message ID normal

    // Find by status (for ProcessMessageJob)
    List<Message> findByStatusOrderByCreatedAtAsc(MessageStatus status);

    // Find messages by sender
    List<Message> findBySenderIdOrderByCreatedAtDesc(Long senderId, Pageable pageable);

    // Find unread messages for recipient
    @Query("""
            SELECT m FROM Message m
            WHERE m.recipient.id = :recipientId
            AND m.status = com.digitalgroup.holape.domain.common.enums.MessageStatus.UNREAD
            ORDER BY m.createdAt DESC
            """)
    List<Message> findUnreadByRecipient(@Param("recipientId") Long recipientId);

    // Count messages by ticket
    long countByTicketId(Long ticketId);

    // Find last message by ticket
    @Query("""
            SELECT m FROM Message m
            WHERE m.ticket.id = :ticketId
            ORDER BY m.createdAt DESC
            LIMIT 1
            """)
    Optional<Message> findLastByTicketId(@Param("ticketId") Long ticketId);

    /**
     * Find sent_at of last incoming message in a ticket
     * PARIDAD RAILS: message.rb línea 115
     * last_closed_ticket&.messages&.incoming&.order(:sent_at, :id)&.last&.sent_at
     */
    @Query("""
            SELECT m.sentAt FROM Message m
            WHERE m.ticket.id = :ticketId
            AND m.direction = com.digitalgroup.holape.domain.common.enums.MessageDirection.INCOMING
            ORDER BY m.sentAt DESC, m.id DESC
            LIMIT 1
            """)
    Optional<LocalDateTime> findLastIncomingSentAtByTicketId(@Param("ticketId") Long ticketId);

    // ==================== MANAGER MIGRATION QUERIES ====================

    // Update sender_id for all messages sent by old agent to new agent
    @Modifying
    @Query("UPDATE Message m SET m.sender.id = :newAgentId WHERE m.sender.id = :oldAgentId")
    int updateSenderForAllMessages(@Param("oldAgentId") Long oldAgentId, @Param("newAgentId") Long newAgentId);

    // Update recipient_id for all messages received by old agent to new agent
    @Modifying
    @Query("UPDATE Message m SET m.recipient.id = :newAgentId WHERE m.recipient.id = :oldAgentId")
    int updateRecipientForAllMessages(@Param("oldAgentId") Long oldAgentId, @Param("newAgentId") Long newAgentId);

    // Count messages by sender
    long countBySenderId(Long senderId);

    // Count messages by recipient
    long countByRecipientId(Long recipientId);

    // Find all messages for a user ordered by sent_at (for CSV export)
    @Query("""
            SELECT m FROM Message m
            WHERE m.sender.id = :senderId OR m.recipient.id = :recipientId
            ORDER BY m.sentAt ASC
            """)
    List<Message> findBySenderIdOrRecipientIdOrderBySentAtAsc(
            @Param("senderId") Long senderId,
            @Param("recipientId") Long recipientId);

    // ==================== MESSAGES LIST (PARIDAD Rails Admin::MessagesController#index) ====================

    /**
     * Find incoming messages for recipient (user) - paginated
     * PARIDAD RAILS: direction == 'incoming' ? Message.where(recipient_id: current_user.id)
     */
    @Query("""
            SELECT m FROM Message m
            LEFT JOIN FETCH m.sender
            WHERE m.recipient.id = :recipientId
            AND m.direction = :direction
            ORDER BY m.sentAt DESC, m.id DESC
            """)
    Page<Message> findByRecipientIdAndDirectionOrderBySentAtDesc(
            @Param("recipientId") Long recipientId,
            @Param("direction") MessageDirection direction,
            Pageable pageable);

    /**
     * Find outgoing messages for sender (user) - paginated
     * PARIDAD RAILS: direction == 'outgoing' ? Message.where(sender_id: current_user.id)
     */
    @Query("""
            SELECT m FROM Message m
            LEFT JOIN FETCH m.recipient
            WHERE m.sender.id = :senderId
            AND m.direction = :direction
            ORDER BY m.sentAt DESC, m.id DESC
            """)
    Page<Message> findBySenderIdAndDirectionOrderBySentAtDesc(
            @Param("senderId") Long senderId,
            @Param("direction") MessageDirection direction,
            Pageable pageable);

    /**
     * Find incoming messages with search filter
     * PARIDAD RAILS: base_query.where("content ILIKE ?", search_term)
     */
    @Query("""
            SELECT m FROM Message m
            LEFT JOIN FETCH m.sender
            WHERE m.recipient.id = :recipientId
            AND m.direction = com.digitalgroup.holape.domain.common.enums.MessageDirection.INCOMING
            AND LOWER(m.content) LIKE LOWER(CONCAT('%', :search, '%'))
            ORDER BY m.sentAt DESC, m.id DESC
            """)
    Page<Message> findIncomingMessagesWithSearch(
            @Param("recipientId") Long recipientId,
            @Param("search") String search,
            Pageable pageable);

    /**
     * Find outgoing messages with search filter
     * PARIDAD RAILS: base_query.where("content ILIKE ?", search_term)
     */
    @Query("""
            SELECT m FROM Message m
            LEFT JOIN FETCH m.recipient
            WHERE m.sender.id = :senderId
            AND m.direction = com.digitalgroup.holape.domain.common.enums.MessageDirection.OUTGOING
            AND LOWER(m.content) LIKE LOWER(CONCAT('%', :search, '%'))
            ORDER BY m.sentAt DESC, m.id DESC
            """)
    Page<Message> findOutgoingMessagesWithSearch(
            @Param("senderId") Long senderId,
            @Param("search") String search,
            Pageable pageable);

    // ==================== SUPERVISOR MESSAGES (multiple user IDs) ====================

    /**
     * Find incoming messages for multiple recipients (supervisors viewing subordinates' messages)
     * PARIDAD RAILS: MessagesController#index for manager_level_4
     * Message.where(recipient_id: agents.ids, direction: 'incoming')
     */
    @Query("""
            SELECT m FROM Message m
            WHERE m.recipient.id IN :recipientIds
            AND m.direction = :direction
            ORDER BY m.sentAt DESC
            """)
    Page<Message> findByRecipientIdInAndDirectionOrderBySentAtDesc(
            @Param("recipientIds") List<Long> recipientIds,
            @Param("direction") MessageDirection direction,
            Pageable pageable);

    /**
     * Find outgoing messages for multiple senders (supervisors viewing subordinates' messages)
     * PARIDAD RAILS: MessagesController#index for manager_level_4
     * Message.where(sender_id: agents.ids, direction: 'outgoing')
     */
    @Query("""
            SELECT m FROM Message m
            WHERE m.sender.id IN :senderIds
            AND m.direction = :direction
            ORDER BY m.sentAt DESC
            """)
    Page<Message> findBySenderIdInAndDirectionOrderBySentAtDesc(
            @Param("senderIds") List<Long> senderIds,
            @Param("direction") MessageDirection direction,
            Pageable pageable);

    /**
     * Find incoming messages with search for multiple recipients (supervisor view)
     * PARIDAD RAILS: base_query.where("content ILIKE ?", search_term)
     */
    @Query("""
            SELECT m FROM Message m
            WHERE m.recipient.id IN :recipientIds
            AND m.direction = com.digitalgroup.holape.domain.common.enums.MessageDirection.INCOMING
            AND LOWER(m.content) LIKE LOWER(CONCAT('%', :search, '%'))
            ORDER BY m.sentAt DESC
            """)
    Page<Message> findIncomingMessagesByUserIdsWithSearch(
            @Param("recipientIds") List<Long> recipientIds,
            @Param("search") String search,
            Pageable pageable);

    /**
     * Find outgoing messages with search for multiple senders (supervisor view)
     * PARIDAD RAILS: base_query.where("content ILIKE ?", search_term)
     */
    @Query("""
            SELECT m FROM Message m
            WHERE m.sender.id IN :senderIds
            AND m.direction = com.digitalgroup.holape.domain.common.enums.MessageDirection.OUTGOING
            AND LOWER(m.content) LIKE LOWER(CONCAT('%', :search, '%'))
            ORDER BY m.sentAt DESC
            """)
    Page<Message> findOutgoingMessagesByUserIdsWithSearch(
            @Param("senderIds") List<Long> senderIds,
            @Param("search") String search,
            Pageable pageable);
}
