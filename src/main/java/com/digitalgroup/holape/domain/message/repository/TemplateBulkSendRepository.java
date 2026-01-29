package com.digitalgroup.holape.domain.message.repository;

import com.digitalgroup.holape.domain.message.entity.TemplateBulkSend;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for TemplateBulkSend
 * PARIDAD RAILS: Solo usa campos que existen en schema.rb
 */
@Repository
public interface TemplateBulkSendRepository extends JpaRepository<TemplateBulkSend, Long> {

    // Find by client
    Page<TemplateBulkSend> findByClientIdOrderByCreatedAtDesc(Long clientId, Pageable pageable);

    // Find by user
    Page<TemplateBulkSend> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // Find incomplete (sent_count < planned_count)
    @Query("SELECT t FROM TemplateBulkSend t WHERE t.sentCount < t.plannedCount ORDER BY t.createdAt ASC")
    List<TemplateBulkSend> findIncomplete();

    // Find by client incomplete
    @Query("SELECT t FROM TemplateBulkSend t WHERE t.client.id = :clientId AND t.sentCount < t.plannedCount ORDER BY t.createdAt ASC")
    List<TemplateBulkSend> findIncompleteByClientId(@Param("clientId") Long clientId);

    // Count incomplete by client
    @Query("SELECT COUNT(t) FROM TemplateBulkSend t WHERE t.client.id = :clientId AND t.sentCount < t.plannedCount")
    long countIncompleteByClientId(@Param("clientId") Long clientId);

    // Find by template
    List<TemplateBulkSend> findByMessageTemplateId(Integer templateId);

    // Stats query
    @Query("""
            SELECT
                COUNT(t),
                SUM(t.sentCount),
                SUM(t.plannedCount)
            FROM TemplateBulkSend t
            WHERE t.client.id = :clientId
            AND t.createdAt BETWEEN :startDate AND :endDate
            """)
    Object[] getStatsByClientAndDateRange(
            @Param("clientId") Long clientId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    // Recent campaigns
    @Query("""
            SELECT t FROM TemplateBulkSend t
            WHERE t.client.id = :clientId
            ORDER BY t.createdAt DESC
            """)
    List<TemplateBulkSend> findRecentByClient(@Param("clientId") Long clientId, Pageable pageable);
}
