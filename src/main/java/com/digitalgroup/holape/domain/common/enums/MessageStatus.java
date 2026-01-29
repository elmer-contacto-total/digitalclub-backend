package com.digitalgroup.holape.domain.common.enums;

import lombok.Getter;

@Getter
public enum MessageStatus {
    SENT(0),
    ERROR(1),
    UNREAD(2),
    READ(3),
    PENDING(4),
    FAILED(5);

    private final int value;

    MessageStatus(int value) {
        this.value = value;
    }

    public static MessageStatus fromValue(int value) {
        for (MessageStatus status : MessageStatus.values()) {
            if (status.value == value) {
                return status;
            }
        }
        return SENT;
    }
}
