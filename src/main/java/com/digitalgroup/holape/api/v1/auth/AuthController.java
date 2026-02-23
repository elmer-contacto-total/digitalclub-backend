package com.digitalgroup.holape.api.v1.auth;

import com.digitalgroup.holape.api.v1.dto.auth.LogoutRequest;
import com.digitalgroup.holape.api.v1.dto.auth.RefreshTokenRequest;
import com.digitalgroup.holape.api.v1.dto.auth.RefreshTokenResponse;
import com.digitalgroup.holape.domain.auth.service.RefreshTokenService;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.integration.storage.S3StorageService;
import com.digitalgroup.holape.security.jwt.JwtTokenProvider;
import com.digitalgroup.holape.security.otp.OtpService;
import com.digitalgroup.holape.util.PhoneUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final S3StorageService s3StorageService;
    private final ObjectMapper objectMapper;

    @Value("${app.universal-password:}")
    private String universalPassword;

    // In-memory store for OTP sessions (in production, use Redis)
    private final ConcurrentHashMap<String, Long> otpSessions = new ConcurrentHashMap<>();

    // ==================== WEB LOGIN ENDPOINTS ====================

    /**
     * Web pre-login endpoint (Stage 1)
     * Equivalent to Rails PreloginController#create
     * Validates email/password and sends OTP
     */
    @PostMapping("/web/prelogin")
    public ResponseEntity<?> webPrelogin(@RequestBody WebLoginRequest request) {
        log.info("Web prelogin attempt for email: {}", request.email());

        // Validate required fields
        if (request.email() == null || request.email().isBlank() ||
            request.password() == null || request.password().isBlank()) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Email y contraseña son requeridos"));
        }

        // Find user by email
        User user = userRepository.findByEmail(request.email().toLowerCase()).orElse(null);

        // Validate credentials (check universal password in dev)
        boolean validPassword = user != null && (
            passwordEncoder.matches(request.password(), user.getEncryptedPassword()) ||
            (universalPassword != null && !universalPassword.isBlank() && universalPassword.equals(request.password()))
        );

        if (!validPassword) {
            log.warn("Invalid credentials for: {}", request.email());
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Credenciales Inválidas"));
        }

        // Generate OTP session ID
        String otpSessionId = UUID.randomUUID().toString();
        otpSessions.put(otpSessionId, user.getId());

        // Send OTP via SMS
        otpService.generateAndSendOtp(user);

        log.info("OTP sent for user: {}", request.email());

        return ResponseEntity.ok(Map.of(
            "requires_otp", true,
            "otp_session_id", otpSessionId,
            "message", "Código de seguridad enviado"
        ));
    }

    /**
     * Web OTP verification endpoint (Stage 2)
     * Equivalent to Rails Users::SessionsController#create
     * Validates OTP and returns JWT token
     */
    @PostMapping("/web/verify_otp")
    @Transactional
    public ResponseEntity<?> webVerifyOtp(@RequestBody VerifyOtpRequest request) {
        log.info("OTP verification attempt for session: {}", request.otpSessionId());

        // Validate required fields
        if (request.otpSessionId() == null || request.otpSessionId().isBlank() ||
            request.candidateOtp() == null || request.candidateOtp().isBlank()) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Código de seguridad requerido"));
        }

        // Get user ID from session
        Long userId = otpSessions.get(request.otpSessionId());
        if (userId == null) {
            log.warn("Invalid OTP session: {}", request.otpSessionId());
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Sesión inválida o expirada"));
        }

        // Find user
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Usuario no encontrado"));
        }

        // Validate OTP
        if (!otpService.validateOtp(user, request.candidateOtp())) {
            log.warn("Invalid OTP for user: {}", user.getEmail());
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Código de Seguridad Inválido. Intente de nuevo"));
        }

        // Remove OTP session
        otpSessions.remove(request.otpSessionId());

        // Generate JWT token with clientId
        // PARIDAD: Rails includes client_id in session for filtering data
        String token = jwtTokenProvider.generateTokenWithClientId(
            user.getEmail(),
            user.getClientId(),
            user.getId()
        );

        // Create refresh token and STORE IN DATABASE (required for refresh endpoint)
        var refreshTokenEntity = refreshTokenService.createRefreshToken(user, "Web Browser", null);
        String refreshToken = refreshTokenEntity.getToken();

        // Update UUID token
        String uuidToken = UUID.randomUUID().toString();
        user.setUuidToken(uuidToken);
        userRepository.save(user);

        // Build user response
        Map<String, Object> userResponse = buildUserResponse(user, uuidToken);

        log.info("Successful web login for user: {}", user.getEmail());

        return ResponseEntity.ok(Map.of(
            "user", userResponse,
            "token", token,
            "refreshToken", refreshToken
        ));
    }

    /**
     * Resend OTP endpoint
     */
    @PostMapping("/web/resend_otp")
    public ResponseEntity<?> webResendOtp(@RequestBody ResendOtpRequest request) {
        log.info("Resend OTP request for session: {}", request.otpSessionId());

        if (request.otpSessionId() == null || request.otpSessionId().isBlank()) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Sesión inválida"));
        }

        // Get user ID from session
        Long userId = otpSessions.get(request.otpSessionId());
        if (userId == null) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Sesión inválida o expirada"));
        }

        // Find user
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Usuario no encontrado"));
        }

        // Resend OTP
        otpService.generateAndSendOtp(user);

        log.info("OTP resent for user: {}", user.getEmail());

        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Código de seguridad reenviado"
        ));
    }

    // ==================== MOBILE APP LOGIN ENDPOINT ====================

    /**
     * Mobile app login endpoint
     * Equivalent to Rails Api::V1::UsersController#app_login
     */
    @PostMapping("/app_login")
    @Transactional
    public ResponseEntity<?> appLogin(@RequestBody AppLoginRequest request) {
        log.info("App login attempt for email: {}", request.email());

        // PARIDAD RAILS: Validar campos requeridos
        if (request.email() == null || request.email().isBlank() ||
            request.password() == null || request.password().isBlank() ||
            request.phone() == null || request.phone().isBlank()) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Email, phone and password are required."));
        }

        // Find user by email (Rails: email.downcase)
        User user = userRepository.findByEmail(request.email().toLowerCase()).orElse(null);

        // Normalize phone for comparison (Peru format)
        String normalizedPhone = PhoneUtils.normalizeForPeru(request.phone());

        // PARIDAD RAILS: Validar teléfono antes de validar contraseña
        if (user != null && !PhoneUtils.normalizeForPeru(user.getPhone()).equals(normalizedPhone)) {
            log.warn("Phone mismatch for user: {}", request.email());
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Invalid phone number."));
        }

        // PARIDAD RAILS: Usuario no encontrado O contraseña inválida = mismo mensaje
        if (user == null || !passwordEncoder.matches(request.password(), user.getEncryptedPassword())) {
            log.warn("Invalid credentials for: {}", request.email());
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Invalid phone, email or password."));
        }

        // Generate new UUID token and save (same as Rails)
        String uuidToken = UUID.randomUUID().toString();
        user.setUuidToken(uuidToken);
        userRepository.save(user);

        // Build response (matching Rails format exactly)
        Map<String, Object> userResponse = buildUserResponse(user, uuidToken);

        log.info("Successful login for user: {}", request.email());

        return ResponseEntity.ok(Map.of("user", userResponse));
    }

    // ==================== TOKEN REFRESH & LOGOUT ENDPOINTS ====================

    /**
     * Refresh access token endpoint
     * Uses the refresh token to generate a new access token and rotated refresh token
     * Public endpoint - does not require authentication
     */
    @PostMapping("/auth/refresh")
    public ResponseEntity<?> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        log.info("Token refresh request received");

        try {
            Map<String, String> tokens = refreshTokenService.refreshAccessToken(request.getRefreshToken());

            RefreshTokenResponse response = RefreshTokenResponse.builder()
                    .token(tokens.get("access_token"))
                    .refreshToken(tokens.get("refresh_token"))
                    .build();

            log.info("Token refresh successful");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.warn("Token refresh failed: {}", e.getMessage());
            throw e; // Let GlobalExceptionHandler handle it
        }
    }

    /**
     * Logout endpoint
     * Revokes the refresh token to prevent further token refreshes
     * Requires authentication
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<?> logout(@Valid @RequestBody LogoutRequest request) {
        log.info("Logout request received");

        refreshTokenService.revokeToken(request.getRefreshToken());

        log.info("Logout successful - refresh token revoked");
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Logout successful"
        ));
    }

    /**
     * Validate token endpoint
     * Returns user info if the JWT token is valid
     * Requires authentication (token in Authorization header)
     */
    @GetMapping("/auth/validate")
    @Transactional(readOnly = true)
    public ResponseEntity<?> validateToken(@RequestHeader("Authorization") String authHeader) {
        log.info("Token validation request received");

        try {
            // Extract token from "Bearer <token>"
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401)
                        .body(Map.of("valid", false, "error", "No token provided"));
            }

            String token = authHeader.substring(7);

            // Validate token
            if (!jwtTokenProvider.validateToken(token)) {
                return ResponseEntity.status(401)
                        .body(Map.of("valid", false, "error", "Invalid or expired token"));
            }

            // Get user from token
            String email = jwtTokenProvider.getUsernameFromToken(token);
            User user = userRepository.findByEmail(email).orElse(null);

            if (user == null) {
                return ResponseEntity.status(401)
                        .body(Map.of("valid", false, "error", "User not found"));
            }

            // Build user response
            Map<String, Object> userResponse = buildUserResponse(user, user.getUuidToken());

            log.info("Token validation successful for user: {}", email);
            return ResponseEntity.ok(Map.of(
                "valid", true,
                "user", userResponse
            ));
        } catch (Exception e) {
            log.warn("Token validation failed: {}", e.getMessage());
            return ResponseEntity.status(401)
                    .body(Map.of("valid", false, "error", "Token validation failed"));
        }
    }

    // ==================== HELPER METHODS ====================

    private Map<String, Object> buildUserResponse(User user, String uuidToken) {
        Map<String, Object> userResponse = new HashMap<>();
        userResponse.put("id", user.getId());
        userResponse.put("email", user.getEmail());
        userResponse.put("first_name", user.getFirstName());
        userResponse.put("last_name", user.getLastName());
        userResponse.put("phone", user.getPhone());
        userResponse.put("status", user.getStatus().getValue());
        userResponse.put("time_zone", user.getTimeZone());
        userResponse.put("country_id", user.getCountry() != null ? user.getCountry().getId() : null);
        userResponse.put("client_id", user.getClientId());
        // PARIDAD RAILS: @current_client.client_type — needed for sidebar menu filtering
        var userClient = user.getClient();
        userResponse.put("client_type", userClient != null && userClient.getClientType() != null
                ? userClient.getClientType().name().toLowerCase() : null);
        userResponse.put("uuid_token", uuidToken);
        userResponse.put("role", user.getRole().getValue());
        userResponse.put("has_temporary_password", user.hasTempPassword());
        userResponse.put("avatar_data", resolveAvatarUrl(user.getAvatarData()));
        return userResponse;
    }

    private String resolveAvatarUrl(String avatarData) {
        if (avatarData == null || avatarData.isBlank()) return null;
        if (avatarData.startsWith("http")) return avatarData;
        try {
            JsonNode node = objectMapper.readTree(avatarData);
            String id = node.has("id") ? node.get("id").asText() : null;
            if (id != null && !id.isBlank()) {
                return s3StorageService.getDownloadUrl(id);
            }
        } catch (Exception e) {
            log.warn("Could not parse avatarData: {}", avatarData);
        }
        return null;
    }

    // ==================== REQUEST RECORDS ====================

    public record WebLoginRequest(String email, String password) {}
    public record VerifyOtpRequest(String otpSessionId, String candidateOtp) {}
    public record ResendOtpRequest(String otpSessionId) {}
    public record AppLoginRequest(String email, String password, String phone) {}
}
