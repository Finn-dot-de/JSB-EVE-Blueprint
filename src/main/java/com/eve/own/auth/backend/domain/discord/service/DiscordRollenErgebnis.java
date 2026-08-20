package com.eve.own.auth.backend.domain.discord.service;

/**
 * Was der Abgleich mit <b>einer</b> Rolle gemacht hat.
 *
 * <p>Der Abgleich schrieb bisher nur ins Log: ein 403 je Rolle wurde als WARN
 * vermerkt und war danach weg. Wer den Abgleich von Hand anstoesst, steht damit
 * vor derselben Frage wie vorher - "hat es gewirkt?" - und beantwortet sie in
 * Discord von Hand. Deshalb gibt {@link DiscordBotService#syncManagedRoles} sein
 * Ergebnis jetzt zurueck, statt es nur zu protokollieren.</p>
 *
 * @param discordRoleId die betrachtete Rolle
 * @param aktion        was zu tun war
 * @param erfolg        ob die Rolle am Ende richtig steht
 * @param geaendert     ob dafuer ein Schreibzugriff noetig war. Getrennt von
 *                      {@link #erfolg}, seit der Abgleich erst liest und dann
 *                      nur die Differenz schreibt: Im Normalfall steht alles
 *                      richtig, und "erfolgreich" hiesse sonst "gerade
 *                      gesetzt" - eine Meldung ueber einen Aufruf, den es nie
 *                      gab.
 * @param grund         warum nicht, falls nicht - {@code null} bei Erfolg
 */
public record DiscordRollenErgebnis(
        String discordRoleId,
        Aktion aktion,
        boolean erfolg,
        boolean geaendert,
        String grund) {

    /**
     * Was mit der Rolle geschehen sollte.
     *
     * <p>Entzogen wird nur, was das Auth selbst verwaltet. Eine handvergebene
     * Rolle ohne Zuordnung kommt hier nie vor - sie wird gar nicht erst
     * angefasst.</p>
     */
    public enum Aktion {
        VERGEBEN,
        ENTZOGEN
    }

    /** Discord hat den Schreibzugriff angenommen. */
    public static DiscordRollenErgebnis gelungen(String discordRoleId, Aktion aktion) {
        return new DiscordRollenErgebnis(discordRoleId, aktion, true, true, null);
    }

    /**
     * Die Rolle stand schon richtig - es ging kein Aufruf hinaus.
     *
     * <p>Steht trotzdem im Ergebnis, weil der Knopf sonst eine unvollstaendige
     * Liste zeigte: Wer nur die geaenderten Rollen sieht, weiss nicht, ob die
     * uebrigen geprueft wurden oder ob der Abgleich sie uebersehen hat.</p>
     */
    public static DiscordRollenErgebnis unveraendert(String discordRoleId, Aktion aktion) {
        return new DiscordRollenErgebnis(discordRoleId, aktion, true, false, null);
    }

    public static DiscordRollenErgebnis gescheitert(String discordRoleId, Aktion aktion, String grund) {
        return new DiscordRollenErgebnis(discordRoleId, aktion, false, false, grund);
    }
}
