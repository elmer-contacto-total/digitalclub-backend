package com.digitalgroup.holape.domain.common.enums;

import lombok.Getter;

@Getter
public enum MessageDirection {
    INCOMING(0),
    OUTGOING(1);

    private final int value;

    MessageDirection(int value) {
        this.value = value;
    }

    public static MessageDirection fromValue(int value) {
        for (MessageDirection direction : MessageDirection.values()) {
            if (direction.value == value) {
                return direction;
            }
        }
        return INCOMING;
    }

    public boolean isIncoming() {
        return this == INCOMING;
    }

    public boolean isOutgoing() {
        return this == OUTGOING;
    }
}
