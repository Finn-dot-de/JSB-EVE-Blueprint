package com.eve.own.auth.backend.domain.auth;

import java.util.Locale;

/**
 * Die im Code fest verdrahteten Rollennamen.
 *
 * <p>Rollen entstehen an zwei Stellen: die hier aufgefuehrten vergibt die
 * Anwendung selbst anhand der Corp-Zugehoerigkeit, alle uebrigen leitet
 * {@link com.eve.own.auth.backend.domain.auth.service.CharacterRoleService}
 * aus den Ingame-Titeln ab und legt sie in {@code title_role_mappings} ab.</p>
 *
 * <p>Als Konstanten, weil ein Tippfehler in einem Rollen-Literal nicht auffaellt:
 * die Pruefung schlaegt dann einfach still fehl und der Zugriff wird verweigert
 * oder - schlimmer - gewaehrt.</p>
 */
public final class SystemRoles {

    private static final String PREFIX = "ROLE_";

    /** Grundrolle jedes angemeldeten Mitglieds einer zugelassenen Corporation. */
    public static final String USER = PREFIX + "USER";

    /** Mitglied einer zugelassenen Corporation (Haupt- oder Alt-Corp). */
    public static final String MEMBER = PREFIX + "MEMBER";

    /**
     * Mitglied der Haupt-Corporation.
     *
     * <p>Login und Hintergrund-Sync vergaben hier bis zur Vereinheitlichung zwei
     * verschiedene Namen ({@code ROLE_MARAUDERS} bzw. {@code ROLE_MARAUDERS_ASSOCIATED}).
     * Uebernommen ist die Variante des Syncs, weil dieser alle zehn Minuten laeuft
     * und die Login-Variante ohnehin immer sofort ueberschrieben hat.</p>
     */
    public static final String MARAUDERS = PREFIX + "MARAUDERS_ASSOCIATED";

    /** Angemeldet, aber in keiner zugelassenen Corporation. */
    public static final String GUEST = PREFIX + "GUEST";

    public static final String CEO = PREFIX + "CEO";
    public static final String DIRECTOR = PREFIX + "DIRECTOR";
    public static final String IT_ADMIN = PREFIX + "IT_ADMIN";

    private SystemRoles() {
        throw new AssertionError("Konstantenhalter, nicht instanziierbar.");
    }

    /**
     * Wandelt einen Ingame-Titel in einen Rollennamen um, z.B. "Fleet Commander"
     * zu {@code ROLE_FLEET_COMMANDER}.
     */
    public static String fromTitle(String titleName) {
        String normalized = titleName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        return PREFIX + normalized;
    }
}
