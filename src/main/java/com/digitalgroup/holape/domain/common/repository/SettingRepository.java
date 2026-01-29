package com.digitalgroup.holape.domain.common.repository;

import com.digitalgroup.holape.domain.common.entity.Setting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SettingRepository extends JpaRepository<Setting, Long> {

    Optional<Setting> findByName(String name);

    List<Setting> findByInternal(Boolean internal);

    List<Setting> findByStatus(Integer status);

    @Query("SELECT s FROM Setting s WHERE s.internal = false OR s.internal IS NULL")
    List<Setting> findPublicSettings();

    @Query("SELECT s FROM Setting s WHERE s.name LIKE :prefix%")
    List<Setting> findByNamePrefix(@Param("prefix") String prefix);

    boolean existsByName(String name);
}
