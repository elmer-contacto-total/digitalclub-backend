package com.digitalgroup.holape.domain.common.enums;

import lombok.Getter;

@Getter
public enum TicketStatus {
    OPEN(0),
    CLOSED(1);

    private final int value;

    TicketStatus(int value) {
        this.value = value;
    }

    public static TicketStatus fromValue(int value) {
        for (TicketStatus status : TicketStatus.values()) {
            if (status.value == value) {
                return status;
            }
        }
        return OPEN;
    }

    public boolean isOpen() {
        return this == OPEN;
    }

    public boolean isClosed() {
        return this == CLOSED;
    }
}
