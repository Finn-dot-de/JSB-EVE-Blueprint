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
 * @param discordRoleId die angefasste Rolle
 * @param aktion        was versucht wurde
 * @param erfolg        ob Discord es angenommen hat
 * @param grund         warum nicht, falls nicht - {@code null} bei Erfolg
 */
public record DiscordRollenErgebnis(
        String discordRoleId,
        Aktion aktion,
        boolean erfolg,
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

    public static DiscordRollenErgebnis gelungen(String discordRoleId, Aktion aktion) {
        return new DiscordRollenErgebnis(discordRoleId, aktion, true, null);
    }

    public static DiscordRollenErgebnis gescheitert(String discordRoleId, Aktion aktion, String grund) {
        return new DiscordRollenErgebnis(discordRoleId, aktion, false, grund);
    }
}
