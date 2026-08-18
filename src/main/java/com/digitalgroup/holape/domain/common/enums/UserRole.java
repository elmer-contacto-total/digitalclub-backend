package com.digitalgroup.holape.domain.common.enums;

import lombok.Getter;

@Getter
public enum UserRole {
    STANDARD(0),
    SUPER_ADMIN(1),
    ADMIN(2),
    MANAGER_LEVEL_1(3),
    MANAGER_LEVEL_2(4),
    MANAGER_LEVEL_3(5),
    MANAGER_LEVEL_4(6),
    AGENT(7),
    STAFF(8),
    WHATSAPP_BUSINESS(9);

    private final int value;

    UserRole(int value) {
        this.value = value;
    }

    public static UserRole fromValue(int value) {
        for (UserRole role : UserRole.values()) {
            if (role.value == value) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown UserRole value: " + value);
    }

    /**
     * Parse role from string name (case-insensitive).
     * PARIDAD RAILS: Import CSV may contain role names like "standard", "agent", etc.
     * Returns STANDARD for null/blank/unknown values.
     */
    public static UserRole fromString(String roleName) {
        if (roleName == null || roleName.isBlank()) return STANDARD;
        return switch (roleName.trim().toLowerCase()) {
            case "standard" -> STANDARD;
            case "super_admin" -> SUPER_ADMIN;
            case "admin" -> ADMIN;
            case "manager_level_1" -> MANAGER_LEVEL_1;
            case "manager_level_2" -> MANAGER_LEVEL_2;
            case "manager_level_3" -> MANAGER_LEVEL_3;
            case "manager_level_4" -> MANAGER_LEVEL_4;
            case "agent" -> AGENT;
            case "staff" -> STAFF;
            case "whatsapp_business" -> WHATSAPP_BUSINESS;
            default -> STANDARD;
        };
    }

    public boolean isAdmin() {
        return this == SUPER_ADMIN || this == ADMIN;
    }

    public boolean isManager() {
        return this == MANAGER_LEVEL_1 || this == MANAGER_LEVEL_2 ||
               this == MANAGER_LEVEL_3 || this == MANAGER_LEVEL_4;
    }

    public boolean isAgent() {
        return this == AGENT;
    }

    public boolean isInternal() {
        return this != STANDARD && this != WHATSAPP_BUSINESS;
    }

    /**
     * Rango de privilegio para decisiones de autorizacion. Mayor = mas privilegio.
     *
     * IMPORTANTE: NO usar getValue() para esto — ese es el codigo persistido en BD
     * (STANDARD=0, SUPER_ADMIN=1, ...) y su orden NO representa jerarquia.
     *
     * El orden sale de la jerarquia que ya declara UserAdminController#availableManagers:
     * standard -> agent -> manager_level_4 -> ... -> manager_level_1 -> admin -> super_admin
     */
    public int rank() {
        return switch (this) {
            case SUPER_ADMIN       -> 100;
            case ADMIN             -> 90;
            case MANAGER_LEVEL_1   -> 80;
            case MANAGER_LEVEL_2   -> 70;
            case MANAGER_LEVEL_3   -> 60;
            case MANAGER_LEVEL_4   -> 50;
            case STAFF             -> 40;
            case AGENT             -> 30;
            case WHATSAPP_BUSINESS -> 10;
            case STANDARD          -> 0;
        };
    }

    /** true si este rol tiene privilegio ESTRICTAMENTE mayor que el otro. */
    public boolean outranks(UserRole other) {
        return other != null && this.rank() > other.rank();
    }

    public boolean canManageUsers() {
        return this == SUPER_ADMIN || this == ADMIN || this == STAFF || isManager();
    }
}
