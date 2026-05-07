package com.digitalgroup.holape.api.v1.auth;

import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.domain.user.service.UserService;
import com.digitalgroup.holape.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    private final PasswordEncoder passwordEncoder;

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
     * Change password for authenticated user.
     * PUT /api/v1/password/change
     *
     * SECURITY: requiere current_password obligatorio para prevenir que un atacante
     * con un token JWT robado pueda cambiar la contraseña sin conocer la actual.
     * Excepción: usuarios con temp_password (primer login) no necesitan current_password,
     * porque su "actual" es la temp asignada por el admin.
     */
    @PutMapping("/change")
    public ResponseEntity<?> changePassword(
            @AuthenticationPrincipal CustomUserDetails currentUser,
            @RequestBody ChangePasswordRequest request) {

        log.info("Password change requested");

        if (currentUser == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "No autenticado"));
        }
        Long userId = currentUser.getId();

        // Cargar usuario para validar current_password contra el hash almacenado
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Usuario no encontrado"));
        }

        if (request.password() == null || request.password().isBlank()) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Nueva contraseña es requerida"));
        }

        if (request.password().length() < 8) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "La contraseña debe tener al menos 8 caracteres"));
        }

        if (!request.password().equals(request.password_confirmation())) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Las contraseñas no coinciden"));
        }

        // Validación de current_password.
        // Excepción: si el usuario tiene temp_password activo (primer login forzado),
        // no se exige current_password — la temp ya fue validada al login.
        boolean hasTempPassword = user.getTempPassword() != null && !user.getTempPassword().isBlank();
        if (!hasTempPassword) {
            if (request.current_password() == null || request.current_password().isBlank()) {
                return ResponseEntity.status(422)
                        .body(Map.of("error", "La contraseña actual es requerida"));
            }
            if (!passwordEncoder.matches(request.current_password(), user.getEncryptedPassword())) {
                log.warn("Password change failed: current_password mismatch for user {}", userId);
                return ResponseEntity.status(422)
                        .body(Map.of("error", "La contraseña actual es incorrecta"));
            }
            // Defensa adicional: la nueva debe ser distinta de la actual
            if (request.current_password().equals(request.password())) {
                return ResponseEntity.status(422)
                        .body(Map.of("error", "La nueva contraseña debe ser diferente de la actual"));
            }
        }

        try {
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
