package com.digitalgroup.holape.security.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        // Set required fields via reflection for testing
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecret",
                "test-secret-key-that-is-at-least-32-characters-long-for-hs256");
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtExpiration", 3600000L);
        ReflectionTestUtils.setField(jwtTokenProvider, "refreshExpiration", 86400000L);

        // Call @PostConstruct init manually
        jwtTokenProvider.init();
    }

    @Test
    void generateToken_ValidInput_ReturnsToken() {
        // generateToken(Long userId, String email, String role)
        String token = jwtTokenProvider.generateToken(123L, "test@example.com", "AGENT");

        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    void validateToken_ValidToken_ReturnsTrue() {
        String token = jwtTokenProvider.generateToken(123L, "test@example.com", "AGENT");

        assertTrue(jwtTokenProvider.validateToken(token));
    }

    @Test
    void validateToken_InvalidToken_ReturnsFalse() {
        assertFalse(jwtTokenProvider.validateToken("invalid.token.here"));
    }

    @Test
    void getUserIdFromToken_ValidToken_ReturnsUserId() {
        String token = jwtTokenProvider.generateToken(123L, "test@example.com", "AGENT");

        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        assertEquals(123L, userId);
    }

    @Test
    void getClientIdFromToken_ValidToken_ReturnsClientId() {
        String token = jwtTokenProvider.generateTokenWithClientId("test@example.com", 1L, 123L);

        Long clientId = jwtTokenProvider.getClientIdFromToken(token);

        assertEquals(1L, clientId);
    }

    @Test
    void getUsernameFromToken_ValidToken_ReturnsUsername() {
        String token = jwtTokenProvider.generateToken(123L, "test@example.com", "AGENT");

        String username = jwtTokenProvider.getUsernameFromToken(token);

        assertEquals("test@example.com", username);
    }

    @Test
    void generateRefreshToken_ValidInput_ReturnsToken() {
        String refreshToken = jwtTokenProvider.generateRefreshToken("test@example.com");

        assertNotNull(refreshToken);
        assertFalse(refreshToken.isEmpty());
        assertTrue(jwtTokenProvider.validateToken(refreshToken));
    }

    @Test
    void isImpersonating_ImpersonationToken_ReturnsTrue() {
        String token = jwtTokenProvider.generateImpersonationToken("test@example.com", 1L, 123L, 456L);

        assertTrue(jwtTokenProvider.isImpersonating(token));
    }

    @Test
    void isImpersonating_NormalToken_ReturnsFalse() {
        String token = jwtTokenProvider.generateToken(123L, "test@example.com", "AGENT");

        assertFalse(jwtTokenProvider.isImpersonating(token));
    }
}
