package com.eve.own.auth.backend.domain.groups.dto;

import java.time.Instant;
import java.util.List;

/**
 * Die Datensaetze der Gruppen (SIGs) - Anzeige wie Pflege.
 *
 * <p>Eine Gruppe ist technisch genau eine Rolle: wer aufgenommen wird, bekommt
 * {@code roleName} in sein {@code roles}-Set, und der Discord-Sync verteilt sie
 * von dort weiter. Nach aussen ist davon nichts zu sehen - deshalb tragen die
 * Datensaetze die Sicht des Aufrufers gleich mit, statt das Frontend die
 * Mitgliedschaft aus Rollennamen selbst zusammenreimen zu lassen.</p>
 */
public class AuthGroupDtos {

    /**
     * Eine Gruppe, gesehen vom Aufrufer.
     *
     * <p>{@code leaderRoleNames} gehen als blanke Rollennamen hinaus und nicht
     * als Namen samt Portrait von Personen: hinter der Leitung stehen seit der
     * Umstellung mehrere Rollen mit je mehreren Traegern, ein einzelnes Gesicht
     * dafuer waere gelogen. Die Oberflaeche zeigt die Namen als Etiketten; eine
     * leere Liste heisst "ohne Leitung" - dann entscheiden nur die Admins.</p>
     *
     * <p>Die Liste geht sortiert hinaus. Am Charakter wie an der Gruppe liegen
     * die Rollen in einer {@code Set}, deren Reihenfolge nicht festliegt; ohne
     * Sortierung wechselten die Etiketten bei jedem Laden die Plaetze.</p>
     *
     * @param memberCount       wie viele Charaktere die Rolle derzeit tragen - das
     *     ist nicht die Zahl der offenen Anfragen, eine Gruppe kann mit laufendem
     *     Antrag durchaus noch null Mitglieder haben.
     *     <p>{@code null} fuer jeden ausserhalb des Sichtkreises, der auch die
     *     Mitgliederliste sehen darf. Die Zahl nennt zwar keine Namen, sie ist
     *     aber dieselbe Auskunft eine Stufe grober - und wer beitreten und
     *     austreten will, braucht sie nicht. Sie stehen zu lassen waere zudem
     *     ein Leck mit Ansage: eine Gruppe, deren Zahl sich nach dem eigenen
     *     Beitritt von 3 auf 4 bewegt, ist abzaehlbar, und bei einer Gruppe mit
     *     genau einem Mitglied verraet schon die 1 zusammen mit einem
     *     Discord-Rollenetikett die Person.
     *     <p>Bewusst {@code null} und nicht {@code 0}: die Null waere eine
     *     Falschaussage ("niemand ist drin"), aus demselben Grund, aus dem die
     *     Mitgliederliste fuer Unberechtigte eine Ausnahme wirft und keine leere
     *     Liste liefert. Die Oberflaeche blendet die Zahl bei {@code null} aus.
     * @param canViewMembers    ob der Betrachter die Mitgliederliste dieser Gruppe
     *     abrufen darf - dieselbe Auswertung, aus der auch {@code memberCount}
     *     entsteht und an der {@code GET /api/groups/{id}/members} scheitert.
     *     <p>Steht neben {@code memberCount} und nicht in ihm, obwohl die beiden
     *     heute zeichengenau dasselbe sagen: die Zahl ist eine <b>Auskunft</b>,
     *     die Berechtigung eine <b>Zusicherung</b>. Beides in einem Feld zu
     *     tragen hiesse, die Oberflaeche muesste aus dem Fehlen einer Zahl auf
     *     ein Recht schliessen - eine Ableitung, die nirgends geschrieben steht
     *     und deshalb still falsch wird, sobald jemand die beiden entkoppelt
     *     (Zahl fuer alle, Liste nur fuer den Kreis, oder umgekehrt). Kein Test
     *     und kein Uebersetzer schluege dabei an; die Oberflaeche zeigte
     *     lediglich den falschen Knopf.
     *     <p>Ein {@code boolean} und kein {@code Boolean}: "unbekannt, ob
     *     erlaubt" gibt es nicht - der Dienst kennt den Betrachter, wenn er den
     *     Datensatz baut.
     * @param isMember          ob der angemeldete Charakter die Rolle bereits traegt
     * @param hasPendingRequest ob von ihm eine offene Anfrage vorliegt
     * @param isLeader          ob er mindestens eine der Leitungsrollen traegt
     */
    public record GroupDto(Long id, String name, String description, String roleName,
                           List<String> leaderRoleNames,
                           Long memberCount, boolean canViewMembers,
                           boolean isMember, boolean hasPendingRequest, boolean isLeader) {}

    /**
     * Ein Mitglied einer Gruppe: der Charakter, der ihre Rolle traegt.
     *
     * <p>Bewusst dieselben drei Felder wie im Antragskopf von
     * {@link GroupRequestDto} - ID, Name, Portrait. Die Oberflaeche zeigt eine
     * Mitgliederliste und eine Anfrageliste direkt untereinander; unterschiedliche
     * Feldnamen fuer dieselbe Person waeren dort nur eine Stolperstelle.</p>
     *
     * <p>Diesen Datensatz bekommt nicht jeder Angemeldete zu sehen, sondern nur
     * der Sichtkreis aus {@code AuthGroupService} (Fuehrung, IT und A38). Wer
     * nicht dazugehoert, bekommt eine {@code AccessDeniedException} und keine
     * leere Liste - eine leere Liste behauptete, die Gruppe sei leer.</p>
     *
     * <p>Ohne Rollennamen und ohne Rechtekennzeichen: welche Rollen jemand sonst
     * noch traegt, gehoert in den Rollenkatalog und nicht in die Mitgliederliste
     * einer einzelnen SIG. Ob der Betrachter entfernen darf, haengt an der Gruppe
     * und nicht am einzelnen Mitglied - das sagt {@link GroupDto#isLeader()}.</p>
     */
    public record GroupMemberDto(Long characterId, String characterName, String portraitUrl) {}

    /**
     * Eine Anfrage, wie die Verwaltung sie sieht.
     *
     * @param requestedAt geht als ISO-8601-Zeichenkette hinaus, damit die
     *     Oberflaeche das Datum in der Zeitzone des Betrachters anzeigen kann
     */
    public record GroupRequestDto(Long requestId, Long groupId, String groupName,
                                  Long characterId, String characterName, String portraitUrl,
                                  String status, Instant requestedAt) {}

    /**
     * Was die Verwaltung beim Anlegen oder Aendern schickt; {@code id == null} heisst: neu.
     *
     * <p>{@code leaderRoleNames} darf leer oder {@code null} sein - dann
     * entscheiden allein die Admins ueber die Anfragen dieser Gruppe.</p>
     *
     * <p>{@code roleName} darf beim Anlegen leer bleiben: der Dienst leitet den
     * Namen dann aus dem Gruppennamen ab und legt die Rolle an. Wer einen
     * anderen Namen will, schickt ihn mit - der Vorschlag ist nur ein Vorschlag.</p>
     */
    public record SaveGroupDto(Long id, String name, String description, String roleName,
                               List<String> leaderRoleNames) {}
}
