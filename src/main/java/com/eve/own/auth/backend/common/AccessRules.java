package com.eve.own.auth.backend.common;

/**
 * Die Zugriffsregeln der Endpunkte, benannt nach ihrer Absicht.
 *
 * <p>Zuvor stand jede Regel als Zeichenkette an der Methode - neun verschiedene
 * Schreibweisen fuer eine Handvoll tatsaechlich unterschiedlicher Berechtigungen,
 * teils mit {@code hasAnyRole}, teils mit {@code hasAnyAuthority}. Wer wissen
 * wollte, welche Endpunkte ein Director sieht, musste sie alle durchsuchen.</p>
 *
 * <p>Die Ausdruecke sind inhaltlich unveraendert uebernommen. {@code hasAnyRole}
 * und {@code hasAnyAuthority} verhalten sich hier identisch, weil die Rollennamen
 * das Praefix {@code ROLE_} bereits tragen; einheitlich steht deshalb ueberall
 * {@code hasAnyRole}.</p>
 *
 * <p>Konstanten, weil Annotationen nur Konstantenausdruecke aufnehmen.</p>
 */
public final class AccessRules {

    /** Fuehrung der Corporation. */
    public static final String LEADERSHIP = "hasAnyRole('ROLE_DIRECTOR', 'ROLE_CEO')";

    /** Fuehrung plus technische Administration. */
    public static final String LEADERSHIP_OR_IT =
            "hasAnyRole('ROLE_DIRECTOR', 'ROLE_CEO', 'ROLE_IT_ADMIN')";

    /** Nur die oberste Ebene - fuer Eingriffe, die den ganzen Verband betreffen. */
    public static final String COMMAND = "hasAnyRole('ROLE_CEO', 'ROLE_IT_ADMIN')";

    /** Flottenfuehrung: Director und die per Titel vergebenen FC-Rollen. */
    public static final String FLEET_STAFF = "hasAnyRole('ROLE_DIRECTOR', 'ROLE_1337', 'ROLE_A38')";

    /** Flottenfuehrung plus technische Administration. */
    public static final String FLEET_STAFF_OR_IT =
            "hasAnyRole('ROLE_DIRECTOR', 'ROLE_1337', 'ROLE_A38', 'ROLE_IT_ADMIN')";

    /** Flottenfuehrung samt Corp-Fuehrung. */
    public static final String FLEET_STAFF_OR_LEADERSHIP =
            "hasAnyRole('ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_IT_ADMIN', 'ROLE_A38')";

    /** Der weiteste Kreis mit Einblick in Flottendaten. */
    public static final String FLEET_VIEWERS =
            "hasAnyRole('ROLE_69', 'ROLE_1337', 'ROLE_A38', 'ROLE_DIRECTOR', 'ROLE_CEO', 'ROLE_IT_ADMIN')";

    private AccessRules() {
        throw new AssertionError("Konstantenhalter, nicht instanziierbar.");
    }
}
