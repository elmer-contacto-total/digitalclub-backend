package com.digitalgroup.holape.api.v1;

import com.digitalgroup.holape.domain.user.entity.DeviceInfo;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.DeviceInfoRepository;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import com.digitalgroup.holape.integration.firebase.PushNotificationService;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Device Controller
 * Handles device registration for push notifications
 *
 * PARIDAD RAILS: device_infos table
 * Campos: user_id, device_id, token, device_type, status
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceInfoRepository deviceInfoRepository;
    private final UserRepository userRepository;
    private final PushNotificationService pushNotificationService;

    /**
     * Register or update device for push notifications
     * PARIDAD RAILS: Campos disponibles: device_id, token, device_type, status
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerDevice(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody RegisterDeviceRequest request) {

        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User", currentUser.getId()));

        // Find existing device or create new
        DeviceInfo device = deviceInfoRepository
                .findByUserIdAndDeviceId(user.getId(), request.deviceId())
                .orElse(new DeviceInfo());

        device.setUser(user);
        device.setDeviceId(request.deviceId());
        device.setToken(request.token()); // FCM token stored in 'token' field
        if (request.deviceType() != null) {
            device.setDeviceType(request.deviceType());
        }
        device.setStatus(0); // Active

        device = deviceInfoRepository.save(device);

        // Subscribe to client topic for broadcasts
        if (user.getClient() != null && pushNotificationService != null) {
            try {
                pushNotificationService.subscribeUserToClientTopic(user.getId(), user.getClient().getId());
            } catch (Exception e) {
                log.warn("Failed to subscribe to client topic: {}", e.getMessage());
            }
        }

        log.info("Registered device {} for user {}", request.deviceId(), user.getId());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "device_id", device.getDeviceId()
        ));
    }

    /**
     * Update FCM token
     */
    @PutMapping("/token")
    public ResponseEntity<Map<String, Object>> updateToken(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody UpdateTokenRequest request) {

        DeviceInfo device = deviceInfoRepository
                .findByUserIdAndDeviceId(currentUser.getId(), request.deviceId())
                .orElseThrow(() -> new ResourceNotFoundException("Device", request.deviceId()));

        device.setToken(request.token());
        deviceInfoRepository.save(device);

        return ResponseEntity.ok(Map.of(
                "result", "success"
        ));
    }

    /**
     * Unregister device (deactivate)
     */
    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Map<String, Object>> unregisterDevice(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @PathVariable String deviceId) {

        DeviceInfo device = deviceInfoRepository
                .findByUserIdAndDeviceId(currentUser.getId(), deviceId)
                .orElse(null);

        if (device != null) {
            // Unsubscribe from topics
            User user = device.getUser();
            if (user.getClient() != null && pushNotificationService != null) {
                try {
                    pushNotificationService.unsubscribeUserFromClientTopic(user.getId(), user.getClient().getId());
                } catch (Exception e) {
                    log.warn("Failed to unsubscribe from client topic: {}", e.getMessage());
                }
            }

            device.setStatus(1); // Inactive
            device.setToken(null);
            deviceInfoRepository.save(device);

            log.info("Unregistered device {} for user {}", deviceId, currentUser.getId());
        }

        return ResponseEntity.ok(Map.of(
                "result", "success"
        ));
    }

    /**
     * Get all devices for current user
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getDevices(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        var devices = deviceInfoRepository.findByUserId(currentUser.getId());

        var deviceList = devices.stream().map(d -> Map.of(
                "id", d.getId(),
                "device_id", d.getDeviceId(),
                "device_type", d.getDeviceType() != null ? d.getDeviceType() : 0,
                "status", d.getStatus() != null ? d.getStatus() : 0,
                "active", d.isActive(),
                "created_at", d.getCreatedAt(),
                "updated_at", d.getUpdatedAt()
        )).toList();

        return ResponseEntity.ok(Map.of(
                "devices", deviceList
        ));
    }

    /**
     * Request DTO for registering device
     * PARIDAD RAILS: Campos de device_infos
     */
    public record RegisterDeviceRequest(
            String deviceId,
            String token,      // FCM token
            Integer deviceType // 0 = default
    ) {}

    public record UpdateTokenRequest(
            String deviceId,
            String token
    ) {}
}
