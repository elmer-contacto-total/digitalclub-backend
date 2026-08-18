package com.digitalgroup.holape.security;

import com.digitalgroup.holape.domain.common.enums.UserRole;
import com.digitalgroup.holape.domain.user.entity.User;
import com.digitalgroup.holape.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Politica central de autorizacion sobre usuarios.
 *
 * MOTIVO (hallazgo V01 - Inadecuado control de Acceso, CVSS 9.4):
 * Los endpoints de gestion de usuarios validaban unicamente "¿tu rol puede entrar
 * a esta funcion?" via @PreAuthorize, pero nunca "¿hasta donde puedes llegar dentro
 * de ella?". Un MANAGER_LEVEL_4 podia enviar PUT /app/users/{suPropioId} con
 * role=ADMIN y el backend lo persistia. Como el rol se relee de BD en cada request,
 * el efecto era inmediato y permitia despues usar /login_as para suplantar al
 * SUPER_ADMIN.
 *
 * Las reglas aqui NO son nuevas: son las que el frontend Angular ya aplicaba en
 * user-list.component.ts (canEditUser / availableRoles). El problema era que vivian
 * solo en el cliente. Esto las mueve al servidor, que es donde deben imponerse.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserAccessPolicy {

    private final UserRepository userRepository;

    /**
     * Verifica que el actor pueda administrar (editar / password / desactivar / avatar)
     * al usuario destino. Devuelve el usuario destino ya cargado.
     *
     * Mensaje generico a proposito: no confirmar si un id existe.
     */
    public User assertCanManage(CustomUserDetails actor, Long targetId) {
        if (actor == null) {
            throw new AccessDeniedException("No autenticado");
        }
        User target = userRepository.findById(targetId)
                .orElseThrow(() -> new AccessDeniedException("Usuario no encontrado"));

        // 1. Aislamiento multi-tenant: solo SUPER_ADMIN cruza clientes.
        if (!actor.isSuperAdmin()) {
            Long targetClientId = target.getClientId();
            if (targetClientId == null || !targetClientId.equals(actor.getClientId())) {
                log.warn("AUTHZ: usuario {} (cliente {}) intento administrar al usuario {} (cliente {})",
                        actor.getId(), actor.getClientId(), targetId, targetClientId);
                throw new AccessDeniedException("Usuario no encontrado");
            }
        }

        // 2. Nadie se auto-administra por esta via. Para la propia cuenta existe
        //    PUT /app/users/profile, que no acepta el campo role.
        if (actor.getId().equals(targetId)) {
            log.warn("AUTHZ: usuario {} intento editarse a si mismo via gestion de usuarios", actor.getId());
            throw new AccessDeniedException(
                    "Use su perfil para modificar su propia cuenta");
        }

        // 3. Solo sobre alguien de rango ESTRICTAMENTE menor.
        if (!actor.getUserRole().outranks(target.getRole())) {
            log.warn("AUTHZ: usuario {} ({}) intento administrar al usuario {} ({}) de rango igual o mayor",
                    actor.getId(), actor.getUserRole(), targetId, target.getRole());
            throw new AccessDeniedException(
                    "No puede administrar usuarios de igual o mayor privilegio");
        }

        return target;
    }

    /**
     * Verifica que el actor pueda ASIGNAR el rol indicado.
     * Nadie puede otorgar un rol igual o superior al suyo (evita la auto-promocion
     * y la promocion de terceros por encima del actor).
     */
    public void assertCanAssignRole(CustomUserDetails actor, UserRole newRole) {
        if (newRole == null) return;
        if (actor == null) {
            throw new AccessDeniedException("No autenticado");
        }
        if (!actor.getUserRole().outranks(newRole)) {
            log.warn("AUTHZ: usuario {} ({}) intento asignar el rol {}",
                    actor.getId(), actor.getUserRole(), newRole);
            throw new AccessDeniedException(
                    "No puede asignar un rol igual o superior al suyo");
        }
    }

    /**
     * Verifica que el manager que se pretende asignar pertenezca al mismo cliente.
     * Sin esto se podia enlazar un usuario a un manager de otro tenant.
     */
    public void assertValidManager(CustomUserDetails actor, Long managerId) {
        if (managerId == null) return;
        User manager = userRepository.findById(managerId)
                .orElseThrow(() -> new AccessDeniedException("Manager no encontrado"));
        if (!actor.isSuperAdmin()) {
            Long managerClientId = manager.getClientId();
            if (managerClientId == null || !managerClientId.equals(actor.getClientId())) {
                log.warn("AUTHZ: usuario {} intento asignar el manager {} de otro cliente",
                        actor.getId(), managerId);
                throw new AccessDeniedException("Manager no encontrado");
            }
        }
    }

    /**
     * Verifica que el actor pueda LEER la ficha del usuario destino.
     * Mas permisivo que assertCanManage: aqui si se admite verse a uno mismo y ver
     * a pares, pero nunca a usuarios de otro cliente.
     */
    public User assertCanView(CustomUserDetails actor, Long targetId) {
        if (actor == null) {
            throw new AccessDeniedException("No autenticado");
        }
        User target = userRepository.findById(targetId)
                .orElseThrow(() -> new AccessDeniedException("Usuario no encontrado"));

        if (actor.isSuperAdmin()) return target;

        Long targetClientId = target.getClientId();
        if (targetClientId == null || !targetClientId.equals(actor.getClientId())) {
            log.warn("AUTHZ: usuario {} (cliente {}) intento ver al usuario {} (cliente {})",
                    actor.getId(), actor.getClientId(), targetId, targetClientId);
            throw new AccessDeniedException("Usuario no encontrado");
        }
        return target;
    }
}
