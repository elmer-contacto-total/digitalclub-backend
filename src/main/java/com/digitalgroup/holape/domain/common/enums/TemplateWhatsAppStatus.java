package com.digitalgroup.holape.domain.common.enums;

import lombok.Getter;

@Getter
public enum TemplateWhatsAppStatus {
    DRAFT(0),
    PENDING_APPROVAL(1),
    APPROVED(2),
    REJECTED(3);

    private final int value;

    TemplateWhatsAppStatus(int value) {
        this.value = value;
    }

    public static TemplateWhatsAppStatus fromValue(int value) {
        for (TemplateWhatsAppStatus status : TemplateWhatsAppStatus.values()) {
            if (status.value == value) {
                return status;
            }
        }
        return DRAFT;
    }

    public boolean isApproved() {
        return this == APPROVED;
    }

    public boolean canSend() {
        return this == APPROVED;
    }
}
