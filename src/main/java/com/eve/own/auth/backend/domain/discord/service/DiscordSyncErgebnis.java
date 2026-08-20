package com.eve.own.auth.backend.domain.discord.service;

import java.util.List;

/**
 * Was ein von Hand angestossener Abgleich fuer einen Charakter bewirkt hat.
 *
 * <p>Der Abgleich lief bisher stumm: Wer ihn ausloeste, erfuhr nicht, ob eine
 * Rolle gesetzt wurde, ob Discord sie verweigert hat oder ob ueberhaupt etwas zu
 * tun war. Uebrig blieb dieselbe Handbewegung wie vorher - in Discord
 * nachsehen.</p>
 *
 * <p>{@link #ausgefuehrt} und {@link #rollen} sind getrennt, weil "es ist nichts
 * passiert" zwei sehr verschiedene Dinge heissen kann: Der Abgleich lief und
 * hatte nichts zu tun, oder er lief gar nicht erst - kein Konto verknuepft, das
 * Mitglied nicht mehr auf dem Server. Im zweiten Fall steht der Grund in
 * {@link #hinweis}; ihn mit einer leeren Liste gleichzusetzen, hiesse den Nutzer
 * vor einem stummen Knopf sitzen zu lassen.</p>
 *
 * @param ausgefuehrt ob der Abgleich ueberhaupt hinausging
 * @param hinweis     warum nicht, oder was sonst zu wissen ist
 * @param rollen      je angefasster Rolle das Ergebnis
 */
public record DiscordSyncErgebnis(
        Long characterId,
        String characterName,
        Long mainCharacterId,
        String mainCharacterName,
        String discordUserId,
        boolean ausgefuehrt,
        String hinweis,
        List<Zeile> rollen) {

    /**
     * Eine Rolle und was mit ihr geschah.
     *
     * @param authRolle die Auth-Rolle, aus der die Discord-Rolle stammt -
     *                  {@code null} bei einer verwalteten Rolle, die dieser
     *                  Charakter nicht haben soll. Ohne sie stuende in der
     *                  Rueckmeldung nur eine achtzehnstellige Zahl.
     * @param geaendert ob dafuer etwas an Discord geschickt wurde. Der
     *                  Abgleich liest inzwischen zuerst und schreibt nur die
     *                  Differenz; ohne dieses Feld saehe "steht schon richtig"
     *                  aus wie "wurde gerade gesetzt".
     */
    public record Zeile(String authRolle,
                        String discordRoleId,
                        DiscordRollenErgebnis.Aktion aktion,
                        boolean erfolg,
                        boolean geaendert,
                        String grund) {
    }

    /** Ob jede angefasste Rolle durchging. */
    public boolean vollstaendig() {
        return ausgefuehrt && rollen.stream().allMatch(Zeile::erfolg);
    }
}
