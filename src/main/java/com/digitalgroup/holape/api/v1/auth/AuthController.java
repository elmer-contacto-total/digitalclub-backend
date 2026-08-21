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

import java.time.Duration;
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
    private final com.digitalgroup.holape.security.jwt.TokenBlacklistService tokenBlacklistService;

    @Value("${app.universal-password:}")
    private String universalPassword;

    // In-memory store for OTP sessions (in production, use Redis)
    // V03: la sesion lleva contador de intentos y vencimiento. Antes se podian
    // probar codigos ilimitadamente dentro de la vigencia del OTP (100 intentos
    // bastaron en la prueba para acertar y obtener un token valido).
    // El 3er fallo corta: fallo1, fallo2, fallo3 -> \"demasiados intentos\".
    private static final int MAX_OTP_ATTEMPTS = 3;
    private static final Duration OTP_SESSION_TTL = Duration.ofMinutes(5);
    // V04: minimo entre solicitudes de OTP para una misma cuenta.
    private static final Duration OTP_RESEND_COOLDOWN = Duration.ofSeconds(60);

    private static final class OtpSession {
        final Long userId;
        volatile String channel;
        final java.time.Instant createdAt = java.time.Instant.now();
        final java.util.concurrent.atomic.AtomicInteger attempts =
                new java.util.concurrent.atomic.AtomicInteger(0);

        OtpSession(Long userId, String channel) {
            this.userId = userId;
            this.channel = channel;
        }
        Long userId() { return userId; }
        String channel() { return channel; }
        java.util.concurrent.atomic.AtomicInteger attempts() { return attempts; }
        boolean isExpired() {
            return java.time.Instant.now().isAfter(createdAt.plus(OTP_SESSION_TTL));
        }
    }

    private final ConcurrentHashMap<String, OtpSession> otpSessions = new ConcurrentHashMap<>();
    /** V04: ultima emision de OTP por usuario, para el cooldown. */
    private final ConcurrentHashMap<Long, java.time.Instant> lastOtpIssuedAt = new ConcurrentHashMap<>();

    /** Descarta sesiones OTP vencidas para que el mapa no crezca sin limite. */
    private void purgeExpiredOtpSessions() {
        otpSessions.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    /** V04: true si al usuario ya se le emitio un OTP hace menos del cooldown. */
    private boolean isOtpThrottled(Long userId) {
        java.time.Instant last = lastOtpIssuedAt.get(userId);
        return last != null && java.time.Instant.now().isBefore(last.plus(OTP_RESEND_COOLDOWN));
    }

    // V04: rate limit por IP con bloqueo temporal PROGRESIVO.
    //
    // NOTA DE DISENO: la defensa PRINCIPAL contra el flooding es el cooldown por
    // CUENTA (OTP_RESEND_COOLDOWN), que es inmune al NAT porque va por usuario.
    // Este limite por IP es defensa secundaria (atacante que rota cuentas desde
    // una IP) y por eso el umbral es ALTO A PROPOSITO: una oficina entera detras
    // de una sola IP publica (NAT) puede loguearse en la mañana sin tocarlo,
    // mientras que un script automatizado lo supera de inmediato.
    //
    // Configurable en application.yml; poner max-per-window=0 lo DESACTIVA.
    @Value("${app.otp.ip-rate-limit.max-per-window:120}")
    private int ipFreeRequests;
    @Value("${app.otp.ip-rate-limit.window-minutes:10}")
    private long ipWindowMinutes;
    private static final long[] IP_BACKOFF_SECONDS = {60, 300, 900, 1800};

    private static final class IpThrottleState {
        volatile java.time.Instant windowStart = java.time.Instant.now();
        final java.util.concurrent.atomic.AtomicInteger count =
                new java.util.concurrent.atomic.AtomicInteger(0);
        volatile java.time.Instant blockedUntil = null;
    }

    private final ConcurrentHashMap<String, IpThrottleState> ipThrottle = new ConcurrentHashMap<>();

    /**
     * Extrae la IP real del cliente de forma NO falsificable.
     *
     * SEGURIDAD: NO se usa X-Forwarded-For. nginx lo arma con
     * $proxy_add_x_forwarded_for, que ANEXA la IP real a lo que mande el cliente;
     * su primer elemento es texto controlado por el atacante y permitiria evadir
     * el rate-limit rotando el valor. Se usa X-Real-IP, que nginx SOBREESCRIBE con
     * la IP del peer TCP ($remote_addr) e ignora lo que envie el cliente. Si por
     * algun motivo no esta (acceso directo sin proxy), se cae a getRemoteAddr(),
     * que es la conexion TCP real y tampoco es falsificable.
     */
    private String clientIp(jakarta.servlet.http.HttpServletRequest req) {
        String realIp = req.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return req.getRemoteAddr();
    }

    /**
     * V04: registra una solicitud de OTP desde una IP y devuelve los segundos que
     * debe esperar (0 = permitido). Bloqueo progresivo por origen.
     */
    private long ipRetryAfterSeconds(String ip) {
        // Off switch: umbral <= 0 desactiva por completo el limite por IP.
        if (ipFreeRequests <= 0) {
            return 0;
        }
        java.time.Instant now = java.time.Instant.now();
        Duration window = Duration.ofMinutes(ipWindowMinutes);
        IpThrottleState st = ipThrottle.computeIfAbsent(ip, k -> new IpThrottleState());
        synchronized (st) {
            if (st.blockedUntil != null && now.isBefore(st.blockedUntil)) {
                return Duration.between(now, st.blockedUntil).getSeconds() + 1;
            }
            // Ventana expirada: reiniciar el conteo.
            if (now.isAfter(st.windowStart.plus(window))) {
                st.windowStart = now;
                st.count.set(0);
                st.blockedUntil = null;
            }
            int n = st.count.incrementAndGet();
            if (n <= ipFreeRequests) {
                return 0;
            }
            int over = n - ipFreeRequests - 1;
            long wait = IP_BACKOFF_SECONDS[Math.min(over, IP_BACKOFF_SECONDS.length - 1)];
            st.blockedUntil = now.plusSeconds(wait);
            return wait;
        }
    }

    /** Limpia estado de IP inactivo para que el mapa no crezca sin limite. */
    private void purgeIpThrottle() {
        java.time.Instant cutoff = java.time.Instant.now().minus(Duration.ofMinutes(ipWindowMinutes * 2));
        ipThrottle.entrySet().removeIf(e ->
                e.getValue().windowStart.isBefore(cutoff)
                && (e.getValue().blockedUntil == null || e.getValue().blockedUntil.isBefore(java.time.Instant.now())));
    }

    // V05 (OTP Flooding): limite POR CUENTA con rafaga permitida. Se admiten
    // ACCOUNT_OTP_BURST OTPs por ventana (cubre el reintento legitimo tras agotar
    // los intentos de OTP: el usuario vuelve al login y re-solicita), y a partir de
    // ahi se aplica bloqueo temporal progresivo. Asi se corta el envio masivo
    // (11 OTP seguidos en el retest) sin trabar el re-login normal.
    private static final int ACCOUNT_OTP_BURST = 3;
    private static final Duration ACCOUNT_OTP_WINDOW = Duration.ofMinutes(3);
    private final ConcurrentHashMap<Long, IpThrottleState> accountOtpThrottle = new ConcurrentHashMap<>();

    private long accountOtpRetryAfterSeconds(Long userId) {
        if (userId == null) return 0;
        java.time.Instant now = java.time.Instant.now();
        IpThrottleState st = accountOtpThrottle.computeIfAbsent(userId, k -> new IpThrottleState());
        synchronized (st) {
            if (st.blockedUntil != null && now.isBefore(st.blockedUntil)) {
                return Duration.between(now, st.blockedUntil).getSeconds() + 1;
            }
            if (now.isAfter(st.windowStart.plus(ACCOUNT_OTP_WINDOW))) {
                st.windowStart = now;
                st.count.set(0);
                st.blockedUntil = null;
            }
            int n = st.count.incrementAndGet();
            if (n <= ACCOUNT_OTP_BURST) {
                return 0;
            }
            int over = n - ACCOUNT_OTP_BURST - 1;
            long wait = IP_BACKOFF_SECONDS[Math.min(over, IP_BACKOFF_SECONDS.length - 1)];
            st.blockedUntil = now.plusSeconds(wait);
            return wait;
        }
    }

    private void purgeAccountOtpThrottle() {
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant cutoff = now.minus(ACCOUNT_OTP_WINDOW.multipliedBy(2));
        accountOtpThrottle.entrySet().removeIf(e ->
                e.getValue().windowStart.isBefore(cutoff)
                && (e.getValue().blockedUntil == null || e.getValue().blockedUntil.isBefore(now)));
    }

    // ==================== WEB LOGIN ENDPOINTS ====================

    /**
     * Web pre-login endpoint (Stage 1)
     * Equivalent to Rails PreloginController#create
     * Validates email/password and sends OTP
     */
    @PostMapping("/web/prelogin")
    public ResponseEntity<?> webPrelogin(@RequestBody WebLoginRequest request,
                                         jakarta.servlet.http.HttpServletRequest httpRequest) {
        log.info("Web prelogin attempt for email: {}", request.email());

        // V04: rate limit por IP (bloqueo progresivo) ANTES de validar credenciales,
        // para que no se pueda usar este endpoint como oraculo ni como via de flooding.
        String ip = clientIp(httpRequest);
        long retryAfter = ipRetryAfterSeconds(ip);
        if (retryAfter > 0) {
            log.warn("SECURITY: prelogin bloqueado por rate-limit de IP {} ({}s)", ip, retryAfter);
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(retryAfter))
                    .body(Map.of("error", "Demasiadas solicitudes. Intente nuevamente en " + retryAfter + " segundos."));
        }
        purgeIpThrottle();

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

        // V05 (OTP Flooding): en vez de un cooldown fijo por cuenta (que bloqueaba el
        // re-login legitimo tras agotar los intentos de OTP), se aplica un limite de
        // RAFAGA (abajo): admite unas pocas solicitudes seguidas (re-login) y a partir
        // de ahi bloquea con espera progresiva, cortando el envio masivo de OTPs.
        // Cada prelogin ademas exige credenciales validas y esta cubierto por el
        // rate-limit por IP (arriba).
        purgeExpiredOtpSessions();
        purgeAccountOtpThrottle();

        // V05 (OTP Flooding): limite por cuenta con rafaga permitida.
        long acctRetry = accountOtpRetryAfterSeconds(user.getId());
        if (acctRetry > 0) {
            log.warn("SECURITY: OTP flooding bloqueado para la cuenta {} ({}s)", user.getId(), acctRetry);
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(acctRetry))
                    .body(Map.of("error", "Demasiadas solicitudes de código. Intente nuevamente en " + acctRetry + " segundos."));
        }

        // Generate OTP session ID
        String otpSessionId = UUID.randomUUID().toString();
        String channel = (request.otpChannel() != null && !request.otpChannel().isBlank())
                ? request.otpChannel() : "sms";
        otpSessions.put(otpSessionId, new OtpSession(user.getId(), channel));

        // Send OTP via selected channel
        otpService.generateAndSendOtp(user, channel);
        lastOtpIssuedAt.put(user.getId(), java.time.Instant.now());

        log.info("OTP sent for user: {} via {}", request.email(), channel);

        return ResponseEntity.ok(Map.of(
            "requires_otp", true,
            "otp_session_id", otpSessionId,
            "otp_channel", channel,
            "otp_destination", maskDestination(user, channel),
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

        // Get session
        OtpSession session = otpSessions.get(request.otpSessionId());
        if (session == null) {
            log.warn("Invalid OTP session: {}", request.otpSessionId());
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Sesión inválida o expirada"));
        }

        // V03: la sesion vence aunque el OTP siguiera vigente.
        if (session.isExpired()) {
            otpSessions.remove(request.otpSessionId());
            log.warn("Expired OTP session: {}", request.otpSessionId());
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Sesión inválida o expirada"));
        }

        // Find user
        User user = userRepository.findById(session.userId()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Usuario no encontrado"));
        }

        // Validate OTP
        if (!otpService.validateOtp(user, request.candidateOtp())) {
            // V03: se cuenta el intento fallido. En el 3er fallo (umbral) se invalida
            // la sesion Y el OTP, obligando a solicitar uno nuevo. Un codigo correcto
            // en el 3er intento SI se acepta (la validacion ocurre antes de este corte).
            int failedAttempts = session.attempts().incrementAndGet();
            if (failedAttempts >= MAX_OTP_ATTEMPTS) {
                otpSessions.remove(request.otpSessionId());
                otpService.clearOtp(user);
                log.warn("SECURITY: limite de intentos de OTP alcanzado para la sesion {} (usuario {})",
                        request.otpSessionId(), session.userId());
                return ResponseEntity.status(429)
                        .body(Map.of("error", "Demasiados intentos fallidos. Solicite un nuevo código."));
            }
            log.warn("Invalid OTP for user: {} (intento {})", user.getEmail(), failedAttempts);
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

        // PARIDAD RAILS: el login web (Devise Users::SessionsController#create) NO modifica uuid_token.
        // El uuid_token identifica al DISPOSITIVO MÓVIL del agente (se asigna solo en /app_login).
        // Regenerarlo aquí invalidaba la sesión del app móvil del agente -> sus mensajes caían en
        // "User not found in user table". Devolvemos el valor existente sin tocarlo (igual que /validate).
        Map<String, Object> userResponse = buildUserResponse(user, user.getUuidToken());

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
    public ResponseEntity<?> webResendOtp(@RequestBody ResendOtpRequest request,
                                          jakarta.servlet.http.HttpServletRequest httpRequest) {
        log.info("Resend OTP request for session: {}", request.otpSessionId());

        // V04: mismo rate-limit por IP que prelogin (resend es via alterna de flooding).
        String ip = clientIp(httpRequest);
        long retryAfter = ipRetryAfterSeconds(ip);
        if (retryAfter > 0) {
            log.warn("SECURITY: resend bloqueado por rate-limit de IP {} ({}s)", ip, retryAfter);
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(retryAfter))
                    .body(Map.of("error", "Demasiadas solicitudes. Intente nuevamente en " + retryAfter + " segundos."));
        }

        if (request.otpSessionId() == null || request.otpSessionId().isBlank()) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Sesión inválida"));
        }

        // Get session
        OtpSession session = otpSessions.get(request.otpSessionId());
        if (session == null) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Sesión inválida o expirada"));
        }

        // Find user
        User user = userRepository.findById(session.userId()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(422)
                    .body(Map.of("error", "Usuario no encontrado"));
        }

        // V04: mismo cooldown que prelogin — resend era una via alterna para inundar.
        if (isOtpThrottled(user.getId())) {
            log.warn("OTP resend throttled for user: {}", user.getEmail());
            return ResponseEntity.status(429)
                    .body(Map.of("error", "Ya se envio un codigo recientemente. Espere un momento antes de solicitar otro."));
        }

        // Use provided channel or fallback to stored channel
        String channel = (request.otpChannel() != null && !request.otpChannel().isBlank())
                ? request.otpChannel() : session.channel();
        // Sesion nueva: reinicia el contador de intentos y la ventana de expiracion.
        otpSessions.put(request.otpSessionId(), new OtpSession(session.userId(), channel));
        otpService.generateAndSendOtp(user, channel);
        lastOtpIssuedAt.put(user.getId(), java.time.Instant.now());

        log.info("OTP resent for user: {} via {}", user.getEmail(), channel);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "otp_channel", channel,
            "otp_destination", maskDestination(user, channel),
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
    public ResponseEntity<?> logout(@Valid @RequestBody LogoutRequest request,
                                    @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("Logout request received");

        refreshTokenService.revokeToken(request.getRefreshToken());

        // V06: revocar tambien el access token para que no siga siendo usable.
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String accessToken = authHeader.substring(7);
            try {
                if (jwtTokenProvider.validateToken(accessToken)) {
                    long remainingMs = jwtTokenProvider.getExpirationDate(accessToken).getTime()
                            - System.currentTimeMillis();
                    if (remainingMs > 0) {
                        tokenBlacklistService.revoke(accessToken, java.time.Duration.ofMillis(remainingMs));
                    }
                }
            } catch (Exception e) {
                log.warn("No se pudo revocar el access token en logout: {}", e.getMessage());
            }
        }

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
        userResponse.put("client_logo_url", userClient != null ? userClient.getLogoUrl() : null);
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
                if (id.startsWith("avatars/")) {
                    return s3StorageService.getDownloadUrl(id);
                }
                // Legacy Shrine files in bucket root — try presigned URL
                try {
                    return s3StorageService.getPresignedUrl(id, Duration.ofHours(24)).toString();
                } catch (Exception presignEx) {
                    log.warn("Presigned URL failed for avatar key '{}': {}, falling back to public URL",
                            id, presignEx.getMessage());
                    return s3StorageService.getDownloadUrl(id);
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse avatarData: {}", avatarData);
        }
        return null;
    }

    // ==================== PRIVATE HELPERS ====================

    private String maskDestination(User user, String channel) {
        if ("email".equals(channel)) {
            String email = user.getEmail();
            int at = email.indexOf('@');
            String local = at > 2 ? email.substring(0, 2) + "***" : "***";
            return local + email.substring(at);
        } else {
            String phone = user.getPhone() != null ? user.getPhone() : "";
            return phone.length() > 4 ? "***" + phone.substring(phone.length() - 4) : "***";
        }
    }

    // ==================== REQUEST RECORDS ====================

    public record WebLoginRequest(String email, String password, String otpChannel) {}
    public record VerifyOtpRequest(String otpSessionId, String candidateOtp) {}
    public record ResendOtpRequest(String otpSessionId, String otpChannel) {}
    public record AppLoginRequest(String email, String password, String phone) {}
}
