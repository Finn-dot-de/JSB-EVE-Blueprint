package com.eve.own.auth.backend.domain.discord.service;

import java.util.List;

/**
 * Dieselbe Pruefung, gelesen aus der Sicht <b>eines Charakters</b>.
 *
 * <p>Gerechnet wird je Discord-Konto - das muss so bleiben, sonst faellt der Fall
 * "zwei Charaktere, ein Konto" wieder auseinander. Gedacht wird aber in
 * Charakteren: die Frage lautet "was hat Tom, und was fehlt ihm", nicht "was
 * traegt Konto 1424800550347735184". Diese Sicht legt beides uebereinander, ohne
 * ein zweites Mal zu vergleichen: Sie uebernimmt {@link #rollen} und
 * {@link #weitereDiscordRollen} unveraendert aus dem Kontobefund. Zwei Stellen,
 * die dasselbe vergleichen, laufen frueher oder spaeter auseinander - und dann
 * glaubt man der falschen.</p>
 *
 * <p>Dass {@link #mainCharacterName} mitgeliefert wird, ist kein Beiwerk: Das
 * Soll haengt am Main. Steht in Toms Zeile eine Rolle, die Tom im Auth gar nicht
 * hat, dann kommt sie von seinem Main - ohne dessen Namen daneben ist die Zeile
 * nicht zu verstehen.</p>
 *
 * @param characterId          der Charakter, aus dessen Sicht gelesen wird
 * @param characterName        sein Name
 * @param mainCharacterId      der Charakter, an dem das Soll haengt
 * @param mainCharacterName    dessen Name
 * @param discordUserId        das Konto - {@code null}, wenn keines verknuepft ist
 * @param verknuepft           ob ueberhaupt ein Discord-Konto dahintersteht
 * @param pruefbar             ob der Ist-Zustand gelesen werden konnte
 * @param hinweis              warum nicht, falls nicht
 * @param rollen               die Gegenueberstellung Zeile fuer Zeile
 * @param weitereDiscordRollen was das Konto sonst noch traegt
 * @param sollUneinig          ob mehrere Charaktere an diesem Konto
 *                             unterschiedliche Soll-Rollen fordern
 */
public record DiscordCharacterAudit(
        Long characterId,
        String characterName,
        Long mainCharacterId,
        String mainCharacterName,
        String discordUserId,
        boolean verknuepft,
        boolean pruefbar,
        String hinweis,
        List<DiscordRollenBefund> rollen,
        List<DiscordRoleAudit.VorhandeneRolle> weitereDiscordRollen,
        boolean sollUneinig) {

    /**
     * Ob an diesem Charakter etwas zu tun ist.
     *
     * <p>Zaehlt nur Zeilen, die {@link DiscordRollenBefund.Zustand#FEHLT} sagen,
     * und die verwalteten unter den zusaetzlichen Rollen. Weder "nicht
     * feststellbar" noch eine handvergebene Rolle sind ein Befund - beides
     * ergaebe eine Meldung, auf die niemand etwas tun kann.</p>
     */
    public boolean hatBefund() {
        boolean fehlt = rollen.stream()
                .anyMatch(zeile -> zeile.zustand() == DiscordRollenBefund.Zustand.FEHLT);
        boolean ueberzaehlig = weitereDiscordRollen.stream()
                .anyMatch(DiscordRoleAudit.VorhandeneRolle::verwaltet);
        return fehlt || ueberzaehlig || sollUneinig;
    }
}
