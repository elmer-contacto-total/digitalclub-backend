package com.digitalgroup.holape.domain.common.enums;

import lombok.Getter;

@Getter
public enum AlertType {
    CONVERSATION_RESPONSE_OVERDUE(0),
    REQUIRE_RESPONSE(1),
    ESCALATION(2);

    private final int value;

    AlertType(int value) {
        this.value = value;
    }

    public static AlertType fromValue(int value) {
        for (AlertType type : AlertType.values()) {
            if (type.value == value) {
                return type;
            }
        }
        return CONVERSATION_RESPONSE_OVERDUE;
    }
}
