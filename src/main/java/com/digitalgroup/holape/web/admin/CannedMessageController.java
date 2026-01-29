package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.message.entity.CannedMessage;
import com.digitalgroup.holape.domain.message.service.CannedMessageService;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Canned Message Admin Controller
 * Manages predefined response messages for quick replies
 *
 * PARIDAD RAILS: admin/canned_messages_controller.rb
 * Campos: message, client_global, status
 */
@Slf4j
@RestController
@RequestMapping("/app/canned_messages")
@RequiredArgsConstructor
public class CannedMessageController {

    private final CannedMessageService cannedMessageService;

    /**
     * List all canned messages for client
     * PARIDAD RAILS: index action
     * For admins/managers: all messages
     * For others: own messages + client_global messages
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> index(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        List<CannedMessage> messages;
        if (currentUser.isSuperAdmin() || currentUser.isAdmin() || currentUser.isManagerLevel4()) {
            messages = cannedMessageService.findByClient(currentUser.getClientId());
        } else {
            messages = cannedMessageService.findVisibleToUser(
                    currentUser.getClientId(), currentUser.getId());
        }

        List<Map<String, Object>> data = messages.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "canned_messages", data
        ));
    }

    /**
     * Get canned message by ID
     * PARIDAD RAILS: show action
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> show(@PathVariable Long id) {
        CannedMessage message = cannedMessageService.findById(id);
        return ResponseEntity.ok(mapToResponse(message));
    }

    /**
     * Create new canned message
     * PARIDAD RAILS: create action
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody CreateCannedMessageRequest request) {

        // For manager_level_4, default client_global to true
        Boolean clientGlobal = request.clientGlobal();
        if (clientGlobal == null && currentUser.isManagerLevel4()) {
            clientGlobal = true;
        }

        CannedMessage message = cannedMessageService.create(
                currentUser.getClientId(),
                currentUser.getId(),
                request.message(),
                clientGlobal
        );

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "canned_message", mapToResponse(message)
        ));
    }

    /**
     * Update canned message
     * PARIDAD RAILS: update action
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody UpdateCannedMessageRequest request) {

        CannedMessage message = cannedMessageService.update(
                id,
                request.message(),
                request.clientGlobal(),
                request.status() != null ? Status.valueOf(request.status().toUpperCase()) : null
        );

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "canned_message", mapToResponse(message)
        ));
    }

    /**
     * Delete canned message
     * PARIDAD RAILS: destroy action (hard delete)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        cannedMessageService.delete(id);

        return ResponseEntity.ok(Map.of(
                "result", "success"
        ));
    }

    /**
     * Search canned messages by content
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam String q) {

        List<CannedMessage> messages = cannedMessageService.findByClient(currentUser.getClientId())
                .stream()
                .filter(m -> m.getMessage().toLowerCase().contains(q.toLowerCase()))
                .collect(Collectors.toList());

        List<Map<String, Object>> data = messages.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "canned_messages", data
        ));
    }

    private Map<String, Object> mapToResponse(CannedMessage message) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", message.getId());
        map.put("message", message.getMessage());
        map.put("client_global", message.getClientGlobal());
        map.put("status", message.getStatus() != null ? message.getStatus().name().toLowerCase() : "active");
        map.put("user_id", message.getUser() != null ? message.getUser().getId() : null);
        map.put("client_id", message.getClient() != null ? message.getClient().getId() : null);
        map.put("created_at", message.getCreatedAt());
        map.put("updated_at", message.getUpdatedAt());
        return map;
    }

    /**
     * Request DTO for creating canned message
     * PARIDAD RAILS: canned_message_params - :message, :client_global, :status
     */
    public record CreateCannedMessageRequest(
            String message,
            Boolean clientGlobal
    ) {}

    /**
     * Request DTO for updating canned message
     */
    public record UpdateCannedMessageRequest(
            String message,
            Boolean clientGlobal,
            String status
    ) {}
}
