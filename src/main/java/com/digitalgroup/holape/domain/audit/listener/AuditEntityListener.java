package com.digitalgroup.holape.domain.audit.listener;

import com.digitalgroup.holape.domain.audit.annotation.Auditable;
import com.digitalgroup.holape.domain.audit.entity.Audit;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.security.CustomUserDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.*;

/**
 * JPA Entity Listener for automatic auditing
 * Equivalent to Rails audited gem
 *
 * Uses JdbcTemplate for INSERT to avoid ConcurrentModificationException
 * (auditRepository.save() inside @PostUpdate modifies Hibernate's ActionQueue during flush)
 */
@Slf4j
@Component
public class AuditEntityListener {

    private static JdbcTemplate jdbcTemplate;
    private static ObjectMapper objectMapper;

    private static final String INSERT_SQL =
            "INSERT INTO audits (auditable_id, auditable_type, associated_id, associated_type, " +
            "user_id, user_type, username, action, audited_changes, version, comment, " +
            "remote_address, request_uuid, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, NOW())";

    private static final String COUNT_SQL =
            "SELECT COUNT(*) FROM audits WHERE auditable_type = ? AND auditable_id = ?";

    // Thread-local storage for pre-update entity state
    private static final ThreadLocal<Map<Object, Map<String, Object>>> preUpdateState = new ThreadLocal<>();

    // Thread-local storage for entity state captured at load time (@PostLoad)
    private static final ThreadLocal<Map<Object, Map<String, Object>>> postLoadState = new ThreadLocal<>();

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
            "client", "user", "manager", "agent", "sender", "recipient", "ticket",
            "country", "language"
    );

    @Autowired
    public void setJdbcTemplate(JdbcTemplate template) {
        AuditEntityListener.jdbcTemplate = template;
    }

    @Autowired
    public void setObjectMapper(ObjectMapper mapper) {
        AuditEntityListener.objectMapper = mapper;
    }

    /**
     * Capture entity state when loaded from DB (before any setters are called).
     * This is the ONLY reliable way to get OLD values for update auditing in JPA,
     * because @PreUpdate fires after setters have already modified the entity.
     */
    @PostLoad
    public void postLoad(Object entity) {
        try {
            Auditable auditable = entity.getClass().getAnnotation(Auditable.class);
            if (auditable == null || !auditable.onUpdate()) {
                return;
            }
            Map<String, Object> state = extractFieldValues(entity, auditable);
            Map<Object, Map<String, Object>> states = postLoadState.get();
            if (states == null) {
                states = new IdentityHashMap<>();
                postLoadState.set(states);
            }
            states.put(entity, state);
        } catch (Exception e) {
            log.error("Error capturing post-load state for {}", entity.getClass().getSimpleName(), e);
        }
    }

    /**
     * Transfer old state from @PostLoad snapshot to preUpdateState for @PostUpdate to use.
     */
    @PreUpdate
    public void preUpdate(Object entity) {
        try {
            Auditable auditable = entity.getClass().getAnnotation(Auditable.class);
            if (auditable != null && !auditable.onUpdate()) {
                return;
            }

            // Get the OLD state captured at @PostLoad time (before setters were called)
            Map<Object, Map<String, Object>> loadStates = postLoadState.get();
            if (loadStates == null) {
                return;
            }

            Map<String, Object> oldState = loadStates.remove(entity);
            if (loadStates.isEmpty()) {
                postLoadState.remove();
            }
            if (oldState == null) {
                return;
            }

            // Transfer to preUpdateState for @PostUpdate to consume
            Map<Object, Map<String, Object>> states = preUpdateState.get();
            if (states == null) {
                states = new IdentityHashMap<>();
                preUpdateState.set(states);
            }
            states.put(entity, oldState);
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
            if (isAuditingDisabled()) {
                return;
            }

            Auditable auditable = entity.getClass().getAnnotation(Auditable.class);
            if (auditable != null && !auditable.onCreate()) {
                return;
            }

            Long entityId = getEntityId(entity);
            Map<String, Object> newValues = extractFieldValues(entity, auditable);

            Audit audit = new Audit();
            audit.setAuditableType(entity.getClass().getSimpleName());
            audit.setAuditableId(entityId);
            audit.setAction("create");
            audit.setVersion(1);
            audit.setAuditedChanges(newValues);
            populateUserInfo(audit);
            populateRequestInfo(audit);
            populateAssociatedInfo(audit, entity, auditable);

            saveAuditViaJdbc(audit);
            log.debug("Audit: created {} {}", entity.getClass().getSimpleName(), entityId);
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
            if (isAuditingDisabled()) {
                return;
            }

            Auditable auditable = entity.getClass().getAnnotation(Auditable.class);
            if (auditable != null && !auditable.onUpdate()) {
                return;
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
                return;
            }

            // Get current version count via JDBC (not JPA, to avoid ActionQueue issues)
            long version = countAuditsViaJdbc(entity.getClass().getSimpleName(), entityId);

            Audit audit = new Audit();
            audit.setAuditableType(entity.getClass().getSimpleName());
            audit.setAuditableId(entityId);
            audit.setAction("update");
            audit.setAuditedChanges(changes);
            audit.setVersion((int) version + 1);
            populateUserInfo(audit);
            populateRequestInfo(audit);
            populateAssociatedInfo(audit, entity, auditable);

            saveAuditViaJdbc(audit);
            log.debug("Audit: updated {} {} with {} changes",
                    entity.getClass().getSimpleName(), entityId, changes.size());
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
            if (isAuditingDisabled()) {
                return;
            }

            Auditable auditable = entity.getClass().getAnnotation(Auditable.class);
            if (auditable != null && !auditable.onDestroy()) {
                return;
            }

            Long entityId = getEntityId(entity);
            Map<String, Object> oldValues = extractFieldValues(entity, auditable);

            long version = countAuditsViaJdbc(entity.getClass().getSimpleName(), entityId);

            Audit audit = new Audit();
            audit.setAuditableType(entity.getClass().getSimpleName());
            audit.setAuditableId(entityId);
            audit.setAction("destroy");
            audit.setVersion((int) version + 1);
            audit.setAuditedChanges(oldValues);
            populateUserInfo(audit);
            populateRequestInfo(audit);
            populateAssociatedInfo(audit, entity, auditable);

            saveAuditViaJdbc(audit);
            log.debug("Audit: destroyed {} {}", entity.getClass().getSimpleName(), entityId);
        } catch (Exception e) {
            log.error("Error logging destroy audit for {}", entity.getClass().getSimpleName(), e);
        }
    }

    /**
     * Save audit record via JDBC to avoid ConcurrentModificationException.
     * Uses the same JDBC connection/transaction as the main operation.
     */
    private void saveAuditViaJdbc(Audit audit) {
        if (jdbcTemplate == null || objectMapper == null) {
            return;
        }
        try {
            String changesJson = objectMapper.writeValueAsString(audit.getAuditedChanges());
            Long userId = audit.getUser() != null ? audit.getUser().getId() : null;

            jdbcTemplate.update(INSERT_SQL,
                    audit.getAuditableId(),
                    audit.getAuditableType(),
                    audit.getAssociatedId(),
                    audit.getAssociatedType(),
                    userId,
                    audit.getUserType(),
                    audit.getUsername(),
                    audit.getAction(),
                    changesJson,
                    audit.getVersion(),
                    audit.getComment(),
                    audit.getRemoteAddress(),
                    audit.getRequestUuid());
        } catch (Exception e) {
            log.error("Error saving audit via JDBC for {} {}", audit.getAuditableType(), audit.getAuditableId(), e);
        }
    }

    /**
     * Count existing audits via JDBC (avoids JPA ActionQueue issues)
     */
    private long countAuditsViaJdbc(String auditableType, Long auditableId) {
        if (jdbcTemplate == null) {
            return 0;
        }
        try {
            Long count = jdbcTemplate.queryForObject(COUNT_SQL, Long.class, auditableType, auditableId);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.error("Error counting audits via JDBC", e);
            return 0;
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
                    continue;
                }
                if (exceptFields.contains(fieldName)) {
                    continue;
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
                        } else if (isJsonbField(field)) {
                            // JSONB/JSON fields: serialize to JSON string for auditing
                            values.put(fieldName, serializeJsonValue(value));
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
     * Check if field is a JSONB/JSON database column that should be audited
     * even though its Java type (Map, Object) would normally be skipped.
     */
    private boolean isJsonbField(Field field) {
        // Check @Column(columnDefinition = "jsonb")
        Column column = field.getAnnotation(Column.class);
        if (column != null && column.columnDefinition().toLowerCase().contains("jsonb")) {
            return true;
        }
        // Check for JSON type annotations by name (avoids hard dependency on Hibernate-specific classes)
        for (Annotation ann : field.getAnnotations()) {
            String annName = ann.annotationType().getSimpleName();
            if ("JdbcTypeCode".equals(annName) || "Type".equals(annName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Serialize a JSONB value for audit storage
     */
    private Object serializeJsonValue(Object value) {
        if (objectMapper == null) {
            return value.toString();
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return value.toString();
        }
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
