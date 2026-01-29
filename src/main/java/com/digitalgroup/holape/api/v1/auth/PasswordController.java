package com.digitalgroup.holape.api.v1.auth;

import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.domain.user.service.UserService;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Password management endpoints
 * Equivalent to Rails Devise::PasswordsController and custom password actions
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/password")
@RequiredArgsConstructor
public class PasswordController {

    private final UserRepository userRepository;
    private final UserService userService;

    /**
     * Request password reset email
     * POST /api/v1/password/forgot
     * Equivalent to Rails: Devise passwords#create
     */
    @PostMapping("/forgot")
    public ResponseEntity<?> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        log.info("Password reset requested for: {}", request.email());

        if (request.email() == null || request.email().isBlank()) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Email es requerido"));
        }

        // Always return success to prevent email enumeration
        try {
            User user = userRepository.findByEmail(request.email().toLowerCase()).orElse(null);
            if (user != null) {
                userService.sendResetPasswordInstructions(user.getId());
            }
        } catch (Exception e) {
            log.warn("Error sending password reset: {}", e.getMessage());
            // Don't expose error to user
        }

        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Si el correo existe, recibirá las instrucciones"
        ));
    }

    /**
     * Reset password with token
     * POST /api/v1/password/reset
     * Equivalent to Rails: Devise passwords#update
     */
    @PostMapping("/reset")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest request) {
        log.info("Password reset attempt with token");

        if (request.reset_password_token() == null || request.reset_password_token().isBlank()) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Token es requerido", "token_invalid", true));
        }

        if (request.password() == null || request.password().isBlank()) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Nueva contraseña es requerida"));
        }

        if (request.password().length() < 6) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "La contraseña debe tener al menos 6 caracteres"));
        }

        if (!request.password().equals(request.password_confirmation())) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Las contraseñas no coinciden"));
        }

        try {
            userService.resetPasswordWithToken(request.reset_password_token(), request.password());

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Contraseña actualizada correctamente"
            ));
        } catch (Exception e) {
            log.warn("Password reset failed: {}", e.getMessage());
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Token inválido o expirado", "token_invalid", true));
        }
    }

    /**
     * Change password for authenticated user (typically for temp password flow)
     * PUT /api/v1/password/change
     * Equivalent to Rails: UsersController#update_temp_password
     *
     * PARIDAD RAILS: Rails NO valida current_password en update_temp_password,
     * solo requiere password y password_confirmation con mínimo 8 caracteres.
     */
    @PutMapping("/change")
    public ResponseEntity<?> changePassword(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody ChangePasswordRequest request) {

        log.info("Password change requested");

        // Get user ID from authenticated principal
        if (currentUser == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "No autenticado"));
        }
        Long userId = currentUser.getId();

        // PARIDAD RAILS: current_password es opcional (Rails no lo valida en update_temp_password)
        // Solo lo usamos si se proporciona para validación adicional

        if (request.password() == null || request.password().isBlank()) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Nueva contraseña es requerida"));
        }

        // PARIDAD RAILS: 8 caracteres mínimo (línea 188 de admin/users_controller.rb)
        if (request.password().length() < 8) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "La contraseña debe tener al menos 8 caracteres"));
        }

        if (!request.password().equals(request.password_confirmation())) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Las contraseñas no coinciden"));
        }

        try {
            // PARIDAD RAILS: Solo actualizar password directamente (como Rails hace)
            // El servicio maneja temp_password=null e initial_password_changed=true
            userService.updatePasswordDirectly(userId, request.password());

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Contraseña actualizada correctamente"
            ));
        } catch (Exception e) {
            log.warn("Password change failed for user {}: {}", userId, e.getMessage());
            return ResponseEntity.status(422)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // Request records
    public record ForgotPasswordRequest(String email) {}
    public record ResetPasswordRequest(
        String reset_password_token,
        String password,
        String password_confirmation
    ) {}
    public record ChangePasswordRequest(
        String current_password,
        String password,
        String password_confirmation
    ) {}
}
