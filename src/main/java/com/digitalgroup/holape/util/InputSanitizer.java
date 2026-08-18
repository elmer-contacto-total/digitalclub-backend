package com.digitalgroup.holape.util;

/**
 * Sanitizacion de entrada para campos de texto libre (V05).
 *
 * MOTIVO (hallazgo V05 - Inadecuada validacion de entrada de datos):
 * Los campos de nombre y apellido admitian etiquetas HTML y payloads de XSS
 * almacenado (p.ej. {@code <img src=x onerror=alert(...)>}).
 *
 * Neutraliza el vector sin romper nombres legitimos: elimina etiquetas HTML,
 * los caracteres de angulo y los caracteres de control, pero conserva letras
 * acentuadas, ñ, espacios, guiones, apostrofes y puntos (O'Brien, Maria Jose,
 * Garcia-Lopez).
 */
public final class InputSanitizer {

    private InputSanitizer() {}

    /**
     * Lista blanca estricta para nombres ingresados por el usuario (perfil, alta y
     * edicion). Conserva solo letras (con tildes/ñ), espacios, guiones, apostrofes
     * y puntos; elimina cualquier otro caracter. Es la contraparte servidor de la
     * validacion del formulario (defensa en profundidad: aunque se evada el cliente,
     * lo almacenado queda limpio).
     */
    public static String sanitizeStrictName(String value) {
        if (value == null) return null;
        String cleaned = sanitizeName(value)
                .replaceAll("[^\\p{L}\\p{M} .'\\-]", "")
                .trim();
        return cleaned;
    }

    /** Elimina etiquetas <...>, caracteres de angulo sueltos y caracteres de control. */
    public static String sanitizeName(String value) {
        if (value == null) return null;
        String cleaned = value
                // etiquetas HTML completas
                .replaceAll("<[^>]*>", "")
                // caracteres de angulo sueltos que quedaran
                .replace("<", "")
                .replace(">", "")
                // caracteres de control (incluye \0, \n, \r, \t de control)
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        return cleaned;
    }
}
