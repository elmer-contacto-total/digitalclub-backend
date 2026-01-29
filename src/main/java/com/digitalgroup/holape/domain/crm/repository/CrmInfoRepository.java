package com.digitalgroup.holape.domain.crm.repository;

import com.digitalgroup.holape.domain.crm.entity.CrmInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CrmInfoRepository extends JpaRepository<CrmInfo, Long> {

    List<CrmInfo> findByUserId(Long userId);

    Optional<CrmInfo> findByUserIdAndCrmInfoSettingId(Long userId, Long crmInfoSettingId);

    @Query("SELECT c FROM CrmInfo c WHERE c.user.id = :userId " +
           "AND c.crmInfoSetting.status = 0 " +
           "ORDER BY c.crmInfoSetting.columnPosition")
    List<CrmInfo> findActiveByUser(@Param("userId") Long userId);

    @Query("SELECT c FROM CrmInfo c WHERE c.user.id = :userId " +
           "AND c.crmInfoSetting.columnVisible = true " +
           "AND c.crmInfoSetting.status = 0 " +
           "ORDER BY c.crmInfoSetting.columnPosition")
    List<CrmInfo> findVisibleByUser(@Param("userId") Long userId);

    void deleteByUserId(Long userId);

    @Query("SELECT c FROM CrmInfo c WHERE c.user.client.id = :clientId " +
           "AND c.crmInfoSetting.id = :settingId")
    List<CrmInfo> findByClientAndSetting(@Param("clientId") Long clientId,
                                          @Param("settingId") Long settingId);
}
