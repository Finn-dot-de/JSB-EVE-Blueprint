package com.eve.own.auth.backend.domain.discord.service;

import java.util.List;
import java.util.Map;

/**
 * Was der Abgleich fuer einen Charakter tun wuerde - dieselbe Rechnung, die auch
 * die Pruefung anstellt.
 *
 * <p>Der Plan kommt aus {@link DiscordRoleAuditService#planFuer(Long)} und nicht
 * aus dem Abgleich selbst. Der Grund ist der Zweck der ganzen Pruefung: Wenn die
 * Uebersicht "Cap Azubi fehlt" sagt und der Anstoss danach etwas anderes setzt,
 * hat man zwei Wahrheiten und keine Moeglichkeit zu erkennen, welche gilt. Also
 * rechnet nur eine Stelle, und beide lesen von ihr.</p>
 *
 * @param discordUserId           das Konto - {@code null}, wenn keines verknuepft ist
 * @param nickname                der Name des Mains, wie ihn der Zeitplan setzt
 * @param verwalteteRollen        alle zugeordneten Discord-Rollen; nur diese
 *                                werden ueberhaupt angefasst
 * @param sollRollen              die Teilmenge davon, die das Konto tragen soll
 * @param authRolleJeDiscordRolle Rueckweg fuer die Anzeige: zu einer Rollen-Id
 *                                die Auth-Rolle, die sie ausgeloest hat
 */
public record DiscordRollenplan(
        Long characterId,
        String characterName,
        Long mainCharacterId,
        String mainCharacterName,
        String discordUserId,
        String nickname,
        List<String> verwalteteRollen,
        List<String> sollRollen,
        Map<String, String> authRolleJeDiscordRolle) {
}
