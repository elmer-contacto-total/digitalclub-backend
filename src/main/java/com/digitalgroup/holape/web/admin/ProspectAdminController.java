package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.prospect.entity.Prospect;
import com.digitalgroup.holape.domain.prospect.service.ProspectService;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Prospect Admin Controller
 * Equivalent to Rails Admin::ProspectsController
 * Manages prospect/leads before they become users
 *
 * Aligned with Rails schema: prospects table has manager_id, name, phone, client_id, status, upgraded_to_user
 */
@Slf4j
@RestController
@RequestMapping("/app/prospects")
@RequiredArgsConstructor
public class ProspectAdminController {

    private final ProspectService prospectService;

    /**
     * List prospects
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> index(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        Page<Prospect> prospectsPage;
        if (search != null && !search.isEmpty()) {
            prospectsPage = prospectService.search(
                    currentUser.getClientId(), search, pageable);
        } else if (status != null && !status.isEmpty()) {
            try {
                Status statusEnum = Status.valueOf(status.toUpperCase());
                prospectsPage = prospectService.findByClientAndStatus(
                        currentUser.getClientId(), statusEnum, pageable);
            } catch (IllegalArgumentException e) {
                prospectsPage = prospectService.findByClient(
                        currentUser.getClientId(), pageable);
            }
        } else {
            prospectsPage = prospectService.findByClient(
                    currentUser.getClientId(), pageable);
        }

        List<Map<String, Object>> data = prospectsPage.getContent().stream()
                .map(this::mapProspectToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "prospects", data,
                "total", prospectsPage.getTotalElements(),
                "page", page,
                "totalPages", prospectsPage.getTotalPages()
        ));
    }

    /**
     * Get prospect by ID
     */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> show(@PathVariable Long id) {
        Prospect prospect = prospectService.findById(id);
        return ResponseEntity.ok(mapProspectToResponse(prospect));
    }

    /**
     * Create new prospect
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody CreateProspectRequest request) {

        Prospect prospect = prospectService.createProspect(
                currentUser.getClientId(),
                request.phone(),
                request.name()
        );

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "prospect", mapProspectToResponse(prospect)
        ));
    }

    /**
     * Update prospect
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody UpdateProspectRequest request) {

        Status status = null;
        if (request.status() != null) {
            try {
                status = Status.valueOf(request.status().toUpperCase());
            } catch (IllegalArgumentException e) {
                // ignore invalid status
            }
        }

        Prospect prospect = prospectService.updateProspect(
                id,
                request.name(),
                status
        );

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "prospect", mapProspectToResponse(prospect)
        ));
    }

    /**
     * Delete prospect
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        prospectService.deleteProspect(id);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", "Prospect deleted successfully"
        ));
    }

    /**
     * Upgrade prospect to user
     */
    @PostMapping("/{id}/upgrade")
    public ResponseEntity<Map<String, Object>> upgradeToUser(
            @PathVariable Long id,
            @RequestBody(required = false) UpgradeToUserRequest request) {

        Long managerId = request != null ? request.managerId() : null;
        Long userId = prospectService.upgradeToUser(id, managerId);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "user_id", userId,
                "message", "Prospect upgraded to user successfully"
        ));
    }

    /**
     * Assign prospect to manager
     */
    @PostMapping("/{id}/assign")
    public ResponseEntity<Map<String, Object>> assign(
            @PathVariable Long id,
            @RequestBody AssignRequest request) {

        Prospect prospect = prospectService.assignToManager(id, request.managerId());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "prospect", mapProspectToResponse(prospect)
        ));
    }

    private Map<String, Object> mapProspectToResponse(Prospect prospect) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", prospect.getId());
        map.put("name", prospect.getName());
        map.put("phone", prospect.getPhone());
        map.put("client_id", prospect.getClientId());
        map.put("status", prospect.getStatus() != null ? prospect.getStatus().name().toLowerCase() : null);
        map.put("upgraded_to_user", prospect.getUpgradedToUser());
        map.put("created_at", prospect.getCreatedAt());
        map.put("updated_at", prospect.getUpdatedAt());

        if (prospect.getManager() != null) {
            map.put("manager_id", prospect.getManager().getId());
            map.put("manager_name", prospect.getManager().getFullName());
        }

        return map;
    }

    public record CreateProspectRequest(
            String phone,
            String name
    ) {}

    public record UpdateProspectRequest(
            String name,
            String status
    ) {}

    public record UpgradeToUserRequest(Long managerId) {}

    public record AssignRequest(Long managerId) {}
}
