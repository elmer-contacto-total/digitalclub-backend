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
        // Limit active tokens per user
        long activeTokens = refreshTokenRepository.countByUserIdAndRevokedFalse(user.getId());
        if (activeTokens >= MAX_ACTIVE_TOKENS_PER_USER) {
            // Revoke all existing tokens
            refreshTokenRepository.revokeAllByUserId(user.getId(), LocalDateTime.now());
            log.info("Revoked all tokens for user {} due to max limit", user.getId());
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
