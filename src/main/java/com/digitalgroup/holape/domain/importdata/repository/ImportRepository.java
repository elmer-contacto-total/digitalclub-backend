package com.digitalgroup.holape.domain.importdata.repository;

import com.digitalgroup.holape.domain.common.enums.ImportStatus;
import com.digitalgroup.holape.domain.importdata.entity.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ImportRepository extends JpaRepository<Import, Long> {

    @Query(value = """
            SELECT i.* FROM imports i
            WHERE i.client_id = :clientId
            ORDER BY i.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(i.id) FROM imports i
            WHERE i.client_id = :clientId
            """,
            nativeQuery = true)
    Page<Import> findByClientId(@Param("clientId") Long clientId, Pageable pageable);

    @Query(value = """
            SELECT i.* FROM imports i
            WHERE i.client_id = :clientId AND i.user_id = :userId
            ORDER BY i.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(i.id) FROM imports i
            WHERE i.client_id = :clientId AND i.user_id = :userId
            """,
            nativeQuery = true)
    Page<Import> findByClientIdAndUserId(
            @Param("clientId") Long clientId,
            @Param("userId") Long userId,
            Pageable pageable);

    @EntityGraph(attributePaths = {"user", "client"})
    Optional<Import> findById(Long id);

    Page<Import> findByClientIdAndStatus(Long clientId, ImportStatus status, Pageable pageable);

    List<Import> findByStatus(ImportStatus status);

    @Query("SELECT i FROM Import i WHERE i.status = :status ORDER BY i.createdAt ASC")
    List<Import> findPendingImports(@Param("status") ImportStatus status);

    @Query("SELECT i FROM Import i WHERE i.status = com.digitalgroup.holape.domain.common.enums.ImportStatus.STATUS_PROCESSING AND i.updatedAt < :timeout")
    List<Import> findStuckImports(@Param("timeout") java.time.LocalDateTime timeout);

    long countByClientIdAndStatus(Long clientId, ImportStatus status);
}
