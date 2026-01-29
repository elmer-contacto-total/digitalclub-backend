package com.digitalgroup.holape.domain.common.enums;

import lombok.Getter;

@Getter
public enum Status {
    ACTIVE(0),
    INACTIVE(1),
    PENDING(2),
    DELETED(3),
    ARCHIVED(4);

    private final int value;

    Status(int value) {
        this.value = value;
    }

    public static Status fromValue(int value) {
        for (Status status : Status.values()) {
            if (status.value == value) {
                return status;
            }
        }
        return ACTIVE;
    }

    public boolean isActive() {
        return this == ACTIVE;
    }
}
