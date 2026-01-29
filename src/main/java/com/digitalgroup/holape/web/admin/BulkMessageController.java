package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.message.entity.BulkMessage;
import com.digitalgroup.holape.domain.message.repository.BulkMessageRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bulk Message Controller
 * Equivalent to Rails Admin::BulkMessagesController
 * Manages bulk message records (similar to canned messages but for campaigns)
 *
 * PARIDAD RAILS: bulk_messages table
 * Campos: client_id, user_id, message, client_global, status
 *
 * Access restricted to ADMIN and MANAGER roles only
 */
@Slf4j
@RestController
@RequestMapping("/app/bulk_messages")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2')")
public class BulkMessageController {

    private final BulkMessageRepository bulkMessageRepository;
    private final UserRepository userRepository;

    /**
     * List bulk messages
     * PARIDAD RAILS: index action
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> index(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        Page<BulkMessage> bulkMessagesPage;
        if (currentUser.isSuperAdmin()) {
            bulkMessagesPage = bulkMessageRepository.findAll(pageable);
        } else {
            bulkMessagesPage = bulkMessageRepository.findByClientId(currentUser.getClientId(), pageable);
        }

        List<Map<String, Object>> data = bulkMessagesPage.getContent().stream()
                .map(this::mapBulkMessageToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "bulk_messages", data,
                "total", bulkMessagesPage.getTotalElements(),
                "page", page,
                "totalPages", bulkMessagesPage.getTotalPages()
        ));
    }

    /**
     * Get single bulk message
     * PARIDAD RAILS: show action
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> show(@PathVariable Long id) {
        BulkMessage bulkMessage = bulkMessageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BulkMessage", id));

        return ResponseEntity.ok(mapBulkMessageToResponse(bulkMessage));
    }

    /**
     * Create new bulk message
     * PARIDAD RAILS: create action
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody CreateBulkMessageRequest request) {

        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User", currentUser.getId()));

        BulkMessage bulkMessage = new BulkMessage();
        bulkMessage.setClient(user.getClient());
        bulkMessage.setUser(user);
        bulkMessage.setMessage(request.message());
        bulkMessage.setClientGlobal(request.clientGlobal() != null ? request.clientGlobal() : false);
        bulkMessage.setStatus(Status.ACTIVE);

        bulkMessage = bulkMessageRepository.save(bulkMessage);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "bulk_message", mapBulkMessageToResponse(bulkMessage)
        ));
    }

    /**
     * Update bulk message
     * PARIDAD RAILS: update action
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody UpdateBulkMessageRequest request) {

        BulkMessage bulkMessage = bulkMessageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BulkMessage", id));

        if (request.message() != null) {
            bulkMessage.setMessage(request.message());
        }
        if (request.clientGlobal() != null) {
            bulkMessage.setClientGlobal(request.clientGlobal());
        }
        if (request.status() != null) {
            bulkMessage.setStatus(Status.valueOf(request.status().toUpperCase()));
        }

        bulkMessage = bulkMessageRepository.save(bulkMessage);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "bulk_message", mapBulkMessageToResponse(bulkMessage)
        ));
    }

    /**
     * Delete bulk message (soft delete)
     * PARIDAD RAILS: destroy action
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> destroy(@PathVariable Long id) {
        BulkMessage bulkMessage = bulkMessageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BulkMessage", id));

        bulkMessage.setStatus(Status.INACTIVE);
        bulkMessageRepository.save(bulkMessage);

        return ResponseEntity.ok(Map.of("result", "success"));
    }

    private Map<String, Object> mapBulkMessageToResponse(BulkMessage bulkMessage) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", bulkMessage.getId());
        map.put("message", bulkMessage.getMessage());
        map.put("client_global", bulkMessage.getClientGlobal());
        map.put("status", bulkMessage.getStatus() != null ? bulkMessage.getStatus().name().toLowerCase() : "active");
        map.put("client_id", bulkMessage.getClient() != null ? bulkMessage.getClient().getId() : null);
        map.put("user_id", bulkMessage.getUser() != null ? bulkMessage.getUser().getId() : null);
        map.put("created_at", bulkMessage.getCreatedAt());
        map.put("updated_at", bulkMessage.getUpdatedAt());

        if (bulkMessage.getUser() != null) {
            map.put("user_name", bulkMessage.getUser().getFullName());
        }

        return map;
    }

    /**
     * Request DTO for creating bulk message
     * PARIDAD RAILS: bulk_message_params
     */
    public record CreateBulkMessageRequest(
            String message,
            Boolean clientGlobal
    ) {}

    /**
     * Request DTO for updating bulk message
     */
    public record UpdateBulkMessageRequest(
            String message,
            Boolean clientGlobal,
            String status
    ) {}
}
