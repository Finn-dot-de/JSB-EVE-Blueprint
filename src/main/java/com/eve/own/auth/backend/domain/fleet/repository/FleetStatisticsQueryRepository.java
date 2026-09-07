package com.eve.own.auth.backend.domain.fleet.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Die Abfragen der FAT-Statistik.
 *
 * <h2>Warum ein Skelett und nicht zwei Aggregate</h2>
 * <p>Die Seite zeigt eine Kopfzeile und eine Teilnahmetafel. Zwei getrennte
 * {@code GROUP BY}-Abfragen waeren naheliegend und waeren falsch: Sie driften
 * auseinander, sobald waehrend des Ladens eine Flotte geschlossen oder ein
 * Teilnehmer nachgetragen wird - dann steht in der Kopfzeile 40 und in der
 * Tafel darunter 41. Eine Auswertung, die sich selbst widerspricht, verliert
 * genau die Glaubwuerdigkeit, um die es auf dieser Seite geht.</p>
 *
 * <p>Deshalb <em>eine</em> Zeilenmenge je (Flotte, Teilnehmer) und beides
 * daraus in einem Durchlauf im Dienst. Die zweite Abfrage hier ist keine
 * Wiederholung derselben Zahlen, sondern beantwortet die eine Frage, die das
 * Fenster nicht beantworten kann: wie weit die Historie ueberhaupt reicht.</p>
 *
 * <h2>Warum nativ</h2>
 * <p>{@code fleet_attendance} hat keine JPA-Beziehung zu {@code fleet_events} -
 * die Entitaeten fuehren nur die IDs. In JPQL laesst sich der Verbund deshalb
 * nicht ausdruecken.</p>
 */
@Repository
public class FleetStatisticsQueryRepository {

    @PersistenceContext
    private EntityManager em;

    /**
     * Was vor dem Laden feststehen muss.
     *
     * @param skelettZeilen wie viele Zeilen das Skelett im gewaehlten Fenster
     *     haette - gezaehlt, nicht geladen, damit ein zu grosses Fenster nicht
     *     erst beim Materialisieren auffaellt
     * @param ersteFlotteInsgesamt der Beginn der aeltesten Flotte ueberhaupt,
     *     auch ausserhalb des Fensters. Nur damit laesst sich sagen, ob "90
     *     Tage" tatsaechlich 90 Tage Daten sind oder 23 - ein Nenner, den es
     *     nicht gibt, darf nicht behauptet werden. {@code null} heisst: es gab
     *     nie eine Flotte.
     */
    public record Vorab(long skelettZeilen, Instant ersteFlotteInsgesamt) {}

    /**
     * Zeilenzahl des Fensters und Beginn der aeltesten Flotte - in einer
     * Abfrage, weil beide vor dem Laden gebraucht werden und beide reine
     * Skalare sind.
     */
    public Vorab vorab(Instant von) {
        String sql = """
                SELECT (SELECT COUNT(*)
                          FROM fleet_events e
                          LEFT JOIN fleet_attendance a ON a.fleet_event_id = e.id
                         WHERE e.start_time >= :von)          AS "skelettZeilen",
                       (SELECT MIN(start_time) FROM fleet_events) AS "ersteFlotte"
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("von", von);
        Tuple zeile = (Tuple) q.getSingleResult();
        return new Vorab(zahl(zeile.get("skelettZeilen")), zeitpunkt(zeile.get("ersteFlotte")));
    }

    /**
     * Eine Zeile je (Flotte, Teilnehmer) - die Grundlage der ganzen Seite.
     *
     * <p>{@code LEFT JOIN} auf die Teilnahme, damit eine Flotte ohne einen
     * einzigen Teilnehmer nicht verschwindet: Sie ist trotzdem gefahren
     * worden, zaehlt in die Flottenzahl der Kopfzeile, und ihre leere
     * Teilnehmerliste ist eine Aussage.</p>
     *
     * <p>Nicht registrierte Piloten ("Unknown Pilot 123") bleiben drin. Sie
     * hier herauszufiltern wuerde die Teilnehmerzahl der Flotte
     * verfaelschen.</p>
     *
     * <p>{@code a.id} kommt mit, weil {@code fleet_attendance} keinen
     * Eindeutigkeitsindex auf (Flotte, Charakter) hat - die Eindeutigkeit
     * haengt allein daran, dass der Scheduler vor dem Schreiben sucht, und
     * dessen 60-Sekunden-Lauf kann sich mit einem manuellen Abgleich
     * ueberschneiden. Der Dienst entdoppelt darueber.</p>
     *
     * <h3>Warum eine Account-Spalte dazugehoert</h3>
     * <p>Gezaehlt wird je Account und nicht je Charakter: Wer drei Alts in eine
     * Flotte bringt, war <em>einmal</em> dabei. Ohne diese Spalte maesse die
     * Tafel die Zahl der Bildschirme statt der Beteiligung - und gerade beim
     * Mining wird hier ohnehin multiboxt.</p>
     *
     * <p>Der {@code COALESCE} ist der Rueckfall auf den Charakter selbst und
     * kein Schoenheitsfehler: Er faengt zwei Faelle. Ein Main traegt im
     * Datenbestand entweder seine eigene ID in {@code main_character_id} oder
     * gar keine. Und wer ueberhaupt nicht in {@code characters} steht - Blues,
     * Fremde, die ueber den Link beigetreten sind, in der Teilnahmeliste oft
     * als "Unknown Pilot 123" - hat keinen Main und bleibt seine eigene Zeile.
     * Ihn stillschweigend zu verschlucken oder unter irgendeinen Account zu
     * legen waere schlimmer, als ihn einzeln zu zaehlen.</p>
     */
    public List<Tuple> skelett(Instant von) {
        String sql = """
                SELECT e.id                AS "fleetId",
                       e.start_time        AS "startTime",
                       e.fc_character_id   AS "fcCharacterId",
                       e.fc_character_name AS "fcCharacterName",
                       e.tracking_type     AS "trackingType",
                       e.doctrine          AS "doctrine",
                       a.id                AS "attendanceId",
                       a.character_id      AS "characterId",
                       a.character_name    AS "characterName",
                       COALESCE(c.main_character_id, a.character_id) AS "accountId"
                FROM fleet_events e
                LEFT JOIN fleet_attendance a ON a.fleet_event_id = e.id
                LEFT JOIN characters c ON c.character_id = a.character_id
                WHERE e.start_time >= :von
                ORDER BY e.start_time, a.character_id, a.id
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("von", von);
        @SuppressWarnings("unchecked")
        List<Tuple> zeilen = q.getResultList();
        return zeilen;
    }

    /**
     * Zu den gezaehlten Accounts: der Name des Mains und wie viele Charaktere
     * das Auth ihnen zuordnet.
     *
     * <h3>Warum das eine zweite Abfrage ist und keine weitere Spalte</h3>
     * <p>Beides steht nicht im Skelett, weil beides nichts mit den Teilnahmen
     * im Fenster zu tun hat. Der Main kann in <em>keiner</em> Flotte gewesen
     * sein - dann steht sein Name in keiner Teilnahmezeile, und die Tafel
     * zeigte den Namen des Alts, der zufaellig zuerst beigetreten ist. Und die
     * Zahl der Charaktere zaehlt auch die, die nie geflogen sind; genau daran
     * haengt die Aussage, ob das Auth zu diesem Account ueberhaupt eine
     * Verknuepfung kennt. Der Vorbehalt aus dem Klassenkommentar - zwei
     * Abfragen, die dieselbe Zahl verschieden ausrechnen - greift hier nicht:
     * Diese hier rechnet keine Teilnahme aus, sie schlaegt Namen nach.</p>
     *
     * @param accountIds die Schluessel aus dem Skelett. Leer heisst: nichts
     *     nachzuschlagen - ein {@code IN ()} waere ein Syntaxfehler.
     */
    public List<Tuple> accounts(Collection<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT COALESCE(c.main_character_id, c.character_id) AS "accountId",
                       COUNT(*)                                      AS "charaktere",
                       MAX(CASE WHEN c.main_character_id IS NULL
                                  OR c.main_character_id = c.character_id
                                THEN c.name END)                     AS "mainName"
                FROM characters c
                WHERE COALESCE(c.main_character_id, c.character_id) IN (:ids)
                GROUP BY COALESCE(c.main_character_id, c.character_id)
                """;
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter("ids", accountIds);
        @SuppressWarnings("unchecked")
        List<Tuple> zeilen = q.getResultList();
        return zeilen;
    }

    // ==================================================================
    // Werte aus einer nativen Zeile
    // ==================================================================

    /**
     * Ein Zeitpunkt aus einer nativen Ergebniszeile.
     *
     * <p>Der Treiber liefert je nach Spaltentyp {@link Timestamp},
     * {@link OffsetDateTime} oder {@link LocalDateTime}; die Tests liefern
     * gleich einen {@link Instant}. Ohne diese Umwandlung an einer Stelle
     * stuende in jedem Aufrufer eine eigene Fallunterscheidung - und eine
     * davon waere irgendwann falsch.</p>
     *
     * <p>{@link LocalDateTime} wird als UTC gelesen: So legt Hibernate
     * {@code Instant}-Felder in {@code timestamp without time zone} ab. Waere
     * es die Serverzeit, verschoebe sich die ganze Auswertung um den
     * Zonenversatz.</p>
     */
    public static Instant zeitpunkt(Object wert) {
        return switch (wert) {
            case null -> null;
            case Instant instant -> instant;
            case Timestamp timestamp -> timestamp.toInstant();
            case OffsetDateTime odt -> odt.toInstant();
            case LocalDateTime ldt -> ldt.toInstant(ZoneOffset.UTC);
            default -> throw new IllegalArgumentException(
                    "Unerwarteter Zeittyp: " + wert.getClass().getName());
        };
    }

    /** Eine Ganzzahl aus einer nativen Ergebniszeile; {@code null} wird zu 0. */
    public static long zahl(Object wert) {
        return wert instanceof Number zahl ? zahl.longValue() : 0L;
    }

    /** Eine ID aus einer nativen Ergebniszeile; {@code null} bleibt {@code null}. */
    public static Long id(Object wert) {
        return wert instanceof Number zahl ? zahl.longValue() : null;
    }

    /** Ein Text aus einer nativen Ergebniszeile. */
    public static String text(Object wert) {
        return wert == null ? null : String.valueOf(wert);
    }
}
