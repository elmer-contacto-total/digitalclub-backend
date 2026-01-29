package com.digitalgroup.holape.domain.common.enums;

import lombok.Getter;

@Getter
public enum DocType {
    RUC(0),
    DNI(1),
    PASSPORT(2);

    private final int value;

    DocType(int value) {
        this.value = value;
    }

    public static DocType fromValue(int value) {
        for (DocType type : DocType.values()) {
            if (type.value == value) {
                return type;
            }
        }
        return DNI;
    }
}
