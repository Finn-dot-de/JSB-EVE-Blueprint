package com.eve.own.auth.backend.domain.fleet.entity;

import com.eve.own.auth.backend.domain.fleet.PingErwaehnung;
import com.eve.own.auth.backend.domain.fleet.PingZustand;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Eine abgesetzte Flottenankuendigung.
 *
 * <p>Nicht zu verwechseln mit {@link FleetEvent}: der ist ein <b>rueckwirkender</b>
 * Nachweis, wer dabei war. Dieser hier ist die Ankuendigung <b>vorher</b> und
 * weiss nichts ueber Teilnehmer. Die beiden zusammenzulegen hiesse, eine Flotte
 * ohne Ping oder einen Ping ohne Flotte zum Sonderfall zu machen - beides ist
 * der Normalfall.</p>
 *
 * <h2>Welche Felder eine EVE-Flottenankuendigung wirklich braucht</h2>
 * <p>Massstab war eine einzige Frage: Ohne welche Angabe kann ein Pilot nicht
 * entscheiden, ob und womit er andockt? Alles andere gehoert in den Freitext
 * und nicht in eine eigene Spalte.</p>
 * <ul>
 *   <li>{@link #fleetType} - Roam, Home Defense, Strat Op, Mining Op. Entscheidet,
 *       ob man das billige oder das teure Schiff nimmt, und ob man ueberhaupt
 *       Zeit hat: ein Strat Op dauert vier Stunden, eine Home Defense zwanzig
 *       Minuten.</li>
 *   <li>{@link #doctrine} - <b>womit</b>. Ein Ping ohne Doktrin fuellt die
 *       Flotte mit dem, was gerade im Hangar steht, und das ist keine Flotte,
 *       sondern eine Ansammlung.</li>
 *   <li>{@link #formupLocation} - <b>wo</b>. Die wichtigste Angabe ueberhaupt.
 *       Wer in der falschen Region sitzt, braucht zwanzig Spruenge und kommt
 *       zur Ankuendigung zu spaet, egal wie gut er sie gelesen hat.</li>
 *   <li>{@link #formupTime} - <b>wann</b>, siehe unten zur Zeitzone.</li>
 *   <li>{@link #comms} - wo zugehoert wird. Ein Pilot in der Flotte, aber nicht
 *       auf Comms, ist im Gefecht ein Schiff, das nicht auf Ansagen reagiert -
 *       schlimmer als eines, das gar nicht da ist.</li>
 *   <li>{@link #srpCovered} - ob Verluste ersetzt werden. Das ist der
 *       Unterschied zwischen "ich bringe den guten Rumpf" und "ich bringe, was
 *       ich verschmerzen kann". Als Wahrheitswert mit erlaubtem {@code null}:
 *       "nicht gesagt" ist eine eigene Aussage und darf nicht als "nein"
 *       gelesen werden.</li>
 * </ul>
 * <p>Bewusst <b>nicht</b> aufgenommen: eine Teilnehmerzahl (die weiss vorher
 * niemand), ein Ziel-System (das sagt kein FC oeffentlich vor dem Start) und
 * eine Dauer (die ergibt sich aus dem Flottentyp).</p>
 *
 * <h2>Zeiten</h2>
 * <p>EVE-Zeit <em>ist</em> UTC, und {@link Instant} hat keine Zeitzone - er ist
 * ein Zeitpunkt und kein Kalenderblatt. Damit gibt es an keiner Stelle etwas
 * umzurechnen und folglich auch nichts falsch umzurechnen. Die Schnittstelle
 * nimmt und liefert ISO-8601 <b>mit</b> Versatz ({@code 2026-09-03T19:00:00Z});
 * eine Angabe ohne Versatz wird abgewiesen, statt sie als Serverzeit zu raten.
 * In Discord steht die Zeit doppelt: als UTC-Text fuer die EVE-Gewohnheit und
 * als {@code <t:...>}-Marke, die Discord jedem Leser in seiner eigenen Zone
 * anzeigt. Damit muss niemand mehr im Kopf rechnen - die haeufigste Quelle
 * dafuer, dass jemand eine Stunde zu spaet andockt.</p>
 */
@Entity
@Table(name = "fleet_pings", indexes = {
        // Die Wartezeit fragt bei JEDEM Ping nach dem juengsten Eintrag genau
        // dieses Charakters. Ohne Index waere das ein Tabellenscan an der
        // Stelle, die am haeufigsten und unter Zeitdruck laeuft.
        @Index(name = "idx_fleet_pings_fc_created", columnList = "fc_character_id, created_at"),
        @Index(name = "idx_fleet_pings_created", columnList = "created_at")
})
@Getter
@Setter
public class FleetPing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Wer gepingt hat - die Charakter-ID des angemeldeten Mains. */
    @Column(name = "fc_character_id", nullable = false)
    private Long fcCharacterId;

    /**
     * Und sein Name zum Zeitpunkt des Pings.
     *
     * <p>Mitgeschrieben statt bei der Anzeige nachgeschlagen, wie es
     * {@link FleetEvent} schon haelt: Die Liste ist die Rechenschaft. Sie muss
     * auch dann noch sagen koennen, wer gepingt hat, wenn der Charakter die
     * Corporation laengst verlassen hat und aus {@code characters} verschwunden
     * ist.</p>
     */
    @Column(name = "fc_character_name", nullable = false)
    private String fcCharacterName;

    /** Roam, Home Defense, Strat Op, Mining Op - frei, weil jede Corp anders benennt. */
    @Column(name = "fleet_type", nullable = false)
    private String fleetType;

    @Column(name = "doctrine")
    private String doctrine;

    /** Treffpunkt - System, Station oder Struktur, so wie der FC es ansagt. */
    @Column(name = "formup_location", nullable = false)
    private String formupLocation;

    /**
     * Wann geformt wird, in UTC.
     *
     * <p>Darf {@code null} sein, und das ist kein vergessenes Pflichtfeld:
     * "form up now" ist die haeufigste Ansage ueberhaupt. Sie mit der aktuellen
     * Uhrzeit zu fuellen saehe gleich aus, waere aber eine andere Aussage -
     * eine Minute spaeter stuende dort eine Zeit in der Vergangenheit.</p>
     */
    @Column(name = "formup_time")
    private Instant formupTime;

    @Column(name = "comms")
    private String comms;

    /** Ob Verluste ersetzt werden. {@code null} heisst "nicht gesagt", nicht "nein". */
    @Column(name = "srp_covered")
    private Boolean srpCovered;

    /** Alles, was in kein Feld passt. Der FC schreibt hier hin, was er will. */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * Die gewaehlte Lautstaerke.
     *
     * <p>{@link EnumType#STRING} und nicht die Ordinalzahl: Wer spaeter eine
     * Konstante dazwischenschiebt, verschoebe sonst rueckwirkend jeden alten
     * Datensatz um eine Stufe - aus "still" wuerde "@here". Bei einem Feld, das
     * genau die Lautstaerke protokolliert, waere das der teuerste denkbare
     * Fehler.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "erwaehnung", nullable = false, length = 16)
    private PingErwaehnung erwaehnung;

    /**
     * Bei {@link PingErwaehnung#ROLLE}: welche Rolle es getroffen hat.
     *
     * <p>Mitgeschrieben und nicht bei Bedarf aus der Konfiguration
     * nachgeschlagen. Der Grund ist derselbe wie bei {@link #fcCharacterName}:
     * Die Liste ist die Rechenschaft. Seit die Rolle je Ping waehlbar ist, sagt
     * "Erwaehnung ROLLE" allein gar nichts mehr - erst zusammen mit dieser
     * Spalte laesst sich hinterher beantworten, <em>wen</em> ein Ping aus dem
     * Bett geholt hat. Und beim Aendern wird der Text neu gebaut; ohne die
     * gespeicherte Kennung koennte eine Korrektur die Erwaehnung stillschweigend
     * auf eine andere Rolle umlenken.</p>
     *
     * <p>{@code null} bei jeder anderen Lautstaerke - und ausdruecklich auch
     * dann, wenn ein Ping von ROLLE auf etwas anderes geaendert wird. Eine
     * stehengebliebene Kennung waere eine Rolle, die im Datensatz noch
     * angeleuchtet aussieht, obwohl niemand sie mehr erwaehnt.</p>
     *
     * <p>Als Zeichenkette, wie {@link #discordMessageId}: Discord-Snowflakes
     * sind vorzeichenlose 64-Bit-Werte und passen nicht sicher in ein
     * {@code long}. Gerechnet wird damit nie.</p>
     */
    @Column(name = "erwaehnung_rolle_id", length = 32)
    private String erwaehnungRolleId;

    /**
     * Die ID der Nachricht in Discord.
     *
     * <p>Der Schluessel zu allem Nachtraeglichen: ohne sie laesst sich der Ping
     * nie wieder korrigieren oder absagen, und eine Flotte, die es nicht mehr
     * gibt, steht bis in alle Ewigkeit im Kanal. Deshalb ist sie
     * {@code nullable = false} - ein Datensatz ohne sie duerfte gar nicht erst
     * entstehen, und der Dienst legt ihn auch erst danach an.</p>
     *
     * <p>Als Zeichenkette und nicht als Zahl: Discord-Snowflakes sind
     * vorzeichenlose 64-Bit-Werte und passen nicht sicher in ein {@code long}.
     * Gerechnet wird damit ohnehin nie.</p>
     */
    @Column(name = "discord_message_id", nullable = false, length = 32)
    private String discordMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "zustand", nullable = false, length = 16)
    private PingZustand zustand;

    /** Wann abgesetzt - zugleich der Bezugspunkt der Wartezeit. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Wann zuletzt geaendert; bei einem unveraenderten Ping gleich {@link #createdAt}. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** Warum abgesagt - steht mit in der Korrektur im Kanal. */
    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;
}
