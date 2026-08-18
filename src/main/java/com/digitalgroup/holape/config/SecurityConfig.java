package com.digitalgroup.holape.config;

import com.digitalgroup.holape.security.CustomUserDetailsService;
import com.digitalgroup.holape.security.jwt.JwtAuthenticationEntryPoint;
import com.digitalgroup.holape.security.jwt.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final JwtAuthenticationEntryPoint unauthorizedHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                // V07: cabeceras de seguridad. Se definen en la app (no solo en Nginx)
                // para que apliquen en todos los despliegues desde un unico lugar.
                // X-Content-Type-Options y X-Frame-Options: Nginx ya los agrega en el
                // borde; aqui se fija frameOptions=sameOrigin para no entrar en conflicto
                // con el DENY por defecto de Spring Security.
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                        // Sin includeSubDomains: infinance corre bajo innovag.com.pe
                        // (dominio de un tercero); extender HSTS a *.innovag.com.pe podria
                        // romper otros subdominios que no sirvan HTTPS. Se limita al host.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(false)
                                .maxAgeInSeconds(31536000))
                        .referrerPolicy(ref -> ref.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        // Permissions-Policy y Cross-Origin-Opener-Policy: en Spring
                        // Security 6.2 no existen como DSL (llegaron en 6.3). Se emiten
                        // con StaticHeadersWriter, estable en cualquier version.
                        .addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter(
                                "Permissions-Policy",
                                "geolocation=(), microphone=(), camera=(), payment=(), usb=()"))
                        .addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter(
                                "Cross-Origin-Opener-Policy", "same-origin-allow-popups"))
                        // CSP: 'self' + imagenes de S3 y conexiones al backend/websocket.
                        // WhatsApp Web corre en una BrowserView aparte (otro origen), no
                        // se ve afectada. Validar en staging antes del rollout a infinance.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; "
                                + "script-src 'self'; "
                                + "style-src 'self' 'unsafe-inline'; "
                                + "img-src 'self' data: blob: https://*.amazonaws.com; "
                                + "font-src 'self' data:; "
                                + "connect-src 'self' https: wss:; "
                                + "frame-ancestors 'self'; "
                                + "base-uri 'self'; "
                                + "form-action 'self'; "
                                + "object-src 'none'")))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(unauthorizedHandler))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints - Auth
                        .requestMatchers("/api/v1/app_login").permitAll()
                        .requestMatchers("/api/v1/web/prelogin").permitAll()
                        .requestMatchers("/api/v1/web/verify_otp").permitAll()
                        .requestMatchers("/api/v1/web/resend_otp").permitAll()
                        .requestMatchers("/api/v1/auth/refresh").permitAll()
                        .requestMatchers("/api/v1/password/forgot").permitAll()
                        .requestMatchers("/api/v1/password/reset").permitAll()
                        // /api/v1/password/change requires authentication (handled by /api/** rule)
                        // Public endpoints - Webhooks & Health
                        .requestMatchers("/whatsapp_webhook").permitAll()
                        .requestMatchers("/health").permitAll()
                        .requestMatchers("/swagger-ui/**", "/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/ws/**", "/websocket/**").permitAll()
                        // Media capture from Electron — requires JWT authentication
                        .requestMatchers("/api/v1/media/**").authenticated()
                        // Public endpoints - App version check (for Electron auto-update)
                        .requestMatchers("/api/v1/app/**").permitAll()
                        // Admin endpoints - require authentication
                        .requestMatchers("/app/**").authenticated()
                        // API endpoints - require authentication
                        .requestMatchers("/api/**").authenticated()
                        // All other requests
                        .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt with strength 12 (same as Rails Devise default)
        return new BCryptPasswordEncoder(12);
    }
}
