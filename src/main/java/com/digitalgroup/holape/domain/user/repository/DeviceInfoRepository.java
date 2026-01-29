package com.digitalgroup.holape.domain.user.repository;

import com.digitalgroup.holape.domain.user.entity.DeviceInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * DeviceInfo Repository
 *
 * PARIDAD RAILS: schema.rb líneas 170-179
 * Campos: user_id, device_id, token, device_type, status
 * - status = 0 → activo
 * - status = 1 → inactivo
 * - token contiene el FCM token (no existe campo fcmToken)
 */
@Repository
public interface DeviceInfoRepository extends JpaRepository<DeviceInfo, Long> {

    List<DeviceInfo> findByUserId(Long userId);

    Optional<DeviceInfo> findByUserIdAndDeviceId(Long userId, String deviceId);

    // PARIDAD RAILS: El campo es 'token', no 'fcmToken'
    Optional<DeviceInfo> findByToken(String token);

    // PARIDAD RAILS: status = 0 significa activo
    @Query("SELECT d FROM DeviceInfo d WHERE d.user.id = :userId AND d.status = 0")
    List<DeviceInfo> findActiveByUserId(@Param("userId") Long userId);

    // PARIDAD RAILS: status = 0 significa activo
    @Query("SELECT d FROM DeviceInfo d JOIN d.user u WHERE u.client.id = :clientId AND d.status = 0")
    List<DeviceInfo> findActiveByClientId(@Param("clientId") Long clientId);

    // PARIDAD RAILS: status = 0 y token no null
    @Query("SELECT d FROM DeviceInfo d WHERE d.status = 0 AND d.token IS NOT NULL")
    List<DeviceInfo> findAllActiveWithToken();

    void deleteByUserIdAndDeviceId(Long userId, String deviceId);

    // PARIDAD RAILS: Desactivar = status = 1
    @Query("UPDATE DeviceInfo d SET d.status = 1 WHERE d.user.id = :userId")
    void deactivateAllByUserId(@Param("userId") Long userId);
}
