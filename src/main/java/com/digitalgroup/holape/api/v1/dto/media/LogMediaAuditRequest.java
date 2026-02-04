package com.digitalgroup.holape.api.v1.dto.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request DTO for logging media audit events
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogMediaAuditRequest {

    /**
     * Device fingerprint (for audit tracking)
     */
    @NotBlank(message = "userId is required")
    private String userId;

    /**
     * ID of the logged-in agent
     */
    private Long agentId;

    @NotBlank(message = "action is required")
    private String action;

    private String description;

    private String chatPhone;

    private String mimeType;

    private String filename;

    private String url;

    private Long size;

    private String clientIp;

    private Map<String, Object> metadata;

    @NotNull(message = "timestamp is required")
    private String timestamp; // ISO datetime string
}
