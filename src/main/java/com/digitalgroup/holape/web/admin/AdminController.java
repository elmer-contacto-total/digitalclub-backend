package com.digitalgroup.holape.web.admin;

import com.digitalgroup.holape.domain.client.entity.Client;
import com.digitalgroup.holape.domain.client.service.ClientService;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.service.UserService;
import com.digitalgroup.holape.security.CustomUserDetails;
import com.digitalgroup.holape.security.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin Controller
 * PARIDAD: Rails Admin::AdminController
 *
 * Handles admin-level actions like setting the current client for Super Admin users.
 */
@Slf4j
@RestController
@RequestMapping("/app")
@RequiredArgsConstructor
public class AdminController {

    private final ClientService clientService;
    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Set current client for Super Admin
     * PARIDAD: Rails Admin::AdminController#set_current_client
     *
     * This endpoint allows Super Admin users to switch between different clients/organizations.
     * A new JWT token is generated with the selected client ID.
     *
     * @param currentUser The authenticated user (must be Super Admin)
     * @param clientId The ID of the client to switch to
     * @return New JWT token with the selected client ID
     */
    @PostMapping("/set_current_client/{clientId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> setCurrentClient(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @PathVariable Long clientId) {

        log.info("Super Admin {} switching to client {}", currentUser.getEmail(), clientId);

        // Verify client exists and is active
        Client client;
        try {
            client = clientService.findById(clientId);
        } catch (Exception e) {
            log.warn("Client not found: {}", clientId);
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Cliente no encontrado"));
        }

        if (!client.isActive()) {
            log.warn("Client {} is not active", clientId);
            return ResponseEntity.status(400)
                    .body(Map.of("error", "El cliente no está activo"));
        }

        // Get user entity for additional info
        User user = userService.findById(currentUser.getId());

        // Generate new JWT token with the selected client ID
        String newToken = jwtTokenProvider.generateTokenWithClientId(
                user.getEmail(),
                clientId,
                user.getId()
        );

        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getEmail());

        log.info("Successfully switched to client {} for user {}", clientId, currentUser.getEmail());

        return ResponseEntity.ok(Map.of(
                "token", newToken,
                "refreshToken", refreshToken,
                "client", Map.of(
                        "id", client.getId(),
                        "name", client.getName()
                ),
                "message", "Cliente activo actualizado"
        ));
    }
}
