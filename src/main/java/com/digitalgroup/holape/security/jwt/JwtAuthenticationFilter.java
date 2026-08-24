package com.digitalgroup.holape.security.jwt;

import com.digitalgroup.holape.multitenancy.TenantContext;
import com.digitalgroup.holape.security.CustomUserDetailsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);

            // V06: un token revocado en logout deja de ser valido aunque no haya expirado.
            if (StringUtils.hasText(jwt) && tokenProvider.validateToken(jwt)
                    && !tokenBlacklistService.isRevoked(jwt)) {
                String username = tokenProvider.getUsernameFromToken(jwt);
                Long clientId = tokenProvider.getClientIdFromToken(jwt);

                // Load user with clientId override if present in token
                // PARIDAD: Rails set_current_client - allows Super Admin to switch clients
                UserDetails userDetails;
                if (clientId != null) {
                    userDetails = userDetailsService.loadUserByUsernameWithClientId(username, clientId);
                } else {
                    userDetails = userDetailsService.loadUserByUsername(username);
                }

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);

                // Set tenant context for multi-tenancy
                if (clientId != null) {
                    TenantContext.setCurrentTenant(clientId);
                }
            } else if (StringUtils.hasText(jwt)) {
                // Diagnostico: hay token pero fue rechazado. Log con contexto (quien / donde /
                // por que) para rastrear apps que pollean con token vencido o revocado
                // (p.ej. un envio masivo que no arranca porque next-recipient se rechaza).
                String reason = tokenBlacklistService.isRevoked(jwt)
                        ? "revoked"
                        : tokenProvider.invalidReason(jwt);
                log.warn("[AUTH] token rechazado reason={} user={} ip={} path={}",
                        reason, tokenProvider.getSubjectQuiet(jwt), clientIp(request), request.getRequestURI());
            }
        } catch (Exception ex) {
            log.error("Could not set user authentication in security context", ex);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    /** IP real del cliente (X-Real-IP puesto por nginx; no falsificable). */
    private String clientIp(HttpServletRequest request) {
        String xRealIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(xRealIp)) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/v1/app_login") ||
               path.startsWith("/whatsapp_webhook") ||
               path.startsWith("/swagger-ui") ||
               path.startsWith("/api-docs") ||
               path.startsWith("/health");
    }
}
