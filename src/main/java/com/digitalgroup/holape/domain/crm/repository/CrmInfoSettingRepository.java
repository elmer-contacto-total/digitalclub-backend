package com.digitalgroup.holape.domain.crm.repository;

import com.digitalgroup.holape.domain.crm.entity.CrmInfoSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CrmInfoSettingRepository extends JpaRepository<CrmInfoSetting, Long> {

    List<CrmInfoSetting> findByClientIdOrderByColumnPositionAsc(Long clientId);

    List<CrmInfoSetting> findByClientIdAndStatusOrderByColumnPositionAsc(
            Long clientId, CrmInfoSetting.Status status);

    @Query("SELECT c FROM CrmInfoSetting c WHERE c.client.id = :clientId " +
           "AND c.status = 0 AND c.columnVisible = true ORDER BY c.columnPosition")
    List<CrmInfoSetting> findVisibleByClient(@Param("clientId") Long clientId);

    Optional<CrmInfoSetting> findByClientIdAndColumnLabel(Long clientId, String columnLabel);

    Optional<CrmInfoSetting> findByClientIdAndColumnPosition(Long clientId, Integer columnPosition);

    @Query("SELECT MAX(c.columnPosition) FROM CrmInfoSetting c WHERE c.client.id = :clientId")
    Integer findMaxPositionByClient(@Param("clientId") Long clientId);

    long countByClientId(Long clientId);
}
