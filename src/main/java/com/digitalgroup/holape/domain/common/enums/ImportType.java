package com.digitalgroup.holape.domain.common.enums;

/**
 * Import type enum matching Rails Import model.
 * Rails: enum import_type: { users: 0 }
 */
public enum ImportType {
    USERS(0);

    private final int value;

    ImportType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
