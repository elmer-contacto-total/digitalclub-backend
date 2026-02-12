package com.digitalgroup.holape.domain.audit.repository;

import com.digitalgroup.holape.domain.audit.entity.Audit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditRepository extends JpaRepository<Audit, Long> {

    Page<Audit> findByAuditableTypeAndAuditableId(String auditableType, Long auditableId, Pageable pageable);

    Page<Audit> findByUserId(Long userId, Pageable pageable);

    @Query("SELECT a FROM Audit a WHERE a.createdAt BETWEEN :startDate AND :endDate ORDER BY a.createdAt DESC")
    Page<Audit> findByDateRange(@Param("startDate") LocalDateTime startDate,
                                 @Param("endDate") LocalDateTime endDate,
                                 Pageable pageable);

    @Query("SELECT a FROM Audit a JOIN a.user u WHERE u.client.id = :clientId " +
           "AND a.createdAt BETWEEN :startDate AND :endDate ORDER BY a.createdAt DESC")
    Page<Audit> findByClientAndDateRange(@Param("clientId") Long clientId,
                                          @Param("startDate") LocalDateTime startDate,
                                          @Param("endDate") LocalDateTime endDate,
                                          Pageable pageable);

    @Query("SELECT a FROM Audit a WHERE a.auditableType = :type ORDER BY a.createdAt DESC")
    Page<Audit> findByAuditableType(@Param("type") String type, Pageable pageable);

    @Query("SELECT a FROM Audit a JOIN a.user u WHERE u.client.id = :clientId ORDER BY a.createdAt DESC")
    Page<Audit> findByClient(@Param("clientId") Long clientId, Pageable pageable);

    @Query("SELECT DISTINCT a.auditableType FROM Audit a ORDER BY a.auditableType")
    List<String> findDistinctAuditableTypes();

    long countByAuditableTypeAndAuditableId(String auditableType, Long auditableId);

    @Query("""
        SELECT a FROM Audit a WHERE
        (a.auditableType = 'User' AND a.auditableId = :userId)
        OR (a.associatedType = 'User' AND a.associatedId = :userId)
        ORDER BY a.createdAt DESC
        """)
    Page<Audit> findByUserOrAssociatedUser(@Param("userId") Long userId, Pageable pageable);

    // Find audits by client and date range (for export - returns List)
    @Query("SELECT a FROM Audit a JOIN a.user u WHERE u.client.id = :clientId " +
           "AND a.createdAt BETWEEN :startDate AND :endDate ORDER BY a.createdAt DESC")
    List<Audit> findByClientAndDateRange(@Param("clientId") Long clientId,
                                         @Param("startDate") LocalDateTime startDate,
                                         @Param("endDate") LocalDateTime endDate);
}
