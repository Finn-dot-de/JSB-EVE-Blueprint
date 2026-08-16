package com.eve.own.auth.backend.domain.auth;

import java.util.List;
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

    /** Alles, was die Anwendung selbst vergibt - im Unterschied zu frei angelegten Rollen. */
    private static final List<String> BUILT_IN =
            List.of(USER, MEMBER, MARAUDERS, GUEST, CEO, DIRECTOR, IT_ADMIN);

    /** Nur Grossbuchstaben, Ziffern und Unterstriche ueberleben in einem Rollennamen. */
    private static final String NON_ROLE_CHARACTERS = "[^A-Z0-9]+";

    /** Trennzeichen am Rand, die beim Ersetzen entstehen ("Recruiter (Trial)"). */
    private static final String LEADING_OR_TRAILING_SEPARATORS = "^_+|_+$";

    private SystemRoles() {
        throw new AssertionError("Konstantenhalter, nicht instanziierbar.");
    }

    /** Die von der Anwendung selbst vergebenen Rollen. */
    public static List<String> builtIn() {
        return BUILT_IN;
    }

    /** Ob dieser Name zu einer eingebauten Rolle gehoert. */
    public static boolean isBuiltIn(String roleName) {
        return BUILT_IN.contains(roleName);
    }

    /**
     * Wandelt einen Ingame-Titel in einen Rollennamen um, z.B. "Fleet Commander"
     * zu {@code ROLE_FLEET_COMMANDER}.
     */
    public static String fromTitle(String titleName) {
        return normalize(titleName);
    }

    /**
     * Bringt eine frei eingegebene Bezeichnung auf die Form eines Rollennamens.
     *
     * <p>Rollennamen werden an vielen Stellen als Zeichenkette verglichen - in
     * {@link com.eve.own.auth.backend.common.AccessRules}, in den Discord-Zuordnungen,
     * in {@code character_roles}. Ein Name, der sich nur in der Schreibweise
     * unterscheidet, ist deshalb eine andere Rolle und greift schlicht nie. Wer
     * "fleet commander" eintippt, meint aber {@code ROLE_FLEET_COMMANDER}.</p>
     *
     * <p>Ein bereits vorhandenes {@code ROLE_} wird nicht verdoppelt.</p>
     *
     * @throws IllegalArgumentException wenn nach dem Saeubern kein Name uebrig bleibt
     */
    public static String normalize(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("Ein Rollenname darf nicht leer sein.");
        }

        String upperCase = rawName.trim().toUpperCase(Locale.ROOT);
        String withoutPrefix =
                upperCase.startsWith(PREFIX) ? upperCase.substring(PREFIX.length()) : upperCase;
        String cleaned = withoutPrefix.replaceAll(NON_ROLE_CHARACTERS, "_")
                .replaceAll(LEADING_OR_TRAILING_SEPARATORS, "");

        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException(
                    "\"" + rawName + "\" ergibt keinen verwendbaren Rollennamen.");
        }
        return PREFIX + cleaned;
    }
}
