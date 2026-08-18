package com.digitalgroup.holape.config;

import com.digitalgroup.holape.security.ImportOwnershipInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registra los interceptores de autorizacion a nivel de recurso (V02).
 */
@Configuration
@RequiredArgsConstructor
public class WebSecurityInterceptorConfig implements WebMvcConfigurer {

    private final ImportOwnershipInterceptor importOwnershipInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(importOwnershipInterceptor)
                .addPathPatterns("/app/imports/**");
    }
}
