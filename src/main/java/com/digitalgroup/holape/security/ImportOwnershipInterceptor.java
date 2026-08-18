package com.digitalgroup.holape.security;

import com.digitalgroup.holape.domain.importdata.service.ImportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Impone la propiedad de la importacion en TODA ruta /app/imports/{id}...
 *
 * MOTIVO (hallazgo V02 - Referencia directa insegura a objetos, CVSS 8.6):
 * ImportAdminController tiene 16 endpoints que reciben {id} y ninguno validaba
 * pertenencia: bastaba enumerar el numero de la URL para leer, DESCARGAR el CSV
 * completo (datos personales y de deuda) o ELIMINAR importaciones ajenas, incluso
 * de otro cliente. El DELETE ni siquiera tenia @PreAuthorize.
 *
 * Se resuelve en un interceptor y no endpoint por endpoint a proposito: cubre los
 * 16 existentes y tambien los que se agreguen despues, sin depender de que alguien
 * recuerde repetir el control.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportOwnershipInterceptor implements HandlerInterceptor {

    /** /app/imports/{id} y cualquier subruta. Excluye /app/imports/mapping_templates/... */
    private static final Pattern IMPORT_ID_PATH =
            Pattern.compile("^/app/imports/([0-9]+)(/.*)?$");

    private final ImportService importService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Matcher m = IMPORT_ID_PATH.matcher(request.getRequestURI());
        if (!m.matches()) {
            return true; // no es una ruta con id de importacion
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails actor)) {
            throw new AccessDeniedException("No autenticado");
        }

        Long importId = Long.parseLong(m.group(1));

        // Lanza AccessDeniedException si no pertenece al ambito del usuario.
        // GlobalExceptionHandler la mapea a 403.
        importService.findByIdForUser(importId, actor);

        return true;
    }
}
