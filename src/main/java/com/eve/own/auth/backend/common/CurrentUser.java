package com.eve.own.auth.backend.common;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Liest den angemeldeten Charakter aus dem Sicherheitskontext.
 *
 * <p>Das Principal ist die Charakter-ID des Main-Charakters - so setzt es der
 * {@link com.eve.own.auth.backend.config.JwtAuthenticationFilter} beim
 * Auswerten des Cookies.</p>
 *
 * <p>Zuvor stand in jedem Controller dieselbe Zeile inklusive Cast und einem
 * {@code assert}, das zur Laufzeit ohnehin nie greift: Assertions sind in der
 * JVM standardmaessig abgeschaltet. Ein fehlendes Principal blieb damit
 * unbemerkt und flog erst spaeter als NullPointerException auf.</p>
 */
public final class CurrentUser {

    private static final String ANONYMOUS_PRINCIPAL = "anonymousUser";

    private CurrentUser() {
        throw new AssertionError("Utility-Klasse, nicht instanziierbar.");
    }

    /**
     * Die ID des angemeldeten Charakters.
     *
     * @throws IllegalStateException wenn kein Charakter angemeldet ist. Hinter
     *     einem {@code authenticated()}-Filter ist das ein Programmierfehler und
     *     kein Zustand, den ein Aufrufer sinnvoll behandeln koennte.
     */
    public static Long characterId() {
        return find().orElseThrow(
                () -> new IllegalStateException("Kein angemeldeter Charakter im Sicherheitskontext."));
    }

    /** Wie {@link #characterId()}, aber leer statt Ausnahme - fuer oeffentliche Endpunkte. */
    public static Optional<Long> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (ANONYMOUS_PRINCIPAL.equals(principal) || !(principal instanceof Long characterId)) {
            return Optional.empty();
        }
        return Optional.of(characterId);
    }
}
