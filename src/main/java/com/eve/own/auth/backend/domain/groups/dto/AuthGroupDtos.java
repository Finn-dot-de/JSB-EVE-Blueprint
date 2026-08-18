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
     *     Antrag durchaus noch null Mitglieder haben
     * @param isMember          ob der angemeldete Charakter die Rolle bereits traegt
     * @param hasPendingRequest ob von ihm eine offene Anfrage vorliegt
     * @param isLeader          ob er mindestens eine der Leitungsrollen traegt
     */
    public record GroupDto(Long id, String name, String description, String roleName,
                           List<String> leaderRoleNames,
                           long memberCount,
                           boolean isMember, boolean hasPendingRequest, boolean isLeader) {}

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
