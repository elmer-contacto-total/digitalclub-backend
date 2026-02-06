package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.media.enums.MediaAuditAction;
import com.digitalgroup.holape.domain.media.service.MediaAuditLogService;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.security.CustomUserDetails;
import com.digitalgroup.holape.web.dto.MediaAuditLogDto;
import com.digitalgroup.holape.web.dto.PagedResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/app/media_audit_logs")
@RequiredArgsConstructor
public class MediaAuditLogAdminController {

    private final MediaAuditLogService mediaAuditLogService;
    private final UserRepository userRepository;

    /**
     * List media audit logs with pagination and filters.
     * Scoped by supervisor's subordinates for manager roles.
     * SUPER_ADMIN and ADMIN see all logs.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER_LEVEL_1','MANAGER_LEVEL_2','MANAGER_LEVEL_3','MANAGER_LEVEL_4')")
    public ResponseEntity<PagedResponse<MediaAuditLogDto>> list(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<Long> agentIds = resolveAgentIds(currentUser, agentId);

        MediaAuditAction parsedAction = parseAction(action);
        LocalDateTime fromDate = parseDate(from, true);
        LocalDateTime toDate = parseDate(to, false);

        var result = mediaAuditLogService.findByAgentIds(agentIds, parsedAction, fromDate, toDate,
                PageRequest.of(page, Math.min(size, 100)));

        var dtos = result.map(MediaAuditLogDto::from);
        return ResponseEntity.ok(PagedResponse.fromPage(dtos));
    }

    /**
     * Get stats counts by action type.
     * Scoped by supervisor's subordinates for manager roles.
     */
    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER_LEVEL_1','MANAGER_LEVEL_2','MANAGER_LEVEL_3','MANAGER_LEVEL_4')")
    public ResponseEntity<Map<String, Long>> stats(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        List<Long> agentIds = resolveAgentIds(currentUser, null);
        Map<String, Long> stats = mediaAuditLogService.getStatsByAgentIds(agentIds);
        return ResponseEntity.ok(stats);
    }

    /**
     * Resolve agent IDs based on current user role.
     * - SUPER_ADMIN/ADMIN: null (see all)
     * - Managers: subordinate agent IDs
     * If a specific agentId filter is provided, restrict to that agent (if in scope).
     */
    private List<Long> resolveAgentIds(CustomUserDetails currentUser, Long filterAgentId) {
        if (currentUser.isSuperAdmin() || currentUser.isAdmin()) {
            if (filterAgentId != null) {
                return List.of(filterAgentId);
            }
            return null; // null means "all" for service layer
        }

        // Manager roles: scope to subordinates
        List<User> subordinates = userRepository.findAgentsBySupervisor(currentUser.getId());
        List<Long> subordinateIds = subordinates.stream().map(User::getId).toList();

        if (filterAgentId != null) {
            // Only allow filtering by agents under this supervisor
            if (subordinateIds.contains(filterAgentId)) {
                return List.of(filterAgentId);
            }
            return List.of(); // Agent not in scope, return empty
        }

        return subordinateIds;
    }

    private MediaAuditAction parseAction(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        try {
            return MediaAuditAction.valueOf(action.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[MediaAuditLogAdmin] Unknown action filter: {}", action);
            return null;
        }
    }

    private LocalDateTime parseDate(String dateStr, boolean startOfDay) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(dateStr);
            return startOfDay ? date.atStartOfDay() : date.atTime(LocalTime.MAX);
        } catch (Exception e) {
            log.warn("[MediaAuditLogAdmin] Invalid date: {}", dateStr);
            return null;
        }
    }
}
