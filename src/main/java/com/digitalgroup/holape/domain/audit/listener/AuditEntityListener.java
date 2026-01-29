package com.digitalgroup.holape.domain.audit.listener;

import com.digitalgroup.holape.domain.audit.annotation.Auditable;
import com.digitalgroup.holape.domain.audit.entity.Audit;
import com.digitalgroup.holape.domain.audit.repository.AuditRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.security.CustomUserDetails;
import jakarta.persistence.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.*;

/**
 * JPA Entity Listener for automatic auditing
 * Equivalent to Rails audited gem
 *
 * Usage: Add @EntityListeners(AuditEntityListener.class) to entities
 *
 * Example:
 * @Entity
 * @EntityListeners(AuditEntityListener.class)
 * public class User { ... }
 */
@Slf4j
@Component
public class AuditEntityListener {

    private static AuditRepository auditRepository;

    // Thread-local storage for pre-update entity state
    private static final ThreadLocal<Map<Object, Map<String, Object>>> preUpdateState = new ThreadLocal<>();

    // PARIDAD RAILS: Thread-local flag to disable auditing (equivalent to User.without_auditing)
    private static final ThreadLocal<Boolean> auditingDisabled = ThreadLocal.withInitial(() -> false);

    /**
     * Disable auditing for the current thread
     * PARIDAD RAILS: Equivalent to User.without_auditing do ... end
     */
    public static void disableAuditing() {
        auditingDisabled.set(true);
    }

    /**
     * Enable auditing for the current thread
     */
    public static void enableAuditing() {
        auditingDisabled.set(false);
    }

    /**
     * Check if auditing is disabled for the current thread
     */
    public static boolean isAuditingDisabled() {
        return Boolean.TRUE.equals(auditingDisabled.get());
    }

    /**
     * Execute a runnable without auditing
     * PARIDAD RAILS: Equivalent to User.without_auditing do ... end
     */
    public static void withoutAuditing(Runnable action) {
        try {
            disableAuditing();
            action.run();
        } finally {
            enableAuditing();
        }
    }

    // Fields to exclude from auditing
    private static final Set<String> EXCLUDED_FIELDS = Set.of(
            "encryptedPassword", "password", "tempPassword", "otp",
            "serialVersionUID", "createdAt", "updatedAt"
    );

    // Fields that are entity relationships (should capture ID only)
    private static final Set<String> RELATIONSHIP_FIELDS = Set.of(
            "client", "user", "manager", "agent", "sender", "recipient", "ticket"
    );

    @Autowired
    public void setAuditRepository(@Lazy AuditRepository repository) {
        AuditEntityListener.auditRepository = repository;
    }

    /**
     * Capture entity state before update
     */
    @PreUpdate
    public void preUpdate(Object entity) {
        try {
            Auditable auditable = entity.getClass().getAnnotation(Auditable.class);
            if (auditable != null && !auditable.onUpdate()) {
                return; // Skip if onUpdate is disabled
            }

            Map<String, Object> currentState = extractFieldValues(entity, auditable);
            Map<Object, Map<String, Object>> states = preUpdateState.get();
            if (states == null) {
                states = new HashMap<>();
                preUpdateState.set(states);
            }
            states.put(entity, currentState);
        } catch (Exception e) {
            log.error("Error capturing pre-update state for {}", entity.getClass().getSimpleName(), e);
        }
    }

    /**
     * Log entity creation
     */
    @PostPersist
    public void postPersist(Object entity) {
        try {
            // PARIDAD RAILS: Check if auditing is disabled (without_auditing block)
            if (isAuditingDisabled()) {
                return;
            }

            Auditable auditable = entity.getClass().getAnnotation(Auditable.class);
            if (auditable != null && !auditable.onCreate()) {
                return; // Skip if onCreate is disabled
            }

            Long entityId = getEntityId(entity);
            Map<String, Object> newValues = extractFieldValues(entity, auditable);

            Audit audit = new Audit();
            audit.setAuditableType(entity.getClass().getSimpleName());
            audit.setAuditableId(entityId);
            audit.setAction("create");
            audit.setAuditedChanges(newValues);
            populateUserInfo(audit);
            populateRequestInfo(audit);
            populateAssociatedInfo(audit, entity, auditable);

            if (auditRepository != null) {
                auditRepository.save(audit);
                log.debug("Audit: created {} {}", entity.getClass().getSimpleName(), entityId);
            }
        } catch (Exception e) {
            log.error("Error logging create audit for {}", entity.getClass().getSimpleName(), e);
        }
    }

    /**
     * Log entity update
     */
    @PostUpdate
    public void postUpdate(Object entity) {
        try {
            // PARIDAD RAILS: Check if auditing is disabled (without_auditing block)
            if (isAuditingDisabled()) {
                return;
            }

            Auditable auditable = entity.getClass().getAnnotation(Auditable.class);
            if (auditable != null && !auditable.onUpdate()) {
                return; // Skip if onUpdate is disabled
            }

            Map<Object, Map<String, Object>> states = preUpdateState.get();
            if (states == null) {
                return;
            }

            Map<String, Object> oldState = states.remove(entity);
            if (oldState == null) {
                return;
            }

            // Clean up if empty
            if (states.isEmpty()) {
                preUpdateState.remove();
            }

            Long entityId = getEntityId(entity);
            Map<String, Object> newState = extractFieldValues(entity, auditable);
            Map<String, Object> changes = calculateChanges(oldState, newState);

            if (changes.isEmpty()) {
                return; // No changes to audit
            }

            // Get current version count
            long version = auditRepository != null ?
                    auditRepository.countByAuditableTypeAndAuditableId(
                            entity.getClass().getSimpleName(), entityId) : 0;

            Audit audit = new Audit();
            audit.setAuditableType(entity.getClass().getSimpleName());
            audit.setAuditableId(entityId);
            audit.setAction("update");
            audit.setAuditedChanges(changes);
            audit.setVersion((int) version + 1);
            populateUserInfo(audit);
            populateRequestInfo(audit);
            populateAssociatedInfo(audit, entity, auditable);

            if (auditRepository != null) {
                auditRepository.save(audit);
                log.debug("Audit: updated {} {} with {} changes",
                        entity.getClass().getSimpleName(), entityId, changes.size());
            }
        } catch (Exception e) {
            log.error("Error logging update audit for {}", entity.getClass().getSimpleName(), e);
        }
    }

    /**
     * Log entity deletion
     */
    @PreRemove
    public void preRemove(Object entity) {
        try {
            // PARIDAD RAILS: Check if auditing is disabled (without_auditing block)
            if (isAuditingDisabled()) {
                return;
            }

            Auditable auditable = entity.getClass().getAnnotation(Auditable.class);
            if (auditable != null && !auditable.onDestroy()) {
                return; // Skip if onDestroy is disabled
            }

            Long entityId = getEntityId(entity);
            Map<String, Object> oldValues = extractFieldValues(entity, auditable);

            Audit audit = new Audit();
            audit.setAuditableType(entity.getClass().getSimpleName());
            audit.setAuditableId(entityId);
            audit.setAction("destroy");
            audit.setAuditedChanges(oldValues);
            populateUserInfo(audit);
            populateRequestInfo(audit);
            populateAssociatedInfo(audit, entity, auditable);

            if (auditRepository != null) {
                auditRepository.save(audit);
                log.debug("Audit: destroyed {} {}", entity.getClass().getSimpleName(), entityId);
            }
        } catch (Exception e) {
            log.error("Error logging destroy audit for {}", entity.getClass().getSimpleName(), e);
        }
    }

    /**
     * Extract entity ID using reflection
     */
    private Long getEntityId(Object entity) {
        try {
            Field idField = findIdField(entity.getClass());
            if (idField != null) {
                idField.setAccessible(true);
                Object id = idField.get(entity);
                if (id instanceof Long) {
                    return (Long) id;
                } else if (id != null) {
                    return Long.parseLong(id.toString());
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract entity ID", e);
        }
        return null;
    }

    /**
     * Find the @Id annotated field
     */
    private Field findIdField(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) {
                return field;
            }
        }
        // Check superclass
        if (clazz.getSuperclass() != null) {
            return findIdField(clazz.getSuperclass());
        }
        return null;
    }

    /**
     * Extract all field values from entity (without Auditable filter)
     */
    private Map<String, Object> extractFieldValues(Object entity) {
        return extractFieldValues(entity, null);
    }

    /**
     * Extract field values from entity with Auditable annotation filter
     */
    private Map<String, Object> extractFieldValues(Object entity, Auditable auditable) {
        Map<String, Object> values = new LinkedHashMap<>();

        if (entity == null) {
            return values;
        }

        // Get only/except field filters from annotation
        Set<String> onlyFields = auditable != null && auditable.only().length > 0 ?
                new HashSet<>(Arrays.asList(auditable.only())) : null;
        Set<String> exceptFields = auditable != null && auditable.except().length > 0 ?
                new HashSet<>(Arrays.asList(auditable.except())) : Set.of();

        Class<?> clazz = entity.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                String fieldName = field.getName();

                // Apply filters from @Auditable annotation
                if (onlyFields != null && !onlyFields.contains(fieldName)) {
                    continue; // Skip if not in 'only' list
                }
                if (exceptFields.contains(fieldName)) {
                    continue; // Skip if in 'except' list
                }
                if (EXCLUDED_FIELDS.contains(fieldName)) {
                    continue;
                }

                field.setAccessible(true);
                try {
                    Object value = field.get(entity);
                    if (value != null) {
                        // For relationship fields, extract the ID
                        if (RELATIONSHIP_FIELDS.contains(fieldName)) {
                            Long relatedId = getEntityId(value);
                            if (relatedId != null) {
                                values.put(fieldName + "_id", relatedId);
                            }
                        } else if (!isComplexType(field.getType())) {
                            values.put(fieldName, formatValue(value));
                        }
                    }
                } catch (IllegalAccessException e) {
                    // Skip fields that can't be accessed
                }
            }
            clazz = clazz.getSuperclass();
        }

        return values;
    }

    /**
     * Check if type is complex (collections, entities)
     */
    private boolean isComplexType(Class<?> type) {
        return Collection.class.isAssignableFrom(type) ||
               Map.class.isAssignableFrom(type) ||
               type.isAnnotationPresent(Entity.class);
    }

    /**
     * Format value for storage
     */
    private Object formatValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        return value.toString();
    }

    /**
     * Calculate changes between old and new state
     * Returns Map with format: { fieldName: [oldValue, newValue] }
     */
    private Map<String, Object> calculateChanges(Map<String, Object> oldState, Map<String, Object> newState) {
        Map<String, Object> changes = new LinkedHashMap<>();

        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(oldState.keySet());
        allKeys.addAll(newState.keySet());

        for (String key : allKeys) {
            Object oldValue = oldState.get(key);
            Object newValue = newState.get(key);

            if (!Objects.equals(oldValue, newValue)) {
                changes.put(key, List.of(
                        oldValue != null ? oldValue : "",
                        newValue != null ? newValue : ""
                ));
            }
        }

        return changes;
    }

    /**
     * Populate user information in audit
     */
    private void populateUserInfo(Audit audit) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof CustomUserDetails details) {
                User user = new User();
                user.setId(details.getId());
                audit.setUser(user);
                audit.setUsername(details.getUsername());
                audit.setUserType("User");
            }
        } catch (Exception e) {
            // No user context available
        }
    }

    /**
     * Populate request information in audit
     */
    private void populateRequestInfo(Audit audit) {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                String remoteAddr = attrs.getRequest().getRemoteAddr();
                audit.setRemoteAddress(remoteAddr);

                // Use request ID if available
                String requestId = attrs.getRequest().getHeader("X-Request-ID");
                audit.setRequestUuid(requestId != null ? requestId : UUID.randomUUID().toString());
            }
        } catch (Exception e) {
            // No request context available
        }
    }

    /**
     * Populate associated entity information in audit
     * Used for polymorphic associations (e.g., Message belongs_to :sender, :recipient)
     */
    private void populateAssociatedInfo(Audit audit, Object entity, Auditable auditable) {
        if (auditable == null || auditable.associated().isEmpty()) {
            return;
        }

        try {
            String associatedFieldName = auditable.associated();
            Field field = findField(entity.getClass(), associatedFieldName);

            if (field != null) {
                field.setAccessible(true);
                Object associatedEntity = field.get(entity);

                if (associatedEntity != null) {
                    audit.setAssociatedType(associatedEntity.getClass().getSimpleName());
                    audit.setAssociatedId(getEntityId(associatedEntity));
                }
            }
        } catch (Exception e) {
            log.debug("Could not populate associated info", e);
        }
    }

    /**
     * Find field by name in class hierarchy
     */
    private Field findField(Class<?> clazz, String fieldName) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}
