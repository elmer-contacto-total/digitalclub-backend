package com.digitalgroup.holape.api.v1.dto.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for saving captured media from Electron app
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaveCapturedMediaRequest {

    @NotBlank(message = "mediaId is required")
    private String mediaId;

    /**
     * Device fingerprint (for audit/tracking)
     */
    @NotBlank(message = "userId is required")
    private String userId;

    /**
     * ID of the logged-in agent in Angular/Electron
     */
    private Long agentId;

    /**
     * ID of the client user (from CRM lookup in Angular)
     * When provided, this takes precedence over phone-based lookup
     */
    private Long clientUserId;

    private String chatPhone;

    private String chatName;

    @NotBlank(message = "mediaType is required")
    private String mediaType;

    private String mimeType;

    @NotBlank(message = "data is required")
    private String data; // Base64 encoded

    private Long size;

    private Integer duration;

    private String whatsappMessageId;

    private String source; // PREVIEW or PLAYBACK

    @NotNull(message = "capturedAt is required")
    private String capturedAt; // ISO datetime string

    /**
     * When the WhatsApp message was originally sent (extracted from message metadata)
     */
    private String messageSentAt; // ISO datetime string
}
