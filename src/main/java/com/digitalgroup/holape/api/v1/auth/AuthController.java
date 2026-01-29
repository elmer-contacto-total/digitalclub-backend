package com.digitalgroup.holape.api.v1.auth;

import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.security.jwt.JwtTokenProvider;
import com.digitalgroup.holape.security.otp.OtpService;
import com.digitalgroup.holape.util.PhoneUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
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
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getEmail());

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
        userResponse.put("uuid_token", uuidToken);
        userResponse.put("role", user.getRole().getValue());
        userResponse.put("has_temporary_password", user.hasTempPassword());
        return userResponse;
    }

    // ==================== REQUEST RECORDS ====================

    public record WebLoginRequest(String email, String password) {}
    public record VerifyOtpRequest(String otpSessionId, String candidateOtp) {}
    public record ResendOtpRequest(String otpSessionId) {}
    public record AppLoginRequest(String email, String password, String phone) {}
}
