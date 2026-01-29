package com.digitalgroup.holape.domain.user.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Device Info entity
 * Equivalent to Rails DeviceInfo model
 * Stores user device information for push notifications
 *
 * PARIDAD RAILS: schema.rb líneas 170-179
 * Campos: user_id, device_id, token, device_type, status, created_at, updated_at
 */
@Entity
@Table(name = "device_infos", indexes = {
        @Index(name = "index_device_infos_on_user_id", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
public class DeviceInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "token", columnDefinition = "text")
    private String token;

    @Column(name = "device_type")
    private Integer deviceType = 0;

    @Column(name = "status")
    private Integer status = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (deviceType == null) deviceType = 0;
        if (status == null) status = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Check if device is active (status = 0)
     */
    public boolean isActive() {
        return status != null && status == 0;
    }

    /**
     * Deactivate device (set status = 1)
     */
    public void deactivate() {
        this.status = 1;
    }

    /**
     * Activate device (set status = 0)
     */
    public void activate() {
        this.status = 0;
    }

    /**
     * Get FCM/Push token (alias for token field)
     * For compatibility with services that expect fcmToken
     */
    public String getFcmToken() {
        return this.token;
    }

    /**
     * Set FCM/Push token (alias for token field)
     */
    public void setFcmToken(String fcmToken) {
        this.token = fcmToken;
    }

    /**
     * Get active status as boolean
     */
    public Boolean getActive() {
        return isActive();
    }

    /**
     * Set active status
     */
    public void setActive(Boolean active) {
        if (Boolean.TRUE.equals(active)) {
            activate();
        } else {
            deactivate();
        }
    }
}
