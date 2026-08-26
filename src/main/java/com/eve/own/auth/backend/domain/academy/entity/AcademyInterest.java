package com.eve.own.auth.backend.domain.academy.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * "Ich haette Interesse an diesem Thema, und zwar dann."
 *
 * <p><b>Kein {@code status}-Feld, und das ist eine Entscheidung.</b>
 * {@code AuthGroupRequest} traegt eines, weil dort eine Rolle vergeben wird und
 * die Zeile hinterher der einzige Herkunftsnachweis ist. Hier wird nichts
 * vergeben und nichts entschieden. Eine Interessensbekundung ist ein Signal,
 * kein Antrag. Ein {@code PENDING}/{@code APPROVED} daran wuerde einen
 * Genehmigungsvorgang erfinden, den es fachlich nicht gibt - und die Oberflaeche
 * wuerde ihn dann auch anzeigen, mit der Folge, dass jemand auf eine Antwort
 * wartet, die nie kommt. <b>Zuruecknehmen heisst: die Zeile loeschen.</b> Wer
 * hier spaeter ein Zustandsfeld nachruestet, baut den Vorgang ein, den dieses
 * Feature ausdruecklich nicht hat.</p>
 *
 * <p>Die Zeile haengt am <b>Account</b> (Main), nicht am einzelnen Charakter -
 * und zwar zwangslaeufig: das JWT traegt immer {@code Character.getAccountId()},
 * ein einzelner Alt ist ueber die Sitzung gar nicht adressierbar. Fachlich ist
 * es ohnehin richtig: am Bildschirm sitzt ein Mensch, und der kann am Dienstag
 * um 20 Uhr genau einmal. Zwei Bekundungen desselben Accounts an demselben
 * Thema waeren Doppelzaehlung - genau das, was das Nachfragebild kaputt macht.
 * Dagegen steht der Unique-Constraint unten.</p>
 */
@Entity
@Table(name = "academy_interests", uniqueConstraints = {
        // Ein Mensch, ein Interesse je Thema. Der Dienst sucht zwar vor dem
        // Speichern nach einer bestehenden Zeile und schreibt sie um - aber
        // zwei gleichzeitige Aufrufe desselben Accounts saehen beide "keine
        // Zeile da" und legten zwei an. Die Pruefung im Dienst gibt es fuer die
        // lesbare Wirkung, den Constraint gegen das Wettrennen.
        @UniqueConstraint(columnNames = {"topic_id", "account_id"})
})
@Getter
@Setter
public class AcademyInterest {

    /**
     * Die Zeitfenster, verankert an EVE-Zeit = UTC.
     *
     * <p>Als Zeichenketten-Konstanten und nicht als {@code @Enumerated}: das
     * Projekt kennt {@code @Enumerated} an keiner einzigen Stelle und legt
     * Zustaende durchgehend als {@code String}-Spalte mit Konstanten ab
     * ({@code AuthGroupRequest.STATUS_*}, {@code industry_orders.status}). Die
     * Pruefschaerfe eines Enums holt der Dienst sich an der Grenze zurueck,
     * indem er jeden hereinkommenden Wert gegen {@link #TIME_WINDOWS} haelt.</p>
     *
     * <p><b>Die Uhrzeiten sind Anzeige, nicht Datum.</b> Gespeichert wird
     * {@code EU_PRIME}, nicht {@code 19:00}. Wer die Grenzen spaeter verschiebt,
     * aendert einen Beschriftungstext in der Oberflaeche und wandert keine
     * Daten.</p>
     */
    public static final String WINDOW_AUTZ = "AUTZ";

    /** EU frueh, 16-19 UTC. */
    public static final String WINDOW_EU_EARLY = "EU_EARLY";

    /** EU Prime, 19-22 UTC. */
    public static final String WINDOW_EU_PRIME = "EU_PRIME";

    /** USTZ, 22-03 UTC - laeuft ueber Mitternacht, deshalb die groesste Spanne. */
    public static final String WINDOW_USTZ = "USTZ";

    /**
     * Wochenende tagsueber (Sa/So).
     *
     * <p>Das fuenfte Fenster faellt aus der Reihe: die anderen vier schneiden den
     * Tag, dieses schneidet die Woche. Es steht trotzdem in derselben Liste,
     * weil die Bekundung damit ohne Kreuztabelle auskommt - "Sa, So + Wochenende
     * tagsueber" sagt genau das, was gemeint ist, und kostet zwei Klicks statt
     * eines Rasters mit 35 Kaestchen, das niemand ausfuellt.</p>
     */
    public static final String WINDOW_WEEKEND_DAY = "WEEKEND_DAY";

    /**
     * Alle gueltigen Fenster in Anzeigereihenfolge.
     *
     * <p>Eine {@code List} und keine {@code Set}: die Reihenfolge ist die des
     * Tages und damit Teil der Aussage. Sie steuert zugleich die Reihenfolge der
     * Verteilung im Datensatz - eine alphabetisch sortierte Verteilung
     * ({@code AUTZ, EU_EARLY, EU_PRIME, USTZ, WEEKEND_DAY} waere hier zufaellig
     * richtig, bei umbenannten Fenstern aber nicht mehr) waere fuer das Auge
     * wertlos.</p>
     */
    public static final List<String> TIME_WINDOWS = List.of(
            WINDOW_AUTZ, WINDOW_EU_EARLY, WINDOW_EU_PRIME, WINDOW_USTZ, WINDOW_WEEKEND_DAY);

    /** Freitext faengt auf, was das Fensterraster nicht abbildet - kurz gehalten. */
    public static final int MAX_NOTE_LENGTH = 280;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Das Thema - eine blanke ID, keine JPA-Beziehung.
     *
     * <p>Wie {@code AuthGroupRequest.groupId}. Es gibt deshalb keinen
     * Fremdschluessel, der diese Zeile beim Loeschen des Themas mitnaehme; das
     * erledigt {@code AcademyService.deleteTopic} von Hand.</p>
     */
    @Column(name = "topic_id", nullable = false)
    private Long topicId;

    /**
     * Der Account (Main-Charakter-ID), der Interesse bekundet hat.
     *
     * <p>Gefuellt aus {@code CurrentUser.characterId()} - <b>nie</b> aus dem
     * Request. Siehe den Klassenkommentar oben und
     * {@code AcademyDtos.SaveInterestDto}.</p>
     */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** "Was du dir davon erhoffst" - optional. */
    @Column(length = MAX_NOTE_LENGTH)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Wann zuletzt geaendert.
     *
     * <p>Traegt auch die Antwort auf "wie frisch ist diese Nachfrage" - ein
     * Interesse von vor acht Monaten zaehlt heute in derselben Zahl wie eines
     * von gestern. Ausgewertet wird das noch nicht; das Feld liegt vor, damit
     * es sich spaeter ohne Wanderung auswerten laesst.</p>
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Die Wochentage als Namen von {@link java.time.DayOfWeek} ({@code MONDAY}
     * bis {@code SUNDAY}).
     *
     * <p>Zwei getrennte Sammlungen fuer Tage und Fenster, nicht eine gekreuzte:
     * "Ich kann Di, Do, So - jeweils EU Prime" sind 4 Klicks, ein Raster Tag x
     * Fenster waeren 35 Kaestchen. Was dabei verloren geht, sei offen gesagt:
     * wer freitags spaet und sonntags frueh kann, kann das hier nicht
     * ausdruecken - dafuer gibt es {@link #note}. Ein Signal, das 90 % der Leute
     * ausfuellen, schlaegt ein exaktes, das 30 % ausfuellen.</p>
     *
     * <p>Geprueft wird im Dienst ueber {@code DayOfWeek.valueOf(...)}: die
     * Pruefschaerfe des Enums an der Grenze, der Stil des Projekts in der
     * Tabelle. In der Datenbank steht damit {@code MONDAY} und nicht {@code 9}
     * oder eine Bitmaske - eine Auswertung bleibt ein {@code GROUP BY} statt
     * Bitarithmetik.</p>
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "academy_interest_days",
            joinColumns = @JoinColumn(name = "interest_id"))
    @Column(name = "weekday", nullable = false, length = 16)
    private Set<String> weekdays = new HashSet<>();

    /** Die Zeitfenster - Werte aus {@link #TIME_WINDOWS}, geprueft im Dienst. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "academy_interest_windows",
            joinColumns = @JoinColumn(name = "interest_id"))
    @Column(name = "time_window", nullable = false, length = 16)
    private Set<String> timeWindows = new HashSet<>();
}
