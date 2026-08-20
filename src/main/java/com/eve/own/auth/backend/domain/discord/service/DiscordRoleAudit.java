package com.eve.own.auth.backend.domain.discord.service;

import java.util.List;

/**
 * Das Ergebnis der Rollenpruefung fuer <b>ein</b> Discord-Konto.
 *
 * <p>Bezugsgroesse ist das Konto, nicht der Charakter. In den echten Daten
 * zeigen zwei EVE-Charaktere auf dieselbe {@code discord_user_id}; ein Ergebnis
 * je Charakter haette dieselbe Person zweimal aufgefuehrt, mit zwei
 * widerspruechlichen Aussagen und ohne Hinweis darauf, dass es dieselbe ist.
 * Genau dieser Widerspruch ist der Befund - er gehoert in eine Zeile, nicht in
 * zwei.</p>
 *
 * <p>{@link #pruefbar} und die Rollenlisten sind bewusst getrennt. Verweigert
 * Discord die Auskunft, ist ueber die Rollen <em>nichts</em> bekannt - dann
 * bleiben beide Listen leer, statt die Soll-Rollen als "fehlend" auszuweisen.
 * Wer das vermischt, meldet ausgerechnet am Server-Owner den lautesten
 * Fehlalarm.</p>
 *
 * @param discordUserId       das gepruefte Konto
 * @param mainCharacterId     der Hauptcharakter, an dem das Soll haengt
 * @param mainCharacterName   sein Name - fuer die Anzeige, damit niemand
 *                            Kennungen nachschlagen muss
 * @param charaktere          alle mit diesem Konto verknuepften Charaktere
 *                            samt ihrem jeweiligen Soll
 * @param rollen              die Gegenueberstellung Zeile fuer Zeile: je
 *                            Auth-Rolle die zugeordnete Discord-Rolle, ob sie
 *                            sitzt, und wenn nicht, warum nicht
 * @param weitereDiscordRollen alles, was das Konto sonst noch traegt - auch das
 *                            von Hand Vergebene. Es steht hier, weil der Leser
 *                            wissen will, was da ist; ein Befund ist es nur,
 *                            wenn {@link VorhandeneRolle#verwaltet()} gilt.
 * @param fehlendeRollen      Soll ja, Ist nein - abgeleitet aus {@code rollen}
 * @param ueberzaehligeRollen Ist ja, Soll nein - <b>nur verwaltete Rollen</b>
 * @param pruefbar            ob der Ist-Zustand ueberhaupt gelesen werden konnte
 * @param hinweis             warum nicht, falls nicht
 * @param sollUneinig         ob die verknuepften Charaktere verschiedene
 *                            Soll-Rollen haben
 */
public record DiscordRoleAudit(
        String discordUserId,
        Long mainCharacterId,
        String mainCharacterName,
        List<CharakterSoll> charaktere,
        List<DiscordRollenBefund> rollen,
        List<VorhandeneRolle> weitereDiscordRollen,
        List<String> fehlendeRollen,
        List<String> ueberzaehligeRollen,
        boolean pruefbar,
        String hinweis,
        boolean sollUneinig) {

    /** Ein verknuepfter Charakter und die Discord-Rollen, die er ergaebe. */
    public record CharakterSoll(Long characterId, String name, List<String> sollRollen) {}

    /**
     * Eine Rolle, die das Konto traegt, ohne dass eine Auth-Rolle sie fordert.
     *
     * <p>Sie hier vollstaendig auszuweisen ist Absicht: Wer die Uebersicht liest,
     * will sehen, was das Konto <em>hat</em>, nicht nur, was ihm fehlt. Die
     * Standardrolle des Servers und jede Farb- oder Pingrolle gehoeren dazu.</p>
     *
     * <p>{@link #verwaltet} trennt die beiden Faelle, die man um keinen Preis
     * verwechseln darf. Ist sie {@code false}, hat das Auth diese Rolle nie
     * vergeben und weiss ueber sie nichts - dann ist sie <b>kein Befund</b> und
     * darf nirgends als "ueberzaehlig" oder falsch erscheinen. Genau diese
     * Verwechslung hat den Abgleich schon einmal dazu gebracht, handvergebene
     * Rollen abzuraeumen.</p>
     */
    public record VorhandeneRolle(String discordRoleId, String name, boolean verwaltet) {}

    /**
     * Ob an diesem Konto etwas nicht stimmt.
     *
     * <p>{@link #pruefbar} zaehlt hier absichtlich <b>nicht</b> hinein: "nicht
     * pruefbar" ist eine Aussage ueber den Zugriff, kein Befund ueber Rollen.
     * Beides in einem Merkmal haette die Meldung "Rollen weichen ab" fuer jedes
     * Konto erzeugt, an dem der Bot ohnehin nichts ausrichten kann.</p>
     */
    public boolean hatBefund() {
        return !fehlendeRollen.isEmpty() || !ueberzaehligeRollen.isEmpty() || sollUneinig;
    }
}
