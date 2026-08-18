package com.digitalgroup.holape.security.jwt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Revocacion de access tokens (V06).
 *
 * MOTIVO (hallazgo V06 - Inadecuada gestion de sesion):
 * El logout solo revocaba el refresh token; el access token (JWT stateless)
 * seguia siendo valido hasta su expiracion natural. Esta clase mantiene una lista
 * de revocacion en Redis: en el logout se agrega el token, y JwtAuthenticationFilter
 * lo consulta en cada peticion.
 *
 * Se indexa por hash SHA-256 del token (no se almacena el token en claro) con TTL
 * igual al tiempo restante de vida, de modo que la entrada se auto-elimina cuando
 * el token expira.
 *
 * Degradacion: ante error de Redis se registra y se continua (fail-open) para no
 * tumbar la autenticacion por una caida de cache. El refresh token revocado sigue
 * cortando la renovacion, por lo que la exposicion residual es acotada.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String PREFIX = "jwt:blacklist:";

    private final RedisTemplate<String, Object> redisTemplate;

    public void revoke(String token, Duration ttl) {
        if (token == null || token.isBlank() || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(PREFIX + hash(token), "1", ttl);
        } catch (Exception e) {
            log.warn("No se pudo registrar el token revocado en Redis: {}", e.getMessage());
        }
    }

    public boolean isRevoked(String token) {
        if (token == null || token.isBlank()) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(PREFIX + hash(token)));
        } catch (Exception e) {
            // Fail-open: no bloquear la autenticacion por una caida de Redis.
            log.warn("No se pudo consultar la lista de revocacion en Redis: {}", e.getMessage());
            return false;
        }
    }

    private String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            // SHA-256 siempre existe; si fallara, no indexar en claro.
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
