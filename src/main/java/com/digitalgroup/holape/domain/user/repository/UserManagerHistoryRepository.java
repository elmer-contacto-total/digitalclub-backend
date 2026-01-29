package com.digitalgroup.holape.domain.user.repository;

import com.digitalgroup.holape.domain.user.entity.UserManagerHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface UserManagerHistoryRepository extends JpaRepository<UserManagerHistory, Long> {

    List<UserManagerHistory> findByUserIdOrderByCreatedAtDesc(Long userId);

    Page<UserManagerHistory> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @Query("SELECT h FROM UserManagerHistory h WHERE h.newManager.id = :managerId ORDER BY h.createdAt DESC")
    List<UserManagerHistory> findByNewManagerId(@Param("managerId") Long managerId);

    @Query("SELECT h FROM UserManagerHistory h WHERE h.oldManager.id = :managerId ORDER BY h.createdAt DESC")
    List<UserManagerHistory> findByOldManagerId(@Param("managerId") Long managerId);

    @Query("SELECT h FROM UserManagerHistory h WHERE h.user.client.id = :clientId ORDER BY h.createdAt DESC")
    Page<UserManagerHistory> findByClientId(@Param("clientId") Long clientId, Pageable pageable);

    @Query("SELECT h FROM UserManagerHistory h WHERE h.createdAt BETWEEN :startDate AND :endDate ORDER BY h.createdAt DESC")
    List<UserManagerHistory> findByDateRange(@Param("startDate") LocalDateTime startDate,
                                              @Param("endDate") LocalDateTime endDate);

    long countByUserId(Long userId);
}
