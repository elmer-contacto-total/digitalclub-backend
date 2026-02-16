package com.digitalgroup.holape.domain.alert.repository;

import com.digitalgroup.holape.domain.alert.entity.Alert;
import com.digitalgroup.holape.domain.common.enums.AlertType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Alert Repository
 * PARIDAD RAILS: La entidad Alert tiene:
 * - user_id (no client_id directo)
 * - read (no acknowledged)
 * - body (no message)
 * - url (almacena referencia a ticket como "/tickets/{id}")
 * - messageId como String (no Long)
 */
@Repository
public interface AlertRepository extends JpaRepository<Alert, Long> {

    Page<Alert> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    List<Alert> findByUserIdAndReadFalseOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndReadFalse(Long userId);

    @Modifying
    @Query("UPDATE Alert a SET a.read = true WHERE a.user.id = :userId AND a.read = false")
    int markAllAsReadByUser(@Param("userId") Long userId);

    @Query("SELECT a FROM Alert a WHERE a.user.id = :userId AND a.alertType = :alertType AND a.read = false")
    List<Alert> findUnreadByUserAndType(@Param("userId") Long userId, @Param("alertType") AlertType alertType);

    // ==================== QUERIES ADAPTADAS PARA PARIDAD RAILS ====================

    // Find alerts by URL containing ticket ID (PARIDAD: ticket no existe como relación)
    @Query("SELECT a FROM Alert a WHERE a.url LIKE CONCAT('/tickets/', :ticketId)")
    List<Alert> findByTicketIdInUrl(@Param("ticketId") Long ticketId);

    // Alias para compatibilidad
    default List<Alert> findByTicketId(Long ticketId) {
        return findByTicketIdInUrl(ticketId);
    }

    // Find by user and read status (PARIDAD: acknowledged -> read)
    Page<Alert> findByUserIdAndRead(Long userId, boolean read, Pageable pageable);

    // Alias para compatibilidad (acknowledged -> read)
    default Page<Alert> findByUserIdAndAcknowledged(Long userId, boolean acknowledged, Pageable pageable) {
        return findByUserIdAndRead(userId, acknowledged, pageable);
    }

    // Count unread alerts for a user
    default long countByUserIdAndAcknowledgedFalse(Long userId) {
        return countByUserIdAndReadFalse(userId);
    }

    // PARIDAD: client_id no existe directo en Alert, buscar por user.client.id
    @Query("SELECT a FROM Alert a WHERE a.user.client.id = :clientId AND a.read = :read ORDER BY a.createdAt DESC")
    Page<Alert> findByClientIdAndRead(
            @Param("clientId") Long clientId,
            @Param("read") boolean read,
            Pageable pageable);

    // Alias para compatibilidad
    default Page<Alert> findByClientIdAndAcknowledged(Long clientId, boolean acknowledged, Pageable pageable) {
        return findByClientIdAndRead(clientId, acknowledged, pageable);
    }

    // Count by client and read status
    @Query("SELECT COUNT(a) FROM Alert a WHERE a.user.client.id = :clientId AND a.read = :read")
    long countByClientIdAndRead(@Param("clientId") Long clientId, @Param("read") boolean read);

    // Alias para compatibilidad
    default long countByClientIdAndAcknowledged(Long clientId, boolean acknowledged) {
        return countByClientIdAndRead(clientId, acknowledged);
    }

    // Find by client, type, and read status
    @Query("SELECT a FROM Alert a WHERE a.user.client.id = :clientId AND a.alertType = :alertType AND a.read = :read ORDER BY a.createdAt DESC")
    Page<Alert> findByClientIdAndTypeAndRead(
            @Param("clientId") Long clientId,
            @Param("alertType") AlertType alertType,
            @Param("read") boolean read,
            Pageable pageable);

    // Alias para compatibilidad
    default Page<Alert> findByClientIdAndTypeAndAcknowledged(
            Long clientId, AlertType alertType, boolean acknowledged, Pageable pageable) {
        return findByClientIdAndTypeAndRead(clientId, alertType, acknowledged, pageable);
    }

    // Find by message ID (String, no Long)
    @Query("SELECT a FROM Alert a WHERE a.messageId = :messageId")
    List<Alert> findByMessageId(@Param("messageId") String messageId);

    // Find recent alerts for a user
    @Query("""
            SELECT a FROM Alert a
            WHERE a.user.id = :userId
            AND a.createdAt > :since
            ORDER BY a.createdAt DESC
            """)
    List<Alert> findRecentByUser(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    // Find alerts by URL containing ticket ID and recent (for avoiding duplicates)
    @Query("""
            SELECT a FROM Alert a
            WHERE a.url LIKE CONCAT('/tickets/', :ticketId)
            AND a.alertType = :alertType
            AND a.createdAt > :since
            ORDER BY a.createdAt DESC
            """)
    List<Alert> findByTicketIdAndTypeAndCreatedAfter(
            @Param("ticketId") Long ticketId,
            @Param("alertType") AlertType alertType,
            @Param("since") LocalDateTime since);

    // Find by URL and read status
    List<Alert> findByUrlAndRead(String url, boolean read);

    // Alias para compatibilidad
    default List<Alert> findByTicketIdAndAcknowledged(Long ticketId, boolean acknowledged) {
        return findByUrlAndRead("/tickets/" + ticketId, acknowledged);
    }
}
