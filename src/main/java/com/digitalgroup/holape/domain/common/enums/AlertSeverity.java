package com.digitalgroup.holape.domain.common.enums;

import lombok.Getter;

@Getter
public enum AlertSeverity {
    INFO(0),
    WARNING(1),
    PRIORITY(2),
    SUCCESS(3),
    HIGH(4);

    private final int value;

    AlertSeverity(int value) {
        this.value = value;
    }

    public static AlertSeverity fromValue(int value) {
        for (AlertSeverity severity : AlertSeverity.values()) {
            if (severity.value == value) {
                return severity;
            }
        }
        return INFO;
    }
}
