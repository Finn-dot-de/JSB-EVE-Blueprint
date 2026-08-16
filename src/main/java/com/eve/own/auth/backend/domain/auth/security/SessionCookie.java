package com.eve.own.auth.backend.domain.auth.security;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.springframework.http.ResponseCookie;

/**
 * Das Cookie, in dem die Sitzung steckt.
 *
 * <p>Name, Pfad und Schutzattribute stehen hier an einer Stelle. Zuvor waren sie
 * an vier Stellen ausgeschrieben - beim Setzen, beim Loeschen an zwei
 * verschiedenen Endpunkten und beim Auslesen im Filter -, und schon ein
 * abweichender Pfad haette das Abmelden wirkungslos gemacht.</p>
 */
public final class SessionCookie {

    /** Cookie-Name. Bewusst nichtssagend, damit er nicht zum Angriffsziel einlaedt. */
    public static final String NAME = "toky";

    private static final String PATH = "/";

    /** Gleiche Spanne wie die Gueltigkeit des enthaltenen Tokens. */
    private static final Duration MAX_AGE = Duration.ofDays(1);

    private SessionCookie() {
        throw new AssertionError("Utility-Klasse, nicht instanziierbar.");
    }

    /**
     * Das Cookie fuer eine frische Sitzung.
     *
     * <p>{@code httpOnly} haelt das Token aus JavaScript heraus, {@code SameSite=Lax}
     * laesst es die Rueckleitung von EVE ueberstehen und blockt zugleich
     * Fremdseiten-Aufrufe.</p>
     */
    public static ResponseCookie create(String token) {
        return ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .secure(false)
                .path(PATH)
                .maxAge(MAX_AGE)
                .sameSite("Lax")
                .build();
    }

    /** Ein Cookie, das den Browser zum sofortigen Verwerfen der Sitzung bewegt. */
    public static Cookie expired() {
        Cookie cookie = new Cookie(NAME, null);
        cookie.setPath(PATH);
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        return cookie;
    }
}
