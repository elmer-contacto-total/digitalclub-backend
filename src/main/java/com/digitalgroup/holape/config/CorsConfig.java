package com.digitalgroup.holape.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    @Value("${cors.allowed-origins}")
    private String allowedOriginsEnv;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(allowedOriginsEnv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        // Validación de seguridad al startup. Combinada con allowCredentials=true,
        // un wildcard sería catastrófico: cualquier sitio podría hacer requests
        // autenticadas contra este backend. Si esto falla, el servicio no arranca.
        if (origins.isEmpty()) {
            throw new IllegalStateException(
                    "cors.allowed-origins must define at least one origin");
        }
        for (String origin : origins) {
            if (origin.contains("*")) {
                throw new IllegalStateException(
                        "Wildcard origins are forbidden because allowCredentials=true. " +
                        "Found: " + origin);
            }
            if (!origin.startsWith("https://") && !origin.startsWith("http://localhost")) {
                throw new IllegalStateException(
                        "Origins must use https:// (or http://localhost for dev). " +
                        "Found: " + origin);
            }
        }

        CorsConfiguration configuration = new CorsConfiguration();
        // setAllowedOrigins (no setAllowedOriginPatterns): rechaza wildcards a nivel framework.
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList(
                "Authorization",
                "Content-Type",
                "X-Requested-With",
                "Accept",
                "Origin",
                "Access-Control-Request-Method",
                "Access-Control-Request-Headers",
                "X-Client-Id"
        ));
        configuration.setExposedHeaders(Arrays.asList(
                "Access-Control-Allow-Origin",
                "Access-Control-Allow-Credentials",
                "Authorization"
        ));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
