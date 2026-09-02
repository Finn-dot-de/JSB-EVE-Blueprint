package com.eve.own.auth.backend.domain.character.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;

/**
 * Eine Aufzeichnung von Standort und Ein-/Ausloggzeiten eines Corp-Mitglieds.
 *
 * <h2>Warum aus der Mitgliederverfolgung und nicht aus {@code /location/}</h2>
 * <p>{@code /characters/{id}/location/} braucht ein eigenes Token und ist damit
 * fuer die eigentliche Zielgruppe <b>strukturell leer</b>: der gesuchte Alt ist
 * ja gerade der, der sich nie angemeldet hat. Die Mitgliederverfolgung kostet
 * dagegen <em>einen</em> Aufruf je Corporation und deckt jedes Mitglied ab,
 * registriert oder nicht. Das ist derselbe Grund, aus dem schon die
 * Beitrittsdaten von dort kommen.</p>
 *
 * <h2>Warum ueberhaupt aufgezeichnet wird</h2>
 * <p>ESI nennt nur den <em>letzten</em> Logon und Logoff, keine Zeitreihe. Aus
 * einem Momentanwert laesst sich keine Korrelation bilden - und gemeinsames
 * Ein- und Ausloggen im Sekundenbereich ist die eigentliche Signatur des
 * Multiboxings. Erst das regelmaessige Mitschreiben macht aus dem Momentanwert
 * eine Reihe.</p>
 *
 * <h2>Warum nicht jeder Lauf eine Zeile schreibt</h2>
 * <p>Geschrieben wird nur, wenn sich gegenueber der letzten Zeile desselben
 * Charakters etwas <b>geaendert</b> hat. Ein Mitglied, das eine Woche nicht
 * einloggt, erzeugt in dieser Woche keine einzige Zeile. Ohne diese Bremse
 * waere die Tabelle voll mit Wiederholungen derselben Aussage - und die
 * Mengenrechnung im {@code AltSourceScheduler} zeigt, dass das der Unterschied
 * zwischen sechsstelligen und fuenfstelligen Zeilenzahlen ist.</p>
 *
 * <p><b>Aufbewahrung:</b> Bewegungsdaten mit Personenbezug. Sie werden nach der
 * in {@code AltSourceProperties} eingestellten Frist geloescht - von einem Lauf,
 * der wirklich laeuft, siehe {@code AltSourceRetentionScheduler}.</p>
 */
@Entity
@Table(name = "corporation_member_presence", indexes = {
        // Wonach spaeter gesucht wird: die Reihe EINES Charakters ueber die Zeit.
        @Index(name = "idx_presence_char_time", columnList = "character_id, measured_at"),
        // Wonach der Loeschlauf sucht.
        @Index(name = "idx_presence_measured_at", columnList = "measured_at"),
        @Index(name = "idx_presence_corp", columnList = "corporation_id")
})
@Getter
@Setter
public class CorporationMemberPresence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "corporation_id", nullable = false)
    private Long corporationId;

    /** Das Mitglied. Muss nicht registriert sein - genau darum geht es. */
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /**
     * Station, Struktur oder Solarsystem laut ESI.
     *
     * <p>{@code nullable}: ESI sichert ausser der Charakter-ID kein Feld zu.
     * Fehlt der Standort, ist er unbekannt - und nicht "Standort 0".</p>
     */
    @Column(name = "location_id")
    private Long locationId;

    /** Letzter Logon laut ESI - der Zeitstempel, aus dem die Korrelation entsteht. */
    @Column(name = "logon_date")
    private Instant logonDate;

    /** Letzter Logoff laut ESI. */
    @Column(name = "logoff_date")
    private Instant logoffDate;

    /** Zeitpunkt der Messung, also wann dieser Lauf ESI gefragt hat. */
    @Column(name = "measured_at", nullable = false)
    private Instant measuredAt;

    /**
     * Ob diese Zeile dieselbe Aussage traegt wie die uebergebene.
     *
     * <p>Der Messzeitpunkt zaehlt bewusst <em>nicht</em> mit - er ist bei jedem
     * Lauf ein anderer, und wuerde er mitverglichen, waere jede Zeile
     * verschieden und die Bremse aus der Klassendoku wirkungslos.</p>
     */
    public boolean sameStateAs(CorporationMemberPresence other) {
        return other != null
                && Objects.equals(locationId, other.locationId)
                && Objects.equals(logonDate, other.logonDate)
                && Objects.equals(logoffDate, other.logoffDate);
    }
}
