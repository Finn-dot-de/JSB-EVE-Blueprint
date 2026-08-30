package com.eve.own.auth.backend.domain.character.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Die vom Director bestaetigte <em>Vermutung</em>, dass ein nicht registrierter
 * Charakter zu einem bestimmten Konto gehoert - und der Nachweis, wer sie
 * ausgesprochen hat.
 *
 * <h2>Warum eine Vormerkung und keine Zuordnung</h2>
 * <p>Ein "nicht registrierter Charakter" ist genau definiert als eine
 * ESI-Mitglieds-ID, zu der es <em>keine</em> Zeile in {@code characters} gibt.
 * Eine Bestaetigung koennte deshalb gar kein {@code UPDATE} auf
 * {@code main_character_id} machen - sie muesste eine Karteileiche anlegen: eine
 * Charakterzeile ohne Access-Token, ohne Refresh-Token, die es in diesem System
 * noch nie gegeben hat. Jede Annahme ueber {@code characters} lautet bisher
 * "diese Zeile ist durch einen EVE-SSO-Login entstanden".</p>
 *
 * <p>Was daran haengt, ist nicht wenig: {@code getAccountId()} ist der Verteiler
 * fuer die Mining-Steuerschuld, die Bestaende, die Industrie-Auftraege, den
 * Discord-Nickname und das Sitzungs-Token. Und es haengt ein Fehler daran, der
 * in die andere Richtung wirkt: meldet sich der <em>echte</em> Besitzer spaeter
 * erstmals an, greift der Zweig {@code else if (getMainCharacterId() == null)}
 * in {@code AuthService.saveOrUpdate} nicht mehr - der Fremdverweis bleibt
 * stehen, und das ausgestellte JWT traegt die ID und die Rollen des fremden
 * Mains. Ein einziger Klick auf "Verknuepfen" waere eine Kontouebernahme in
 * beide Richtungen. Rueckgaengig machen liesse sie sich nicht: es gibt im
 * gesamten Produktivcode keinen Weg, der einen Charakter aus einem Konto
 * herausloest.</p>
 *
 * <h2>Der Weg, den es stattdessen gibt</h2>
 * <p>Die Zuordnung entsteht weiterhin ausschliesslich dort, wo sie schon immer
 * entstand: beim EVE-SSO-Login des Charakters selbst, waehrend der Main
 * angemeldet ist ({@code AuthService.saveOrUpdate} mit gesetztem
 * {@code loggedInMainId}, erreicht ueber "Alt hinzufuegen"). Dieser Weg braucht
 * keine Rollenpruefung und kein Protokoll, weil CCP die Eigentuemerschaft
 * bereits beweist - wer den Charakter zuordnen will, muss dessen Zugangsdaten
 * besitzen. Eine Wahrscheinlichkeit von 80 ist dafuer kein Ersatz.</p>
 *
 * <p>Diese Zeile ist deshalb <b>ein Hinweis, kein Schreibzugriff</b>: sie haelt
 * fest, dass die Fuehrung den Verdacht fuer richtig haelt, damit der Main den
 * Charakter gezielt ueber den bestehenden Weg nachziehen kann. Sie beruehrt
 * {@code characters} nicht.</p>
 *
 * <h2>Warum sie trotzdem einen vollen Nachweis traegt</h2>
 * <p>Weil eine Kontozuordnung mindestens so schwerwiegend ist wie eine
 * Rollenvergabe - und die hat mit {@code role_assignment_audit} laengst einen
 * Nachweis. Ohne {@code actorCharacterId} waere die Frage "wer hat behauptet,
 * dass dieser Charakter mir gehoert?" ab dem ersten Klick unbeantwortbar.
 * Festgehalten wird auch die errechnete Wahrscheinlichkeit samt ihrer
 * Aufschluesselung: sie ist der Grund der Entscheidung und laesst sich spaeter
 * nicht rekonstruieren, weil die Signale von ESI-Daten abhaengen, die sich
 * aendern.</p>
 */
@Entity
@Table(name = "alt_link_proposals",
        // Ein Charakter kann nur EINEM Konto vorgemerkt sein. Die Eindeutigkeit
        // liegt in der Datenbank und nicht bloss im Dienst: zwei gleichzeitige
        // Bestaetigungen zweier Directors wuerden die Pruefung im Dienst beide
        // passieren und danach zwei widersprechende Zeilen hinterlassen.
        uniqueConstraints = @UniqueConstraint(
                name = "uk_alt_link_unauthed_char",
                columnNames = "unauthed_character_id"),
        indexes = {
                @Index(name = "idx_alt_link_main", columnList = "main_character_id"),
                @Index(name = "idx_alt_link_corp", columnList = "corporation_id")
        })
@Getter
@Setter
public class AltLinkProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Der nicht registrierte Charakter. Steht bewusst NICHT als Fremdschluessel
     * auf {@code characters} - es gibt dort per Definition keine Zeile fuer ihn. */
    @Column(name = "unauthed_character_id", nullable = false)
    private Long unauthedCharacterId;

    /**
     * Sein Name zum Zeitpunkt der Bestaetigung.
     *
     * <p>Mitgeschrieben und nicht nachgeschlagen: der Name kommt aus ESI, ein
     * Spieler kann ihn aendern, und ein Nachweis, der sich hinterher anders
     * liest als beim Klick, ist keiner.</p>
     */
    @Column(name = "unauthed_character_name", nullable = false)
    private String unauthedCharacterName;

    /** Das Konto, dem er vorgemerkt ist - die ID des Main-Charakters. */
    @Column(name = "main_character_id", nullable = false)
    private Long mainCharacterId;

    /** Die Corporation, aus deren Mitgliederliste der Verdacht stammt. */
    @Column(name = "corporation_id", nullable = false)
    private Long corporationId;

    /** Die errechnete Wahrscheinlichkeit 0..100 zum Zeitpunkt der Bestaetigung. */
    @Column(nullable = false)
    private int probability;

    /**
     * Worauf die Zahl beruhte, im Klartext.
     *
     * <p>Ohne diese Spalte stuende im Nachweis eine nackte Zahl, und genau die
     * ist das Problem: eine 94 aus einem einzigen Signal und eine 94 aus dreien
     * sind verschiedene Entscheidungen. Als Text und nicht als Spalten je
     * Signal, damit ein spaeter hinzugekommenes Signal alte Zeilen nicht
     * unleserlich macht.</p>
     */
    @Column(name = "signal_summary", length = 500)
    private String signalSummary;

    /** Wer bestaetigt hat. Ohne dieses Feld waere die ganze Zeile wertlos. */
    @Column(name = "actor_character_id", nullable = false)
    private Long actorCharacterId;

    /**
     * Ob der Handelnde den fremden Charakter dem <em>eigenen</em> Konto
     * vorgemerkt hat.
     *
     * <p>Eigene Spalte, obwohl sich derselbe Wert aus dem Vergleich zweier IDs
     * ergibt - dieselbe Ueberlegung wie bei {@code RoleAssignmentAudit}: das ist
     * der Fall, den jemand spaeter <em>suchen</em> wird, und als Spaltenvergleich
     * muesste er erst wissen, dass es ihn gibt.</p>
     *
     * <p>Verboten ist es nicht, weil es nichts einbringt: die Vormerkung
     * schreibt nichts, und eingeloest wird sie nur durch den SSO-Login des
     * Charakters selbst. Sichtbar muss es sein, nicht unmoeglich.</p>
     */
    @Column(name = "self_assigned", nullable = false)
    private boolean selfAssigned;

    /** Wann. Ohne Zeitpunkt liesse sich die Reihenfolge zweier Vormerkungen nicht sagen. */
    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;
}
