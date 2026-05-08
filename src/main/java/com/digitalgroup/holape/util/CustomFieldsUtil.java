package com.digitalgroup.holape.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Convención del sistema: keys de custom_fields y de column_mapping en lowercase.
 *
 * El wizard de Import auto-sugiere mappings preservando el case del header CSV
 * (ej. "custom_field:DIST_DOM"), pero la data legacy de Rails almacena keys
 * lowercase ("dist_dom"). Esta clase centraliza la norma "siempre lowercase al
 * escribir; tolerante al case al leer" para evitar inconsistencia entre callers.
 */
public final class CustomFieldsUtil {

    private CustomFieldsUtil() {}

    /** Lowercase + trim. Devuelve null si la entrada es null. */
    public static String normalizeKey(String key) {
        return key == null ? null : key.trim().toLowerCase();
    }

    /**
     * Lookup tolerante: get exacto primero (rápido), fallback case-insensitive
     * sobre el Map si no hubo match. Devuelve null si la entrada es null o no se
     * encuentra la key.
     */
    public static Object getCaseInsensitive(Map<String, Object> map, String key) {
        if (map == null || key == null) return null;
        Object val = map.get(key);
        if (val != null) return val;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (key.equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return null;
    }

    /**
     * Variante de {@link #getCaseInsensitive(Map, String)} que también verifica
     * existencia de la key (útil cuando el value puede ser null intencionalmente).
     */
    public static boolean containsKeyCaseInsensitive(Map<String, Object> map, String key) {
        if (map == null || key == null) return false;
        if (map.containsKey(key)) return true;
        for (String k : map.keySet()) {
            if (key.equalsIgnoreCase(k)) return true;
        }
        return false;
    }

    /**
     * Para column_mapping: si el valor empieza con "custom_field:", lowercase
     * la parte después. Otros prefijos (crm_, +cf, unmatched_) y valores planos
     * se dejan tal cual porque no los usamos como key directa de custom_fields.
     */
    public static String normalizeMappingValue(String value) {
        if (value == null) return null;
        if (value.startsWith("custom_field:")) {
            return "custom_field:" + value.substring("custom_field:".length()).trim().toLowerCase();
        }
        return value;
    }
}
