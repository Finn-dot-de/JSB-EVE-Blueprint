package com.eve.own.auth.backend.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Wer wem wann welche Rolle von Hand gegeben oder genommen hat.
 *
 * <p>Am Charakter steht nur der Rollenname. Woher er kommt, sagt der Rollensatz
 * nicht: eine Rolle aus einem Ingame-Titel, eine aus einer Gruppenaufnahme und
 * eine von Hand vergebene sehen dort zeichengenau gleich aus. Fuer die
 * Gruppenaufnahme haelt {@code AuthGroupRequest.decidedByCharacterId} den
 * Nachweis fest - fuer die Vergabe von Hand gab es bis hierher keinen, und
 * genau diesen Weg eroeffnet {@code RoleAssignmentService}. Ohne diese Tabelle
 * waere die Frage "wer hat mir das gegeben?" ab dem ersten Klick unbeantwortbar.</p>
 *
 * <p><b>Diese Aufzeichnung rekonstruiert die Vergangenheit nicht.</b> Sie
 * beginnt leer und fuellt sich ausschliesslich mit dem, was ab ihrer Einfuehrung
 * ueber {@code RoleAssignmentService} laeuft. Jede Rolle, die ein Charakter
 * heute schon traegt, steht hier nicht drin - nicht als Luecke, sondern weil es
 * die Angabe nie gab und sie sich aus dem Rollensatz auch nicht erraten laesst.
 * Ein leerer Verlauf heisst deshalb "seit Einfuehrung nichts von Hand geaendert"
 * und niemals "die Rolle war schon immer da".</p>
 *
 * <p>Die Zeile bleibt auch dann stehen, wenn die Rolle laengst wieder entzogen
 * ist oder es sie gar nicht mehr gibt - sie ist ein Protokoll und kein Abbild
 * des heutigen Zustands. Deshalb steht hier auch der blanke Rollenname statt
 * eines Fremdschluessels auf {@code system_roles}: eine geloeschte Rolle darf
 * ihren eigenen Nachweis nicht mitnehmen.</p>
 */
@Entity
@Table(name = "role_assignment_audit",
        indexes = {
                // Der haeufigste Zugriff ist "der Verlauf dieses einen Charakters",
                // und die Tabelle waechst nur, sie wird nie aufgeraeumt.
                @Index(name = "idx_role_audit_character", columnList = "character_id"),
                @Index(name = "idx_role_audit_role", columnList = "role_name")
        })
@Getter
@Setter
public class RoleAssignmentAudit {

    /**
     * Die Handlung als Zeichenkette und nicht als {@code enum} - dieselbe
     * Ueberlegung wie bei {@code AuthGroupRequest.status}: die Werte liegen in
     * der Datenbank, und eine Zeichenkette laesst sich ohne Wanderung des
     * Schemas erweitern. Der Preis sind diese Konstanten, denn ein Tippfehler
     * in {@code "REVOK"} faellt still auf die Fuesse.
     */
    public static final String ACTION_GRANT = "GRANT";
    public static final String ACTION_REVOKE = "REVOKE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Der Charakter, dessen Rollensatz sich geaendert hat - nicht sein Konto.
     *
     * <p>Rollen haengen am einzelnen Charakter, nicht am Verbund aus Haupt- und
     * Alt-Charakteren. Wer einem Alt etwas gibt, gibt es nicht dem Main.</p>
     */
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /** Die betroffene Rolle, bereits in der Schreibweise aus {@code SystemRoles.normalize}. */
    @Column(name = "role_name", nullable = false)
    private String roleName;

    /** GRANT oder REVOKE - siehe die Konstanten oben. */
    @Column(nullable = false, length = 16)
    private String action;

    /** Wer gehandelt hat. Ohne dieses Feld waere die ganze Zeile wertlos. */
    @Column(name = "actor_character_id", nullable = false)
    private Long actorCharacterId;

    /**
     * Ob sich der Handelnde die Rolle selbst gegeben oder genommen hat.
     *
     * <p>Steht ausdruecklich in einer eigenen Spalte, obwohl sich derselbe Wert
     * aus dem Vergleich der beiden IDs ergibt. Der Grund ist derselbe wie beim
     * protokollierten Alleingang des IT-Admins in {@code AuthGroupService.decide}:
     * die Selbstvergabe ist der Fall, den jemand spaeter <em>suchen</em> wird.
     * Als Vergleich zweier Spalten muesste er dafuer wissen, dass es diesen Fall
     * ueberhaupt gibt; als Kennzeichen faellt er in jeder Liste von selbst auf.</p>
     *
     * <p>Verboten ist die Selbstvergabe nicht - ein Admin kann sich jede Rolle
     * ohnehin ueber den Rollenkatalog oder eine selbst angelegte Gruppe
     * verschaffen. Eine Sperre hier waere Symbolik und wuerde den Vorgang nur auf
     * einen Weg ohne Nachweis draengen. Sichtbar muss er sein, nicht unmoeglich.</p>
     */
    @Column(name = "self_assigned", nullable = false)
    private boolean selfAssigned;

    /**
     * Warum - freiwillige Angabe des Handelnden, {@code null} wenn er keine machte.
     *
     * <p>Bewusst kein Pflichtfeld: ein erzwungener Grund wird zu "x" oder ".",
     * und ein Protokoll voller Platzhalter ist schlechter als eines, in dem das
     * Fehlen der Angabe ehrlich sichtbar bleibt.</p>
     */
    @Column(length = 500)
    private String reason;

    /** Wann. Ohne Zeitpunkt liesse sich die Reihenfolge zweier Aenderungen nicht sagen. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
