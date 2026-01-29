package com.digitalgroup.holape.domain.client.repository;

import com.digitalgroup.holape.domain.client.entity.ClientSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientSettingRepository extends JpaRepository<ClientSetting, Long> {

    List<ClientSetting> findByClientId(Long clientId);

    Optional<ClientSetting> findByClientIdAndName(Long clientId, String name);

    @Query("SELECT cs FROM ClientSetting cs WHERE cs.client.id = :clientId AND cs.internal = false")
    List<ClientSetting> findPublicSettingsByClient(@Param("clientId") Long clientId);

    @Query("SELECT cs.stringValue FROM ClientSetting cs WHERE cs.client.id = :clientId AND cs.name = :name")
    Optional<String> findStringValueByClientAndName(@Param("clientId") Long clientId, @Param("name") String name);

    @Query("SELECT cs.integerValue FROM ClientSetting cs WHERE cs.client.id = :clientId AND cs.name = :name")
    Optional<Integer> findIntegerValueByClientAndName(@Param("clientId") Long clientId, @Param("name") String name);

    /**
     * Check if a client has a specific setting with non-null hash_value
     * PARIDAD RAILS: Equivalent to client.client_settings.find_by(name: 'X').try(:hash_value).present?
     */
    @Query("SELECT CASE WHEN COUNT(cs) > 0 THEN true ELSE false END FROM ClientSetting cs " +
            "WHERE cs.client.id = :clientId AND cs.name = :name AND cs.hashValue IS NOT NULL")
    boolean existsByClientIdAndNameWithHashValue(@Param("clientId") Long clientId, @Param("name") String name);
}
