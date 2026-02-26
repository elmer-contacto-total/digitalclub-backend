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

    @Query(value = "SELECT a FROM Audit a LEFT JOIN FETCH a.user u LEFT JOIN FETCH u.client WHERE a.createdAt BETWEEN :startDate AND :endDate ORDER BY a.createdAt DESC",
           countQuery = "SELECT COUNT(a) FROM Audit a WHERE a.createdAt BETWEEN :startDate AND :endDate")
    Page<Audit> findByDateRange(@Param("startDate") LocalDateTime startDate,
                                 @Param("endDate") LocalDateTime endDate,
                                 Pageable pageable);

    @Query(value = "SELECT a FROM Audit a LEFT JOIN FETCH a.user u LEFT JOIN FETCH u.client WHERE u.client.id = :clientId " +
           "AND a.createdAt BETWEEN :startDate AND :endDate ORDER BY a.createdAt DESC",
           countQuery = "SELECT COUNT(a) FROM Audit a JOIN a.user u WHERE u.client.id = :clientId AND a.createdAt BETWEEN :startDate AND :endDate")
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
    @Query("SELECT a FROM Audit a LEFT JOIN FETCH a.user u LEFT JOIN FETCH u.client WHERE u.client.id = :clientId " +
           "AND a.createdAt BETWEEN :startDate AND :endDate ORDER BY a.createdAt DESC")
    List<Audit> findByClientAndDateRange(@Param("clientId") Long clientId,
                                         @Param("startDate") LocalDateTime startDate,
                                         @Param("endDate") LocalDateTime endDate);

    /**
     * Search audits with multiple optional filters.
     * PARIDAD RAILS: Rails uses DataTables client-side search; this provides server-side equivalent.
     *
     * @param startDate  date range start
     * @param endDate    date range end
     * @param clientId   filter by client (null = all clients, for SUPER_ADMIN)
     * @param auditableType filter by entity type (null = all types)
     * @param action     filter by action: create/update/destroy (null = all)
     * @param search     free text search across username, auditable_type, action, auditable_id, audited_changes
     */
    @Query(value = """
            SELECT a.* FROM audits a
            LEFT JOIN users u ON u.id = a.user_id
            WHERE a.created_at BETWEEN :startDate AND :endDate
            AND (:clientId IS NULL OR u.client_id = :clientId)
            AND (:auditableType IS NULL OR a.auditable_type = :auditableType)
            AND (:action IS NULL OR a.action = :action)
            AND (:search IS NULL OR (
                a.username ILIKE CONCAT('%', :search, '%')
                OR a.auditable_type ILIKE CONCAT('%', :search, '%')
                OR a.action ILIKE CONCAT('%', :search, '%')
                OR CAST(a.auditable_id AS TEXT) = :search
                OR CAST(a.audited_changes AS TEXT) ILIKE CONCAT('%', :search, '%')
            ))
            ORDER BY a.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM audits a
            LEFT JOIN users u ON u.id = a.user_id
            WHERE a.created_at BETWEEN :startDate AND :endDate
            AND (:clientId IS NULL OR u.client_id = :clientId)
            AND (:auditableType IS NULL OR a.auditable_type = :auditableType)
            AND (:action IS NULL OR a.action = :action)
            AND (:search IS NULL OR (
                a.username ILIKE CONCAT('%', :search, '%')
                OR a.auditable_type ILIKE CONCAT('%', :search, '%')
                OR a.action ILIKE CONCAT('%', :search, '%')
                OR CAST(a.auditable_id AS TEXT) = :search
                OR CAST(a.audited_changes AS TEXT) ILIKE CONCAT('%', :search, '%')
            ))
            """,
            nativeQuery = true)
    Page<Audit> searchAudits(@Param("startDate") LocalDateTime startDate,
                              @Param("endDate") LocalDateTime endDate,
                              @Param("clientId") Long clientId,
                              @Param("auditableType") String auditableType,
                              @Param("action") String action,
                              @Param("search") String search,
                              Pageable pageable);
}
