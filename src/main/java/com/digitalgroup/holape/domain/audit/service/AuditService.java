package com.digitalgroup.holape.domain.audit.service;

import com.digitalgroup.holape.domain.audit.entity.Audit;
import com.digitalgroup.holape.domain.audit.repository.AuditRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.multitenancy.TenantContext;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit Service
 * Provides auditing functionality similar to Rails audited gem
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditRepository auditRepository;

    public Page<Audit> findAll(Pageable pageable) {
        return auditRepository.findAll(pageable);
    }

    public Page<Audit> findByClient(Long clientId, Pageable pageable) {
        return auditRepository.findByClient(clientId, pageable);
    }

    public Page<Audit> findByDateRange(LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return auditRepository.findByDateRange(startDate, endDate, pageable);
    }

    public Page<Audit> findByClientAndDateRange(Long clientId, LocalDateTime startDate,
                                                  LocalDateTime endDate, Pageable pageable) {
        return auditRepository.findByClientAndDateRange(clientId, startDate, endDate, pageable);
    }

    public Page<Audit> findByEntity(String entityType, Long entityId, Pageable pageable) {
        return auditRepository.findByAuditableTypeAndAuditableId(entityType, entityId, pageable);
    }

    public List<String> getAuditableTypes() {
        return auditRepository.findDistinctAuditableTypes();
    }

    /**
     * Log creation of entity
     */
    @Async
    @Transactional
    public void logCreate(Object entity, Long entityId) {
        try {
            User currentUser = getCurrentUser();
            Map<String, Object> newValues = extractFieldValues(entity);

            Audit audit = Audit.forCreate(entity, currentUser, newValues);
            audit.setAuditableId(entityId);
            audit.setRemoteAddress(getRemoteAddress());

            auditRepository.save(audit);
            log.debug("Logged create audit for {} {}", entity.getClass().getSimpleName(), entityId);
        } catch (Exception e) {
            log.error("Failed to log create audit", e);
        }
    }

    /**
     * Log update of entity
     */
    @Async
    @Transactional
    public void logUpdate(Object entity, Long entityId, Map<String, Object> changes) {
        try {
            User currentUser = getCurrentUser();

            // Get current version
            long version = auditRepository.countByAuditableTypeAndAuditableId(
                    entity.getClass().getSimpleName(), entityId);

            Audit audit = Audit.forUpdate(entity, entityId, currentUser, changes, (int) version + 1);
            audit.setRemoteAddress(getRemoteAddress());

            auditRepository.save(audit);
            log.debug("Logged update audit for {} {}", entity.getClass().getSimpleName(), entityId);
        } catch (Exception e) {
            log.error("Failed to log update audit", e);
        }
    }

    /**
     * Log deletion of entity
     */
    @Async
    @Transactional
    public void logDestroy(Object entity, Long entityId) {
        try {
            User currentUser = getCurrentUser();
            Map<String, Object> oldValues = extractFieldValues(entity);

            Audit audit = Audit.forDestroy(entity, entityId, currentUser, oldValues);
            audit.setRemoteAddress(getRemoteAddress());

            auditRepository.save(audit);
            log.debug("Logged destroy audit for {} {}", entity.getClass().getSimpleName(), entityId);
        } catch (Exception e) {
            log.error("Failed to log destroy audit", e);
        }
    }

    /**
     * Log update with old and new values
     */
    @Async
    @Transactional
    public void logUpdate(Object entity, Long entityId, Object oldEntity, Object newEntity) {
        try {
            Map<String, Object> changes = calculateChanges(oldEntity, newEntity);
            if (!changes.isEmpty()) {
                logUpdate(entity, entityId, changes);
            }
        } catch (Exception e) {
            log.error("Failed to calculate and log changes", e);
        }
    }

    /**
     * Calculate changes between two entity states
     */
    private Map<String, Object> calculateChanges(Object oldEntity, Object newEntity) {
        Map<String, Object> changes = new HashMap<>();

        if (oldEntity == null || newEntity == null) {
            return changes;
        }

        Class<?> clazz = newEntity.getClass();
        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            try {
                Object oldValue = field.get(oldEntity);
                Object newValue = field.get(newEntity);

                if (!java.util.Objects.equals(oldValue, newValue)) {
                    changes.put(field.getName(), List.of(
                            oldValue != null ? oldValue.toString() : null,
                            newValue != null ? newValue.toString() : null
                    ));
                }
            } catch (IllegalAccessException e) {
                // Skip fields that can't be accessed
            }
        }

        return changes;
    }

    /**
     * Extract all field values from entity
     */
    private Map<String, Object> extractFieldValues(Object entity) {
        Map<String, Object> values = new HashMap<>();

        if (entity == null) {
            return values;
        }

        Class<?> clazz = entity.getClass();
        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            try {
                Object value = field.get(entity);
                if (value != null && !isExcludedField(field.getName())) {
                    values.put(field.getName(), value.toString());
                }
            } catch (IllegalAccessException e) {
                // Skip fields that can't be accessed
            }
        }

        return values;
    }

    private boolean isExcludedField(String fieldName) {
        // Exclude sensitive and internal fields
        return fieldName.equals("encryptedPassword") ||
               fieldName.equals("password") ||
               fieldName.equals("otp") ||
               fieldName.equals("serialVersionUID");
    }

    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails details) {
            User user = new User();
            user.setId(details.getId());
            return user;
        }
        return null;
    }

    private String getRemoteAddress() {
        // Would need to be set via request context
        return null;
    }
}
