package com.digitalgroup.holape.domain.common.enums;

import lombok.Getter;

@Getter
public enum ClientType {
    WHATSAPP_APP(0),
    WHATSAPP_BUSINESS(1),
    POINT_TO_POINT_ONLY(2);

    private final int value;

    ClientType(int value) {
        this.value = value;
    }

    public static ClientType fromValue(int value) {
        for (ClientType type : ClientType.values()) {
            if (type.value == value) {
                return type;
            }
        }
        return WHATSAPP_APP;
    }

    public boolean isWhatsAppBusiness() {
        return this == WHATSAPP_BUSINESS;
    }
}
