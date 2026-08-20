package com.eve.own.auth.backend.domain.auth.dto;

import com.eve.own.auth.backend.domain.auth.AuthRoleSource;
import java.time.Instant;
import java.util.List;

/**
 * Die Datensaetze fuer das Zuweisen und Entziehen einzelner Rollen.
 *
 * <p>Der Kern ist {@link RoleStateDto}: er beantwortet fuer jede Rolle
 * <em>vorab</em>, was ein Klick bewirken wuerde. Ohne diese Auskunft muesste die
 * Oberflaeche aus Rollennamen, Titelzuordnungen und dem {@code is_special}-Kennzeichen
 * selbst zusammenreimen, ob sich eine Rolle sinnvoll vergeben oder entziehen
 * laesst - dieselbe Ableitung ein zweites Mal, in einer anderen Sprache, und
 * ohne dass ein Test anschlaegt, wenn sie vom Dienst abweicht.</p>
 */
public class RoleAssignmentDtos {

    /**
     * Ein Charakter samt der Bewertung jeder Rolle, die es fuer ihn gibt.
     *
     * <p>Getragene und nicht getragene Rollen stehen in <b>einer</b> Liste und
     * nicht in zweien. Der Unterschied ist ein Kennzeichen ({@link RoleStateDto#held()})
     * und kein Strukturbruch: eine getrennte "vergebbar"-Liste muesste dieselben
     * Bewertungsfelder noch einmal fuehren, und beim naechsten Feld wuerde eine
     * der beiden vergessen.</p>
     */
    public record CharacterRolesDto(Long characterId,
                                    String characterName,
                                    String portraitUrl,
                                    List<RoleStateDto> roles) {}

    /**
     * Was ein Klick auf diese Rolle bewirken wuerde - die Auskunft VOR der Tat.
     *
     * @param held ob der Charakter die Rolle derzeit traegt
     * @param survivesSync ob die Rolle den Rollen-Sync ueberdauert, also das
     *     {@code is_special}-Kennzeichen aus {@code system_roles} traegt.
     *     {@code CharacterRoleService.applyRoles} baut den Rollensatz alle zehn
     *     Minuten neu auf und rettet aus dem alten Stand ausschliesslich die so
     *     gekennzeichneten Rollen. Steht hier {@code false} und die Rolle waere
     *     trotzdem vergebbar, dann nur, weil das Zuweisen das Kennzeichen selbst
     *     setzt - siehe {@code RoleAssignmentService.grant}.
     * @param assignable ob sich die Rolle diesem Charakter zuweisen laesst
     * @param revocable ob sich das Entziehen ueberhaupt lohnt. {@code false} bei
     *     einer Rolle, die ein Ingame-Titel vergibt: der naechste Sync traegt sie
     *     wieder ein, und der Admin haette dreimal geklickt, bevor er merkt, dass
     *     es nicht an ihm liegt.
     * @param grantingTitles die Ingame-Titel der Corporation dieses Charakters,
     *     die diese Rolle vergeben - die Begruendung zu {@code revocable == false}
     *     in Worten, die der Admin ingame wiederfindet
     * @param note ein Satz, warum es so ist, wie es ist. Zum Anzeigen gedacht;
     *     die Oberflaeche soll den Text nicht aus den Kennzeichen selbst bauen,
     *     sonst stehen zwei Begruendungen fuer dieselbe Regel an zwei Orten.
     */
    public record RoleStateDto(String roleName,
                               String description,
                               AuthRoleSource source,
                               boolean held,
                               boolean survivesSync,
                               boolean assignable,
                               boolean revocable,
                               List<String> grantingTitles,
                               String note) {}

    /**
     * Was die Oberflaeche beim Zuweisen und beim Entziehen schickt.
     *
     * <p>{@code reason} darf fehlen - siehe {@code RoleAssignmentAudit.reason}:
     * ein erzwungener Grund wird zu "x", und das ist schlechter als ein ehrlich
     * leeres Feld.</p>
     */
    public record ChangeRoleDto(String roleName, String reason) {}

    /**
     * Ein Eintrag aus dem Nachweis.
     *
     * <p>Namen und Portrait stehen mit drin, obwohl in der Tabelle nur IDs
     * liegen: der Nachweis wird gelesen, um zu verstehen, wer gehandelt hat, und
     * eine Liste aus Zahlen beantwortet das nicht. Ist ein Charakter
     * zwischenzeitlich verschwunden, bleibt die ID die beste Auskunft - genauso
     * wie bei {@code AuthGroupDtos.GroupRequestDto}.</p>
     *
     * @param selfAssigned ob sich der Handelnde selbst bedient hat. Als eigenes
     *     Feld und nicht als Vergleich zweier IDs, damit die Oberflaeche den Fall
     *     hervorheben kann, ohne ihn erst kennen zu muessen.
     * @param occurredAt geht als ISO-8601-Zeichenkette hinaus, damit die
     *     Oberflaeche den Zeitpunkt in der Zone des Betrachters anzeigen kann
     */
    public record RoleAuditDto(Long id,
                               Long characterId,
                               String characterName,
                               String portraitUrl,
                               String roleName,
                               String action,
                               Long actorCharacterId,
                               String actorName,
                               boolean selfAssigned,
                               String reason,
                               Instant occurredAt) {}
}
