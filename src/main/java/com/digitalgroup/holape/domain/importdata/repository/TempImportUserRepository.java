package com.digitalgroup.holape.domain.importdata.repository;

import com.digitalgroup.holape.domain.importdata.entity.TempImportUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * TempImportUser Repository
 *
 * PARIDAD RAILS: schema.rb líneas 367-384
 * La validez se determina por error_message:
 * - error_message IS NULL → válido (sin errores)
 * - error_message IS NOT NULL → inválido (tiene errores)
 *
 * El orden se determina por phone_order (no existe campo rowNumber)
 */
@Repository
public interface TempImportUserRepository extends JpaRepository<TempImportUser, Long> {

    // Find by import
    List<TempImportUser> findByUserImportId(Long importId);

    Page<TempImportUser> findByUserImportId(Long importId, Pageable pageable);

    // Find unprocessed by import
    // PARIDAD RAILS: usar phoneOrder para ordenar (no existe rowNumber)
    @Query("SELECT t FROM TempImportUser t WHERE t.userImport.id = :importId AND t.processed = false ORDER BY t.phoneOrder ASC NULLS LAST, t.id ASC")
    List<TempImportUser> findUnprocessedByImport(@Param("importId") Long importId);

    // Find valid records by import
    // PARIDAD RAILS: válido = error_message IS NULL (no existe campo valid)
    @Query("SELECT t FROM TempImportUser t WHERE t.userImport.id = :importId AND t.errorMessage IS NULL ORDER BY t.phoneOrder ASC NULLS LAST, t.id ASC")
    List<TempImportUser> findValidByImport(@Param("importId") Long importId);

    // Find invalid records by import
    // PARIDAD RAILS: inválido = error_message IS NOT NULL
    @Query("SELECT t FROM TempImportUser t WHERE t.userImport.id = :importId AND t.errorMessage IS NOT NULL ORDER BY t.phoneOrder ASC NULLS LAST, t.id ASC")
    List<TempImportUser> findInvalidByImport(@Param("importId") Long importId);

    // Count by import
    long countByUserImportId(Long importId);

    // Count valid by import (error_message IS NULL)
    @Query("SELECT COUNT(t) FROM TempImportUser t WHERE t.userImport.id = :importId AND t.errorMessage IS NULL")
    long countValidByImport(@Param("importId") Long importId);

    // Count invalid by import (error_message IS NOT NULL)
    @Query("SELECT COUNT(t) FROM TempImportUser t WHERE t.userImport.id = :importId AND t.errorMessage IS NOT NULL")
    long countInvalidByImport(@Param("importId") Long importId);

    // Count processed by import
    @Query("SELECT COUNT(t) FROM TempImportUser t WHERE t.userImport.id = :importId AND t.processed = true")
    long countProcessedByImport(@Param("importId") Long importId);

    // Find by phone (for duplicate detection)
    @Query("SELECT t FROM TempImportUser t WHERE t.userImport.id = :importId AND t.phone = :phone")
    List<TempImportUser> findByImportAndPhone(@Param("importId") Long importId, @Param("phone") String phone);

    // Find by email (for duplicate detection)
    @Query("SELECT t FROM TempImportUser t WHERE t.userImport.id = :importId AND LOWER(t.email) = LOWER(:email)")
    List<TempImportUser> findByImportAndEmail(@Param("importId") Long importId, @Param("email") String email);

    // Paged: all records for import
    @Query("SELECT t FROM TempImportUser t WHERE t.userImport.id = :importId ORDER BY t.phoneOrder ASC NULLS LAST, t.id ASC")
    Page<TempImportUser> findPagedByImport(@Param("importId") Long importId, Pageable pageable);

    // Paged: only invalid (has error)
    @Query("SELECT t FROM TempImportUser t WHERE t.userImport.id = :importId AND t.errorMessage IS NOT NULL ORDER BY t.phoneOrder ASC NULLS LAST, t.id ASC")
    Page<TempImportUser> findPagedInvalidByImport(@Param("importId") Long importId, Pageable pageable);

    // Paged: only valid (no error)
    @Query("SELECT t FROM TempImportUser t WHERE t.userImport.id = :importId AND t.errorMessage IS NULL ORDER BY t.phoneOrder ASC NULLS LAST, t.id ASC")
    Page<TempImportUser> findPagedValidByImport(@Param("importId") Long importId, Pageable pageable);

    // Paged + search: all
    @Query("SELECT t FROM TempImportUser t WHERE t.userImport.id = :importId AND (LOWER(t.phone) LIKE :q OR LOWER(t.firstName) LIKE :q OR LOWER(t.lastName) LIKE :q OR LOWER(t.email) LIKE :q OR LOWER(t.managerEmail) LIKE :q) ORDER BY t.phoneOrder ASC NULLS LAST, t.id ASC")
    Page<TempImportUser> searchPagedByImport(@Param("importId") Long importId, @Param("q") String query, Pageable pageable);

    // Paged + search: only invalid
    @Query("SELECT t FROM TempImportUser t WHERE t.userImport.id = :importId AND t.errorMessage IS NOT NULL AND (LOWER(t.phone) LIKE :q OR LOWER(t.firstName) LIKE :q OR LOWER(t.lastName) LIKE :q OR LOWER(t.email) LIKE :q OR LOWER(t.managerEmail) LIKE :q) ORDER BY t.phoneOrder ASC NULLS LAST, t.id ASC")
    Page<TempImportUser> searchPagedInvalidByImport(@Param("importId") Long importId, @Param("q") String query, Pageable pageable);

    // Paged + search: only valid
    @Query("SELECT t FROM TempImportUser t WHERE t.userImport.id = :importId AND t.errorMessage IS NULL AND (LOWER(t.phone) LIKE :q OR LOWER(t.firstName) LIKE :q OR LOWER(t.lastName) LIKE :q OR LOWER(t.email) LIKE :q OR LOWER(t.managerEmail) LIKE :q) ORDER BY t.phoneOrder ASC NULLS LAST, t.id ASC")
    Page<TempImportUser> searchPagedValidByImport(@Param("importId") Long importId, @Param("q") String query, Pageable pageable);

    // Delete all by import
    @Modifying
    @Query("DELETE FROM TempImportUser t WHERE t.userImport.id = :importId")
    void deleteByImportId(@Param("importId") Long importId);

    // Mark as processed
    @Modifying
    @Query("UPDATE TempImportUser t SET t.processed = true WHERE t.id = :id")
    void markAsProcessed(@Param("id") Long id);

    // Bulk mark as processed (valid = errorMessage IS NULL)
    @Modifying
    @Query("UPDATE TempImportUser t SET t.processed = true WHERE t.userImport.id = :importId AND t.errorMessage IS NULL")
    int markValidAsProcessed(@Param("importId") Long importId);
}
