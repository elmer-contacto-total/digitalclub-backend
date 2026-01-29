package com.digitalgroup.holape.domain.importdata.repository;

import com.digitalgroup.holape.domain.common.enums.ImportStatus;
import com.digitalgroup.holape.domain.importdata.entity.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ImportRepository extends JpaRepository<Import, Long> {

    Page<Import> findByClientId(Long clientId, Pageable pageable);

    Page<Import> findByClientIdAndStatus(Long clientId, ImportStatus status, Pageable pageable);

    List<Import> findByStatus(ImportStatus status);

    @Query("SELECT i FROM Import i WHERE i.status = :status ORDER BY i.createdAt ASC")
    List<Import> findPendingImports(@Param("status") ImportStatus status);

    @Query("SELECT i FROM Import i WHERE i.status = com.digitalgroup.holape.domain.common.enums.ImportStatus.STATUS_PROCESSING AND i.updatedAt < :timeout")
    List<Import> findStuckImports(@Param("timeout") java.time.LocalDateTime timeout);

    long countByClientIdAndStatus(Long clientId, ImportStatus status);
}
