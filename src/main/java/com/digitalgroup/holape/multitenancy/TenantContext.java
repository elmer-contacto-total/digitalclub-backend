package com.digitalgroup.holape.multitenancy;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TenantContext {

    private static final ThreadLocal<Long> currentTenant = new ThreadLocal<>();

    private TenantContext() {}

    public static void setCurrentTenant(Long clientId) {
        log.debug("Setting tenant context to client: {}", clientId);
        currentTenant.set(clientId);
    }

    public static Long getCurrentTenant() {
        return currentTenant.get();
    }

    public static void clear() {
        currentTenant.remove();
    }

    public static boolean hasTenant() {
        return currentTenant.get() != null;
    }
}
