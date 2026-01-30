package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.common.enums.Status;
import com.digitalgroup.holape.domain.common.enums.UserRole;
import com.digitalgroup.holape.domain.message.entity.Message;
import com.digitalgroup.holape.domain.message.repository.MessageRepository;
import com.digitalgroup.holape.domain.prospect.entity.Prospect;
import com.digitalgroup.holape.domain.prospect.repository.ProspectRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.entity.UserManagerHistory;
import com.digitalgroup.holape.domain.user.repository.UserManagerHistoryRepository;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.domain.user.service.UserService;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.digitalgroup.holape.web.dto.PagedResponse;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * User Admin Controller
 * Equivalent to Rails Admin::UsersController
 */
@Slf4j
@RestController
@RequestMapping("/app/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserService userService;
    private final UserRepository userRepository;
    private final UserManagerHistoryRepository userManagerHistoryRepository;
    private final MessageRepository messageRepository;
    private final ProspectRepository prospectRepository;

    /**
     * List users with standard REST pagination
     */
    @GetMapping
    public ResponseEntity<PagedResponse<Map<String, Object>>> index(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search) {

        Long clientId = currentUser.getClientId();
        Pageable pageable = PageRequest.of(page - 1, pageSize, Sort.by("createdAt").descending());

        Page<User> usersPage;

        // Filter by role
        if (currentUser.getRole().equals("SUPER_ADMIN") ||
            currentUser.getRole().equals("ADMIN") ||
            currentUser.getRole().equals("STAFF")) {
            usersPage = userService.findByClient(clientId, pageable);
        } else if (currentUser.getRole().contains("MANAGER")) {
            usersPage = userRepository.findByManager_IdAndRole(
                    currentUser.getId(), UserRole.STANDARD, pageable);
        } else {
            usersPage = Page.empty();
        }

        // Map to DTOs
        List<Map<String, Object>> data = usersPage.getContent().stream()
                .map(this::mapUserToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(PagedResponse.of(data, usersPage.getTotalElements(), page, pageSize));
    }

    /**
     * Get user by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> show(@PathVariable Long id) {
        User user = userService.findById(id);
        return ResponseEntity.ok(mapUserToResponse(user));
    }

    /**
     * Create new user
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4')")
    public ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody CreateUserRequest request) {

        User newUser = User.builder()
                .email(request.email())
                .firstName(request.firstName())
                .lastName(request.lastName())
                .phone(request.phone())
                .role(UserRole.valueOf(request.role().toUpperCase()))
                .build();

        // Set client from current user
        newUser.setClient(userService.findById(currentUser.getId()).getClient());

        // Set manager if provided
        if (request.managerId() != null) {
            newUser.setManager(userService.findById(request.managerId()));
        }

        User created = userService.create(newUser, request.password());

        return ResponseEntity.ok(mapUserToResponse(created));
    }

    /**
     * Update user
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4')")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody UpdateUserRequest request) {

        User updates = new User();
        updates.setFirstName(request.firstName());
        updates.setLastName(request.lastName());
        updates.setPhone(request.phone());
        if (request.role() != null) {
            updates.setRole(UserRole.valueOf(request.role().toUpperCase()));
        }
        if (request.status() != null) {
            updates.setStatus(Status.valueOf(request.status().toUpperCase()));
        }

        User updated = userService.update(id, updates);

        return ResponseEntity.ok(mapUserToResponse(updated));
    }

    /**
     * Delete (deactivate) user
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long id) {
        userService.deactivate(id);
        return ResponseEntity.ok(Map.of("message", "User deactivated successfully"));
    }

    /**
     * Get internal users (non-standard role)
     */
    @GetMapping("/internal")
    public ResponseEntity<PagedResponse<Map<String, Object>>> internalUsers(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        Long clientId = currentUser.getClientId();
        List<UserRole> internalRoles = List.of(
                UserRole.ADMIN, UserRole.MANAGER_LEVEL_1, UserRole.MANAGER_LEVEL_2,
                UserRole.MANAGER_LEVEL_3, UserRole.MANAGER_LEVEL_4, UserRole.AGENT, UserRole.STAFF
        );

        List<User> users = userRepository.findActiveInternalUsers(
                clientId, internalRoles, Status.ACTIVE);

        List<Map<String, Object>> data = users.stream()
                .map(this::mapUserToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(PagedResponse.fromList(data));
    }

    /**
     * Get available managers
     */
    @GetMapping("/available_managers")
    public ResponseEntity<PagedResponse<Map<String, Object>>> availableManagers(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) String role) {

        Long clientId = currentUser.getClientId();
        List<UserRole> managerRoles = List.of(
                UserRole.MANAGER_LEVEL_1, UserRole.MANAGER_LEVEL_2,
                UserRole.MANAGER_LEVEL_3, UserRole.MANAGER_LEVEL_4, UserRole.AGENT
        );

        List<User> managers = userRepository.findActiveInternalUsers(
                clientId, managerRoles, Status.ACTIVE);

        List<Map<String, Object>> data = managers.stream()
                .map(u -> Map.<String, Object>of(
                        "id", u.getId(),
                        "name", u.getFullName(),
                        "email", u.getEmail(),
                        "role", u.getRole().name()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(PagedResponse.fromList(data));
    }

    /**
     * Get agent's clients (subordinates)
     * PARIDAD: Rails UsersController#agent_clients
     * Supports filters: activeOnly, ticketStatus, messageStatus, search
     */
    @GetMapping("/agent_clients")
    public ResponseEntity<PagedResponse<Map<String, Object>>> agentClients(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "25") int pageSize,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false) String ticketStatus,
            @RequestParam(required = false) String messageStatus,
            @RequestParam(required = false) String search) {

        // Use unsorted Pageable for native queries (they have their own ORDER BY)
        // Use sorted Pageable for JPQL queries
        Pageable unsortedPageable = PageRequest.of(page, pageSize);
        Pageable sortedPageable = PageRequest.of(page, pageSize, Sort.by("lastMessageAt").descending().and(Sort.by("id")));

        Page<User> clientsPage;

        // Apply filters based on Rails agent_clients implementation
        if (ticketStatus != null && !"all".equals(ticketStatus)) {
            // Filter by ticket status (native queries - use unsorted)
            if ("open".equals(ticketStatus)) {
                clientsPage = userRepository.findClientsWithOpenTicketsByManager(
                        currentUser.getId(), unsortedPageable);
            } else { // closed
                clientsPage = userRepository.findClientsWithoutOpenTicketsByManager(
                        currentUser.getId(), unsortedPageable);
            }
        } else if (messageStatus != null && !"all".equals(messageStatus)) {
            // Filter by message response status (JPQL queries - use sorted)
            if ("to_respond".equals(messageStatus)) {
                clientsPage = userRepository.findClientsRequiringResponseByManager(
                        currentUser.getId(), sortedPageable);
            } else { // responded
                clientsPage = userRepository.findClientsRespondedByManager(
                        currentUser.getId(), sortedPageable);
            }
        } else if (Boolean.TRUE.equals(activeOnly)) {
            // Only clients with active conversations (native query - use unsorted)
            clientsPage = userRepository.findClientsWithActiveConversationByManager(
                    currentUser.getId(), unsortedPageable);
        } else {
            // All subordinates (default - JPQL query - use sorted)
            clientsPage = userRepository.findClientsByManager(
                    currentUser.getClientId(), currentUser.getId(), sortedPageable);
        }

        // Apply search filter if provided
        // Note: For simplicity, search is applied in the query - for now we filter in memory
        List<User> filteredClients = clientsPage.getContent();
        if (search != null && !search.isBlank()) {
            String searchLower = search.toLowerCase();
            filteredClients = filteredClients.stream()
                    .filter(u -> {
                        String name = u.getFullName().toLowerCase();
                        String phone = u.getPhone() != null ? u.getPhone() : "";
                        String codigo = u.getCodigo() != null ? u.getCodigo().toLowerCase() : "";
                        return name.contains(searchLower) ||
                               phone.contains(search) ||
                               codigo.contains(searchLower);
                    })
                    .collect(Collectors.toList());
        }

        List<Map<String, Object>> data = filteredClients.stream()
                .map(this::mapUserToAgentClientResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(PagedResponse.of(data, clientsPage.getTotalElements(), page, pageSize));
    }

    /**
     * Get supervisor's clients (clients of agents under the supervisor)
     * Equivalent to Rails: supervisor_clients
     * PARIDAD: Rails supports filtering by manager, ticket status, message status, active only
     */
    @GetMapping("/supervisor_clients")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4')")
    public ResponseEntity<PagedResponse<Map<String, Object>>> supervisorClients(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "25") int pageSize,
            @RequestParam(required = false) Long managerId,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false) String ticketStatus,
            @RequestParam(required = false) String messageStatus,
            @RequestParam(required = false) String search) {

        // Get subordinate agent IDs
        List<Long> managerIds = userRepository.findAgentsBySupervisor(currentUser.getId())
                .stream().map(User::getId).collect(Collectors.toList());

        if (managerIds.isEmpty()) {
            return ResponseEntity.ok(PagedResponse.empty());
        }

        // If specific manager requested, filter to that manager only
        if (managerId != null && !managerId.equals(0L)) {
            if (managerIds.contains(managerId)) {
                managerIds = List.of(managerId);
            } else {
                // Manager not in subordinates - return empty
                return ResponseEntity.ok(PagedResponse.empty());
            }
        }

        Pageable unsortedPageable = PageRequest.of(page, pageSize);
        Pageable sortedPageable = PageRequest.of(page, pageSize, Sort.by("lastMessageAt").descending().and(Sort.by("id")));

        Page<User> clientsPage;

        // Apply filters based on Rails supervisor_clients implementation
        if (ticketStatus != null && !"all".equals(ticketStatus)) {
            // Filter by ticket status
            if ("open".equals(ticketStatus)) {
                clientsPage = userRepository.findClientsWithOpenTicketsByManagerIds(managerIds, unsortedPageable);
            } else { // closed
                clientsPage = userRepository.findClientsWithoutOpenTicketsByManagerIds(managerIds, unsortedPageable);
            }
        } else if (messageStatus != null && !"all".equals(messageStatus)) {
            // Filter by message response status
            if ("to_respond".equals(messageStatus)) {
                clientsPage = userRepository.findClientsRequiringResponseByManagerIds(managerIds, sortedPageable);
            } else { // responded
                clientsPage = userRepository.findClientsRespondedByManagerIds(managerIds, sortedPageable);
            }
        } else if (Boolean.TRUE.equals(activeOnly)) {
            // Only clients with active conversations
            clientsPage = userRepository.findClientsWithActiveConversationByManagerIds(managerIds, unsortedPageable);
        } else {
            // All clients of subordinates
            if (search != null && !search.isBlank()) {
                clientsPage = userRepository.findClientsByManagerIdsWithSearch(managerIds, search, sortedPageable);
            } else {
                clientsPage = userRepository.findClientsByManagerIds(managerIds, sortedPageable);
            }
        }

        // Apply search filter if provided (for filtered queries)
        List<User> filteredClients = clientsPage.getContent();
        if (search != null && !search.isBlank() && !(ticketStatus == null && messageStatus == null && !Boolean.TRUE.equals(activeOnly))) {
            String searchLower = search.toLowerCase();
            filteredClients = filteredClients.stream()
                    .filter(u -> (u.getFirstName() != null && u.getFirstName().toLowerCase().contains(searchLower)) ||
                            (u.getLastName() != null && u.getLastName().toLowerCase().contains(searchLower)) ||
                            (u.getPhone() != null && u.getPhone().contains(search)) ||
                            (u.getCodigo() != null && u.getCodigo().toLowerCase().contains(searchLower)))
                    .collect(Collectors.toList());
        }

        List<Map<String, Object>> data = filteredClients.stream()
                .map(this::mapUserToAgentClientResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(PagedResponse.of(data, clientsPage.getTotalElements(), page + 1, pageSize));
    }

    /**
     * Get agents under supervisor
     * Equivalent to Rails: supervisor_agents
     */
    @GetMapping("/supervisor_agents")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4')")
    public ResponseEntity<PagedResponse<Map<String, Object>>> supervisorAgents(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        List<User> agents = userRepository.findAgentsBySupervisor(currentUser.getId());

        List<Map<String, Object>> data = agents.stream()
                .map(this::mapUserToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(PagedResponse.fromList(data));
    }

    /**
     * Get prospects assigned to agent
     * PARIDAD Rails: UsersController#agent_prospects
     * - For agents: current_user.prospects (prospects where manager_id = current_user.id)
     * - For manager_level_4: All prospects in client, optionally filtered by manager
     */
    @GetMapping("/agent_prospects")
    @PreAuthorize("hasAnyRole('AGENT', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4')")
    @Transactional(readOnly = true)
    public ResponseEntity<PagedResponse<Map<String, Object>>> agentProspects(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "25") int pageSize,
            @RequestParam(required = false) Long managerId,
            @RequestParam(required = false) String search) {

        Pageable pageable = PageRequest.of(page, pageSize);
        Page<Prospect> prospectsPage;

        // PARIDAD Rails: manager_level_4 can see all prospects in client, others only their own
        if (currentUser.isManagerLevel4()) {
            // Manager level 4: all prospects in client, optionally filtered by manager
            if (search != null && !search.isEmpty()) {
                prospectsPage = prospectRepository.searchByClientIdAndOptionalManager(
                        currentUser.getClientId(),
                        managerId != null && !managerId.equals(0L) ? managerId : null,
                        search,
                        pageable);
            } else if (managerId != null && !managerId.equals(0L)) {
                prospectsPage = prospectRepository.findByClientIdAndManagerIdOrderByIdDesc(
                        currentUser.getClientId(), managerId, pageable);
            } else {
                prospectsPage = prospectRepository.findByClientIdOrderByIdDesc(
                        currentUser.getClientId(), pageable);
            }
        } else {
            // Agents and other managers: only their assigned prospects
            if (search != null && !search.isEmpty()) {
                prospectsPage = prospectRepository.searchByManagerId(
                        currentUser.getId(), search, pageable);
            } else {
                prospectsPage = prospectRepository.findByManagerIdOrderByIdDesc(
                        currentUser.getId(), pageable);
            }
        }

        // Map to DataTable format (PARIDAD Rails)
        List<Map<String, Object>> data = prospectsPage.getContent().stream()
                .map(this::mapProspectToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(PagedResponse.of(data, prospectsPage.getTotalElements(), page, pageSize));
    }

    /**
     * Map Prospect entity to response format
     * PARIDAD Rails: agent_prospects JSON format
     */
    private Map<String, Object> mapProspectToResponse(Prospect prospect) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", prospect.getId());
        map.put("name", prospect.getName() != null ? prospect.getName() : "Sin nombre");
        map.put("phone", prospect.getPhone() != null ? prospect.getPhone() : "");
        map.put("clientId", prospect.getClientId());
        map.put("status", prospect.getStatus() != null ? prospect.getStatus().name().toLowerCase() : "active");
        map.put("upgradedToUser", prospect.getUpgradedToUser() != null ? prospect.getUpgradedToUser() : false);
        map.put("createdAt", prospect.getCreatedAt());
        map.put("updatedAt", prospect.getUpdatedAt());

        // Manager info (safely accessed to avoid lazy loading issues)
        if (prospect.getManager() != null) {
            try {
                map.put("managerId", prospect.getManager().getId());
                map.put("managerName", prospect.getManager().getFullName());
            } catch (Exception e) {
                // Lazy loading exception, just use ID if available
                map.put("managerId", null);
                map.put("managerName", null);
            }
        } else {
            map.put("managerId", null);
            map.put("managerName", null);
        }

        return map;
    }

    /**
     * Get unassigned prospects
     */
    @GetMapping("/unassigned_prospects")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2')")
    public ResponseEntity<PagedResponse<Map<String, Object>>> unassignedProspects(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "10") int pageSize) {

        Pageable pageable = PageRequest.of(page - 1, pageSize);

        Page<User> prospectsPage = userRepository.findUnassignedProspects(currentUser.getClientId(), pageable);

        List<Map<String, Object>> data = prospectsPage.getContent().stream()
                .map(this::mapUserToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(PagedResponse.of(data, prospectsPage.getTotalElements(), page, pageSize));
    }

    /**
     * Get users requiring response
     * Equivalent to Rails: require_response filter
     */
    @GetMapping("/require_response")
    public ResponseEntity<PagedResponse<Map<String, Object>>> requireResponse(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "10") int pageSize) {

        Pageable pageable = PageRequest.of(page - 1, pageSize);

        Page<User> usersPage = userRepository.findRequiringResponseByClient(currentUser.getClientId(), pageable);

        List<Map<String, Object>> data = usersPage.getContent().stream()
                .map(this::mapUserToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(PagedResponse.of(data, usersPage.getTotalElements(), page, pageSize));
    }

    /**
     * Assign user to agent
     */
    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4')")
    public ResponseEntity<Map<String, Object>> assignToAgent(
            @PathVariable Long id,
            @RequestBody AssignRequest request) {

        User user = userService.findById(id);
        User agent = userService.findById(request.agentId());

        user.setManager(agent);
        userRepository.save(user);

        log.info("User {} assigned to agent {}", id, request.agentId());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "user", mapUserToResponse(user)
        ));
    }

    /**
     * Get subordinates (agents) for current user
     * PARIDAD RAILS: current_user.subordinates (for manager_level_4 agent filter)
     */
    @GetMapping("/subordinates")
    @PreAuthorize("hasAnyRole('MANAGER_LEVEL_4', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_1', 'ADMIN', 'SUPER_ADMIN')")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getSubordinates(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        List<User> subordinates = userRepository.findSubordinatesByManagerId(currentUser.getId());

        List<Map<String, Object>> data = subordinates.stream()
                .map(user -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", user.getId());
                    map.put("firstName", user.getFirstName());
                    map.put("lastName", user.getLastName());
                    map.put("fullName", user.getFullName());
                    map.put("email", user.getEmail());
                    map.put("role", user.getRole().name());
                    return map;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(data);
    }

    /**
     * Get clients of subordinates (subordinates of subordinates)
     * PARIDAD RAILS: managers#index JSON response
     * Returns users where manager_id IN (current_user's subordinates' ids) and role = standard
     */
    @GetMapping("/subordinates_clients")
    @PreAuthorize("hasAnyRole('MANAGER_LEVEL_4', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_1', 'ADMIN', 'SUPER_ADMIN')")
    @Transactional(readOnly = true)
    public ResponseEntity<PagedResponse<Map<String, Object>>> getSubordinatesClients(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "50") int pageSize,
            @RequestParam(required = false) String search) {

        // Get subordinate IDs
        List<Long> subordinateIds = userRepository.findSubordinatesByManagerId(currentUser.getId())
                .stream().map(User::getId).collect(Collectors.toList());

        if (subordinateIds.isEmpty()) {
            return ResponseEntity.ok(PagedResponse.empty());
        }

        Pageable pageable = PageRequest.of(page - 1, pageSize, Sort.by("id").descending());
        Page<User> clientsPage;

        if (search != null && !search.isBlank()) {
            clientsPage = userRepository.findClientsByManagerIdsWithSearch(subordinateIds, search, pageable);
        } else {
            clientsPage = userRepository.findClientsByManagerIds(subordinateIds, pageable);
        }

        // Cache manager names to avoid N+1
        Map<Long, String> managerNames = userRepository.findAllById(subordinateIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));

        List<Map<String, Object>> data = clientsPage.getContent().stream()
                .map(user -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", user.getId());
                    map.put("name", user.getFullName());
                    map.put("phone", user.getPhone());
                    map.put("manager_id", user.getManager() != null ? user.getManager().getId() : null);
                    map.put("manager_name", user.getManager() != null ? managerNames.getOrDefault(user.getManager().getId(), "") : "");
                    return map;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(PagedResponse.of(
                data,
                clientsPage.getTotalElements(),
                clientsPage.getNumber() + 1,
                clientsPage.getSize()
        ));
    }

    /**
     * Search user by phone number
     * PARIDAD ELECTRON: CRM panel search
     * Returns user info if found by phone (within agent's clients or client scope)
     */
    @GetMapping("/search_by_phone")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> searchByPhone(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam String phone) {

        // Normalize phone (remove +, spaces, dashes)
        String normalizedPhone = phone.replaceAll("[^0-9]", "");

        // Search for user by phone within the current client
        Optional<User> userOpt = userRepository.findByPhoneAndClientId(normalizedPhone, currentUser.getClientId());

        // If not found, try with country code variations
        if (userOpt.isEmpty() && normalizedPhone.length() >= 9) {
            // Try without country code (last 9 digits for Peru)
            String shortPhone = normalizedPhone.length() > 9 ?
                    normalizedPhone.substring(normalizedPhone.length() - 9) : normalizedPhone;
            userOpt = userRepository.findByPhoneAndClientId(shortPhone, currentUser.getClientId());

            // Try with Peru country code
            if (userOpt.isEmpty()) {
                String withCountryCode = "51" + shortPhone;
                userOpt = userRepository.findByPhoneAndClientId(withCountryCode, currentUser.getClientId());
            }
        }

        if (userOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("found", false));
        }

        User user = userOpt.get();

        // Build response
        Map<String, Object> contact = new HashMap<>();
        contact.put("id", user.getId());
        contact.put("firstName", user.getFirstName());
        contact.put("lastName", user.getLastName());
        contact.put("fullName", user.getFullName());
        contact.put("email", user.getEmail());
        contact.put("phone", user.getPhone());
        contact.put("codigo", user.getCodigo());
        contact.put("avatarUrl", user.getAvatarData());
        contact.put("status", user.getStatus() != null ? user.getStatus().name() : "ACTIVE");
        contact.put("createdAt", user.getCreatedAt());
        contact.put("issueNotes", user.getIssueNotes());
        contact.put("requireResponse", user.getRequireResponse());

        // Manager info
        if (user.getManager() != null) {
            contact.put("managerId", user.getManager().getId());
            contact.put("managerName", user.getManager().getFullName());
        }

        // Check for open ticket
        contact.put("hasOpenTicket", hasOpenTicket(user.getId()));

        return ResponseEntity.ok(Map.of(
                "found", true,
                "contact", contact
        ));
    }

    /**
     * Update issue notes for a user
     * PARIDAD ELECTRON: CRM panel notes save
     */
    @PatchMapping("/{id}/issue_notes")
    public ResponseEntity<Map<String, Object>> updateIssueNotes(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {

        User user = userService.findById(id);
        user.setIssueNotes(request.get("notes"));
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * Get client/user details with manager assignment history
     * PARIDAD RAILS: client_details_from_stimulus_modal
     * Returns user profile and user_manager_histories for the modal
     */
    @GetMapping("/client_details")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> clientDetails(
            @RequestParam(name = "user_id") Long userId) {

        User user = userService.findById(userId);

        Map<String, Object> response = new HashMap<>();

        // User profile
        Map<String, Object> profile = new HashMap<>();
        profile.put("id", user.getId());
        profile.put("name", user.getFullName());
        profile.put("firstName", user.getFirstName());
        profile.put("lastName", user.getLastName());
        profile.put("email", user.getEmail());
        profile.put("phone", user.getPhone());
        profile.put("codigo", user.getCodigo());
        profile.put("avatarUrl", user.getAvatarData());
        response.put("user", profile);

        // Manager assignment history
        List<UserManagerHistory> histories = userManagerHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<Map<String, Object>> historyList = histories.stream()
                .map(h -> {
                    Map<String, Object> hm = new HashMap<>();
                    hm.put("id", h.getId());
                    hm.put("managerName", h.getNewManager() != null ? h.getNewManager().getFullName() : "-");
                    hm.put("createdAt", h.getCreatedAt());
                    return hm;
                })
                .collect(Collectors.toList());
        response.put("managerHistory", historyList);

        return ResponseEntity.ok(response);
    }

    /**
     * Login as another user (impersonation)
     * Equivalent to Rails: login_as
     */
    @PostMapping("/{id}/login_as")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> loginAs(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @PathVariable Long id) {

        User targetUser = userService.findById(id);

        // Verify same client (unless super admin)
        if (!currentUser.getRole().equals("SUPER_ADMIN") &&
            !targetUser.getClient().getId().equals(currentUser.getClientId())) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Cannot impersonate users from other clients"
            ));
        }

        // Generate new JWT for target user
        String token = userService.generateImpersonationToken(targetUser, currentUser.getId());

        log.info("User {} impersonating user {}", currentUser.getId(), id);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "token", token,
                "user", mapUserToResponse(targetUser),
                "original_user_id", currentUser.getId()
        ));
    }

    /**
     * Return to original user after impersonation
     */
    @PostMapping("/return_from_impersonation")
    public ResponseEntity<Map<String, Object>> returnFromImpersonation(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam Long originalUserId) {

        User originalUser = userService.findById(originalUserId);

        // Generate new JWT for original user
        String token = userService.generateToken(originalUser);

        log.info("Returning from impersonation to user {}", originalUserId);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "token", token,
                "user", mapUserToResponse(originalUser)
        ));
    }

    /**
     * Reassign users from one agent to another
     * PARIDAD RAILS: managers#update (assign_managers)
     * Used by manager assignments view to reassign clients between agents
     */
    @PostMapping("/reassign_bulk")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4')")
    public ResponseEntity<Map<String, Object>> reassignBulk(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody ReassignBulkRequest request) {

        if (request.newAgentId() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "result", "error",
                    "message", "Debe seleccionar un agente destino"
            ));
        }

        if (request.userIds() == null || request.userIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "result", "error",
                    "message", "Debe seleccionar al menos un usuario"
            ));
        }

        User newAgent = userService.findById(request.newAgentId());
        int count = 0;

        for (Long userId : request.userIds()) {
            User user = userService.findById(userId);
            user.setManager(newAgent);
            userRepository.save(user);
            count++;
        }

        log.info("User {} reassigned {} users to agent {}", currentUser.getId(), count, request.newAgentId());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", String.format("%d usuario(s) reasignado(s) correctamente", count),
                "reassigned_count", count
        ));
    }

    /**
     * Export client messages to CSV
     * Equivalent to Rails: export_client_messages
     */
    @GetMapping("/export_client_messages")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4', 'AGENT')")
    public ResponseEntity<byte[]> exportClientMessages(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestParam(required = false) Long managerId,
            @RequestParam(required = false) String ticketStatus,
            @RequestParam(required = false) String messageStatus,
            @RequestParam(required = false) String search) {

        // Get users based on filters
        List<User> users;
        List<Long> managerIds;

        if (currentUser.getRole().equals("AGENT") ||
            currentUser.getRole().contains("MANAGER")) {
            // Agent/Manager scope - direct subordinates
            managerIds = List.of(currentUser.getId());
        } else {
            // Supervisor scope - subordinates of subordinates
            List<User> directSubordinates = userRepository.findSubordinatesByManagerId(currentUser.getId());
            managerIds = directSubordinates.stream().map(User::getId).collect(Collectors.toList());
        }

        if (managerId != null) {
            managerIds = List.of(managerId);
        }

        if (!managerIds.isEmpty()) {
            users = userRepository.findByManagersWithFilters(managerIds, ticketStatus, messageStatus);
        } else {
            users = List.of();
        }

        // Build CSV
        StringBuilder csv = new StringBuilder();
        csv.append("Código,Nombre,Teléfono,Mensaje Enviado,Mensaje Recibido,Timestamp\n");

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        for (User user : users) {
            List<Message> messages = messageRepository.findBySenderIdOrRecipientIdOrderBySentAtAsc(
                    user.getId(), user.getId());

            for (Message message : messages) {
                String codigo = user.getCodigo() != null ? user.getCodigo() : "";
                String nombre = user.getFullName();
                String telefono = user.getPhone() != null ? user.getPhone() : "";
                String sentMessage = "";
                String receivedMessage = "";
                String timestamp = message.getSentAt() != null ?
                        message.getSentAt().format(formatter) : "";

                if (message.isIncoming()) {
                    receivedMessage = escapeCSV(message.getContent());
                } else {
                    sentMessage = escapeCSV(message.getContent());
                }

                csv.append(String.format("%s,%s,%s,%s,%s,%s\n",
                        escapeCSV(codigo),
                        escapeCSV(nombre),
                        escapeCSV(telefono),
                        sentMessage,
                        receivedMessage,
                        timestamp));
            }
        }

        byte[] csvBytes = csv.toString().getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=utf-8"));
        headers.setContentDispositionFormData("attachment",
                "client_messages_export_" + java.time.LocalDate.now() + ".csv");

        log.info("Exported messages for {} users", users.size());

        return ResponseEntity.ok()
                .headers(headers)
                .body(csvBytes);
    }

    /**
     * Get clients for a specific agent (used by supervisor view)
     * Equivalent to Rails: supervisor_get_agent_clients
     */
    @GetMapping("/supervisor_get_agent_clients")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER_LEVEL_1', 'MANAGER_LEVEL_2', 'MANAGER_LEVEL_3', 'MANAGER_LEVEL_4')")
    public ResponseEntity<PagedResponse<Map<String, Object>>> supervisorGetAgentClients(
            @RequestParam Long managerId,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search) {

        User agent = userService.findById(managerId);

        Pageable pageable = PageRequest.of(page - 1, pageSize, Sort.by("lastMessageAt").descending());
        Page<User> clientsPage;

        if (search != null && !search.isBlank()) {
            clientsPage = userRepository.searchUsers(agent.getClientId(), search, pageable);
        } else {
            clientsPage = userRepository.findClientsOf(managerId, pageable);
        }

        List<Map<String, Object>> data = clientsPage.getContent().stream()
                .map(this::mapUserToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(PagedResponse.of(data, clientsPage.getTotalElements(), page, pageSize));
    }

    /**
     * Send reset password instructions to user's email
     * Equivalent to Rails: send_reset_password_instructions
     */
    @PostMapping("/send_reset_password")
    public ResponseEntity<Map<String, Object>> sendResetPasswordInstructions(
            @AuthenticationPrincipal CustomUserDetails currentUser) {

        userService.sendResetPasswordInstructions(currentUser.getId());

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", "Reset password instructions sent to your email"
        ));
    }

    /**
     * Update temporary password (for first-time login)
     * Equivalent to Rails: update_temp_password
     */
    @PostMapping("/update_temp_password")
    public ResponseEntity<Map<String, Object>> updateTempPassword(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody UpdateTempPasswordRequest request) {

        // Validate password
        if (request.password() == null || request.password().length() < 8) {
            return ResponseEntity.badRequest().body(Map.of(
                    "result", "error",
                    "message", "Password must be at least 8 characters"
            ));
        }

        if (!request.password().equals(request.passwordConfirmation())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "result", "error",
                    "message", "Password confirmation does not match"
            ));
        }

        userService.updateTempPassword(currentUser.getId(), request.password());

        // Generate new token
        User user = userService.findById(currentUser.getId());
        String token = userService.generateToken(user);

        return ResponseEntity.ok(Map.of(
                "result", "success",
                "message", "Password updated successfully",
                "token", token
        ));
    }

    private String escapeCSV(String value) {
        if (value == null) return "";
        // Escape quotes and wrap in quotes if contains special characters
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private Map<String, Object> mapUserToResponse(User user) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", user.getId());
        map.put("email", user.getEmail());
        map.put("firstName", user.getFirstName());
        map.put("lastName", user.getLastName());
        map.put("fullName", user.getFullName());
        map.put("phone", user.getPhone());
        map.put("role", user.getRole() != null ? user.getRole().getValue() : 0);
        map.put("status", user.getStatus() != null ? user.getStatus().getValue() : 0);
        map.put("createdAt", user.getCreatedAt());
        map.put("lastMessageAt", user.getLastMessageAt());
        map.put("requireResponse", user.getRequireResponse());

        // Safely handle lazy-loaded manager to avoid LazyInitializationException
        try {
            if (user.getManager() != null) {
                User manager = user.getManager();
                // Check if manager is initialized (not a proxy)
                if (org.hibernate.Hibernate.isInitialized(manager)) {
                    map.put("managerId", manager.getId());
                    map.put("managerName", manager.getFullName());
                    map.put("managerRole", manager.getRole() != null ? manager.getRole().getValue() : 0);
                } else {
                    // Only access the ID (which is available without loading)
                    map.put("managerId", manager.getId());
                }
            }
        } catch (Exception e) {
            log.debug("Could not load manager for user {}: {}", user.getId(), e.getMessage());
        }
        return map;
    }

    /**
     * Map user to agent client response (for agent_clients endpoint)
     * PARIDAD: Rails DataTable columns: [id, name, phone, codigo, chevron]
     */
    private Map<String, Object> mapUserToAgentClientResponse(User user) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", user.getId());
        map.put("email", user.getEmail());
        map.put("firstName", user.getFirstName());
        map.put("lastName", user.getLastName());
        map.put("fullName", user.getFullName());
        map.put("phone", user.getPhone());
        map.put("codigo", user.getCodigo());
        map.put("role", user.getRole() != null ? user.getRole().getValue() : 0);
        map.put("status", user.getStatus() != null ? user.getStatus().getValue() : 0);
        map.put("clientId", user.getClient() != null ? user.getClient().getId() : null);
        map.put("createdAt", user.getCreatedAt());
        map.put("requireResponse", user.getRequireResponse() != null ? user.getRequireResponse() : false);
        map.put("hasOpenTicket", hasOpenTicket(user.getId()));
        return map;
    }

    /**
     * Check if user has an open ticket
     */
    private boolean hasOpenTicket(Long userId) {
        // TODO: Implement when Ticket repository is available
        // For now, return false
        return false;
    }

    public record CreateUserRequest(
            String email,
            String firstName,
            String lastName,
            String phone,
            String password,
            String role,
            Long managerId
    ) {}

    public record UpdateUserRequest(
            String firstName,
            String lastName,
            String phone,
            String role,
            String status
    ) {}

    public record AssignRequest(Long agentId) {}

    public record ReassignBulkRequest(List<Long> userIds, Long newAgentId) {}

    public record UpdateTempPasswordRequest(String password, String passwordConfirmation) {}
}
