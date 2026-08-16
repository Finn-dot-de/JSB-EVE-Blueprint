package com.eve.own.auth.backend.domain.auth;

/**
 * Woher eine Rolle stammt.
 *
 * <p>Die Herkunft entscheidet darueber, was sich an einer Rolle aendern laesst:
 * eingebaute Rollen sind Teil des Programms, aus Titeln abgeleitete haengen an
 * der Zuordnung des jeweiligen Ingame-Titels, und nur die selbst angelegten
 * lassen sich frei bearbeiten und wieder loeschen.</p>
 */
public enum AuthRoleSource {

    /** Von der Anwendung selbst vergeben, siehe {@link SystemRoles}. */
    BUILT_IN,

    /** Von Hand angelegt und in {@code system_roles} hinterlegt. */
    CUSTOM,

    /** Entsteht daraus, dass ein Ingame-Titel auf diesen Namen zeigt. */
    TITLE
}
