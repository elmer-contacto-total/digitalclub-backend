package com.digitalgroup.holape.domain.audit.annotation;

import com.digitalgroup.holape.domain.audit.listener.AuditEntityListener;
import jakarta.persistence.EntityListeners;

import java.lang.annotation.*;

/**
 * Marks an entity as auditable.
 * Equivalent to Rails audited gem's `audited` declaration.
 *
 * Usage:
 * @Entity
 * @Auditable
 * public class User { ... }
 *
 * Or with options:
 * @Entity
 * @Auditable(only = {"firstName", "lastName", "email"})
 * public class User { ... }
 *
 * This annotation automatically adds the AuditEntityListener.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@EntityListeners(AuditEntityListener.class)
public @interface Auditable {

    /**
     * Fields to audit (if empty, all fields are audited)
     */
    String[] only() default {};

    /**
     * Fields to exclude from auditing
     */
    String[] except() default {};

    /**
     * Associated entity type for polymorphic auditing
     */
    String associated() default "";

    /**
     * Whether to audit on create
     */
    boolean onCreate() default true;

    /**
     * Whether to audit on update
     */
    boolean onUpdate() default true;

    /**
     * Whether to audit on destroy
     */
    boolean onDestroy() default true;
}
