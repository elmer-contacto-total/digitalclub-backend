package com.digitalgroup.holape.domain.auth.service;

import com.digitalgroup.holape.domain.auth.entity.RefreshToken;
import com.digitalgroup.holape.domain.auth.repository.RefreshTokenRepository;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import com.digitalgroup.holape.exception.InvalidTokenException;
import com.digitalgroup.holape.exception.ResourceNotFoundException;
import com.digitalgroup.holape.security.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Comparator;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Value("${jwt.refresh-expiration:604800000}") // 7 days default
    private long refreshExpirationMs;

    private static final int MAX_ACTIVE_TOKENS_PER_USER = 5;

    /**
     * Creates a new refresh token for the user
     */
    @Transactional
    public RefreshToken createRefreshToken(User user, String deviceInfo, String ipAddress) {
        // Limite de sesiones activas por usuario.
        //
        // Antes, al llegar al limite se revocaban TODAS las sesiones del usuario,
        // incluida la que estaba en uso en ese momento. Medido en produccion el
        // 28/09/2026 sobre una sola cuenta: dos barridas de 5 tokens de golpe
        // (12:38:45 y 13:12:24) que dejaron sin sesion a las ventanas abiertas.
        // Duele especialmente al suplantar, porque /login_as no emite refresh
        // token propio y esa ventana viaja con el del administrador.
        //
        // Ahora se revocan solo las MAS ANTIGUAS, las justas para dejar sitio a
        // la nueva. Las sesiones recientes siguen vivas.
        List<RefreshToken> activos = refreshTokenRepository.findByUserIdAndRevokedFalse(user.getId());
        int sobran = activos.size() - (MAX_ACTIVE_TOKENS_PER_USER - 1);
        if (sobran > 0) {
            activos.stream()
                    .sorted(Comparator.comparing(RefreshToken::getCreatedAt))
                    .limit(sobran)
                    .forEach(t -> {
                        t.revoke();
                        refreshTokenRepository.save(t);
                    });
            log.info("Revoked {} oldest token(s) for user {} to stay under the limit",
                    sobran, user.getId());
        }

        String tokenValue = jwtTokenProvider.generateRefreshToken(user.getEmail());
        LocalDateTime expiresAt = LocalDateTime.now().plus(refreshExpirationMs, ChronoUnit.MILLIS);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(tokenValue)
                .expiresAt(expiresAt)
                .deviceInfo(deviceInfo)
                .ipAddress(ipAddress)
                .build();

        return refreshTokenRepository.save(refreshToken);
    }

    /**
     * Refreshes the access token using a valid refresh token
     * Returns new access_token and optionally rotates the refresh_token
     */
    @Transactional
    public Map<String, String> refreshAccessToken(String refreshTokenValue) {
        RefreshToken refreshToken = refreshTokenRepository.findByTokenAndRevokedFalse(refreshTokenValue)
                .orElseThrow(() -> new InvalidTokenException("Invalid or revoked refresh token"));

        if (refreshToken.isExpired()) {
            refreshToken.revoke();
            refreshTokenRepository.save(refreshToken);
            throw new InvalidTokenException("Refresh token has expired");
        }

        User user = refreshToken.getUser();
        if (!user.isActive()) {
            refreshToken.revoke();
            refreshTokenRepository.save(refreshToken);
            throw new InvalidTokenException("User account is inactive");
        }

        // Generate new access token
        String newAccessToken = jwtTokenProvider.generateTokenWithClientId(
                user.getEmail(),
                user.getClientId(),
                user.getId()
        );

        // Token rotation: create new refresh token and revoke old one
        refreshToken.revoke();
        refreshTokenRepository.save(refreshToken);

        RefreshToken newRefreshToken = createRefreshToken(
                user,
                refreshToken.getDeviceInfo(),
                refreshToken.getIpAddress()
        );

        return Map.of(
                "access_token", newAccessToken,
                "refresh_token", newRefreshToken.getToken()
        );
    }

    /**
     * Validates a refresh token without refreshing
     */
    @Transactional(readOnly = true)
    public boolean validateRefreshToken(String token) {
        return refreshTokenRepository.findByTokenAndRevokedFalse(token)
                .map(RefreshToken::isValid)
                .orElse(false);
    }

    /**
     * Revokes a specific refresh token
     */
    @Transactional
    public void revokeToken(String token) {
        refreshTokenRepository.findByToken(token).ifPresent(refreshToken -> {
            refreshToken.revoke();
            refreshTokenRepository.save(refreshToken);
            log.info("Revoked refresh token for user {}", refreshToken.getUser().getId());
        });
    }

    /**
     * Revokes all refresh tokens for a user (logout from all devices)
     */
    @Transactional
    public void revokeAllUserTokens(Long userId) {
        int count = refreshTokenRepository.revokeAllByUserId(userId, LocalDateTime.now());
        log.info("Revoked {} refresh tokens for user {}", count, userId);
    }

    /**
     * Cleanup job: removes expired and revoked tokens
     * Runs daily at 3 AM
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpiredTokens() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(30);
        int deleted = refreshTokenRepository.deleteExpiredAndRevoked(threshold);
        if (deleted > 0) {
            log.info("Cleaned up {} expired/revoked refresh tokens", deleted);
        }
    }
}
