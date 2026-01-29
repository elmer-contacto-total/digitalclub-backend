package com.digitalgroup.holape.domain.common.enums;

import lombok.Getter;

@Getter
public enum KpiType {
    NEW_CLIENT(0),
    NEW_TICKET(1),
    OPEN_CASE(2),
    FIRST_RESPONSE_TIME(3),
    RESPONDED_TO_CLIENT(4),
    CLOSED_TICKET(5),
    SENT_MESSAGE(6),
    REQUIRE_RESPONSE(7),
    AUTO_CLOSED_TICKET(8),
    CLOSED_CON_ACUERDO(9),
    CLOSED_SIN_ACUERDO(10),
    TMO(11),
    UNIQUE_RESPONDED_TO_CLIENT(12);

    private final int value;

    KpiType(int value) {
        this.value = value;
    }

    public static KpiType fromValue(int value) {
        for (KpiType type : KpiType.values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown KpiType value: " + value);
    }

    public boolean isResponseMetric() {
        return this == FIRST_RESPONSE_TIME || this == RESPONDED_TO_CLIENT ||
               this == UNIQUE_RESPONDED_TO_CLIENT || this == REQUIRE_RESPONSE;
    }

    public boolean isTicketMetric() {
        return this == NEW_TICKET || this == CLOSED_TICKET ||
               this == AUTO_CLOSED_TICKET || this == TMO;
    }
}
