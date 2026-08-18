package com.digitalgroup.holape.security.jwt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Revocacion de access tokens (V06).
 *
 * MOTIVO (hallazgo V06 - Inadecuada gestion de sesion):
 * El logout solo revocaba el refresh token; el access token (JWT stateless)
 * seguia siendo valido hasta su expiracion natural. Esta clase mantiene una lista
 * de revocacion consultada por JwtAuthenticationFilter y el handshake de WebSocket.
 *
 * Almacenamiento EN MEMORIA (ConcurrentHashMap): cada sitio corre en una unica
 * instancia y no hay Redis instalado, por lo que un store en memoria es adecuado y
 * evita una dependencia de infraestructura para un hallazgo de severidad baja. Se
 * indexa por hash SHA-256 del token (no se guarda el token en claro) con marca de
 * expiracion; las entradas vencidas se purgan de forma perezosa.
 *
 * Limitacion conocida: la lista se pierde ante reinicio del proceso; un token
 * revocado volveria a ser aceptado hasta expirar. Para una instancia unica y
 * severidad baja es aceptable; mitigable reduciendo el TTL del access token. Si en
 * el futuro se escala horizontalmente, migrar este store a Redis manteniendo esta
 * misma API publica.
 */
@Slf4j
@Service
public class TokenBlacklistService {

    /** hash del token -> epoch millis de expiracion. */
    private final ConcurrentHashMap<String, Long> revoked = new ConcurrentHashMap<>();

    public void revoke(String token, Duration ttl) {
        if (token == null || token.isBlank() || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        purgeExpired();
        revoked.put(hash(token), System.currentTimeMillis() + ttl.toMillis());
    }

    public boolean isRevoked(String token) {
        if (token == null || token.isBlank()) return false;
        Long expiry = revoked.get(hash(token));
        if (expiry == null) return false;
        if (System.currentTimeMillis() >= expiry) {
            revoked.remove(hash(token));
            return false;
        }
        return true;
    }

    /** Elimina entradas ya vencidas para acotar el crecimiento del mapa. */
    private void purgeExpired() {
        long now = System.currentTimeMillis();
        revoked.entrySet().removeIf(e -> now >= e.getValue());
    }

    private String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
