package com.digitalgroup.holape.domain.common.enums;

/**
 * Message Type Enum
 * Defines the channel through which a message was sent/received
 */
public enum MessageType {
    WHATSAPP,       // WhatsApp message (via Cloud API)
    SMS,            // SMS message (via Twilio)
    INTERNAL,       // Internal system message
    TEMPLATE,       // WhatsApp template message
    PUSH,           // Push notification
    EMAIL;          // Email message

    public boolean isWhatsApp() {
        return this == WHATSAPP || this == TEMPLATE;
    }

    public boolean requiresExternalDelivery() {
        return this == WHATSAPP || this == SMS || this == TEMPLATE || this == EMAIL;
    }
}
