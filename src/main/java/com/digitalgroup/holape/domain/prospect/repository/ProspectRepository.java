package com.digitalgroup.holape.domain.prospect.repository;

import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.prospect.entity.Prospect;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProspectRepository extends JpaRepository<Prospect, Long> {

    Optional<Prospect> findByPhone(String phone);

    Optional<Prospect> findByPhoneAndClientId(String phone, Long clientId);

    List<Prospect> findByClientId(Long clientId);

    Page<Prospect> findByClientIdAndUpgradedToUserFalse(Long clientId, Pageable pageable);

    List<Prospect> findByClientIdAndUpgradedToUserFalse(Long clientId);

    Page<Prospect> findByClientIdAndStatusAndUpgradedToUserFalse(Long clientId, Status status, Pageable pageable);

    List<Prospect> findByManagerId(Long managerId);

    // PARIDAD Rails: current_user.prospects (for agents)
    Page<Prospect> findByManagerIdOrderByIdDesc(Long managerId, Pageable pageable);

    // PARIDAD Rails: Prospect.where(client_id: ...) with optional manager filter (for manager_level_4)
    Page<Prospect> findByClientIdOrderByIdDesc(Long clientId, Pageable pageable);

    // With manager filter
    Page<Prospect> findByClientIdAndManagerIdOrderByIdDesc(Long clientId, Long managerId, Pageable pageable);

    // Search by manager
    @Query("SELECT p FROM Prospect p WHERE p.manager.id = :managerId AND " +
           "(LOWER(p.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "p.phone LIKE CONCAT('%', :searchTerm, '%')) ORDER BY p.id DESC")
    Page<Prospect> searchByManagerId(@Param("managerId") Long managerId,
                                      @Param("searchTerm") String searchTerm,
                                      Pageable pageable);

    // Search by client with optional manager filter
    @Query("SELECT p FROM Prospect p WHERE p.clientId = :clientId AND " +
           "(:managerId IS NULL OR p.manager.id = :managerId) AND " +
           "(LOWER(p.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "p.phone LIKE CONCAT('%', :searchTerm, '%')) ORDER BY p.id DESC")
    Page<Prospect> searchByClientIdAndOptionalManager(@Param("clientId") Long clientId,
                                                       @Param("managerId") Long managerId,
                                                       @Param("searchTerm") String searchTerm,
                                                       Pageable pageable);

    @Query("SELECT p FROM Prospect p WHERE p.clientId = :clientId AND " +
           "(LOWER(p.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "p.phone LIKE CONCAT('%', :searchTerm, '%'))")
    Page<Prospect> searchByClientId(@Param("clientId") Long clientId,
                                     @Param("searchTerm") String searchTerm,
                                     Pageable pageable);

    long countByClientId(Long clientId);

    long countByClientIdAndStatus(Long clientId, Status status);
}
