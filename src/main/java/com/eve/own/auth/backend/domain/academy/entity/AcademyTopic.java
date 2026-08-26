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
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * Ein Schulungsthema der Academy samt Lehrplan.
 *
 * <p>Das Thema ist die eine Seite des Nachfrage-Boards, die Bekundung
 * ({@link AcademyInterest}) die andere. Zwischen beiden gibt es <b>keine</b>
 * JPA-Beziehung - die Bekundung zeigt mit blanker {@code topic_id} hierher, wie
 * {@code AuthGroupRequest.groupId} auf die Gruppe. Der Preis steht im
 * {@code AcademyInterestRepository}: beim Loeschen eines Themas raeumt der
 * Dienst die Bekundungen selbst ab, kein Fremdschluessel tut das fuer ihn.</p>
 *
 * <p>Es wird nichts genehmigt und nichts terminiert. Ein Thema sammelt
 * Interesse ein und macht es ablesbar; der Termin entsteht wie bisher in
 * Discord.</p>
 */
@Entity
@Table(name = "academy_topics")
@Getter
@Setter
public class AcademyTopic {

    /** Der Titel ist zugleich der Wiedererkennungswert - laenger wird er nicht lesbar. */
    public static final int MAX_TITLE_LENGTH = 120;

    /** Die Einzeilerzeile der Liste; was hier nicht hineinpasst, gehoert in den Lehrplan. */
    public static final int MAX_SUMMARY_LENGTH = 200;

    /**
     * Obergrenze des Lehrplans.
     *
     * <p>{@code text} ist in Postgres praktisch unbegrenzt (1 GB). Unbegrenzte
     * Nutzereingabe, die anschliessend in einer API-Antwort landet, ist ein
     * Fussschuss - 20 000 Zeichen sind fuer einen Lehrplan reichlich. Geprueft
     * wird im Dienst von Hand: das Projekt hat keine Bean Validation.</p>
     */
    public static final int MAX_DESCRIPTION_LENGTH = 20_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Anzeigename des Themas, z.B. "EWar Grundlagen". */
    @Column(nullable = false, unique = true, length = MAX_TITLE_LENGTH)
    private String title;

    /**
     * Die eine Zeile, die in der eingeklappten Karte unter dem Titel steht.
     *
     * <p>Ein eigenes Feld und nicht der erste Absatz des Lehrplans: die
     * Themenliste liefert {@link #description} bewusst nicht mit (sonst gingen
     * bei zwoelf Themen zwoelf Lehrplaene ueber die Leitung, jedes Mal). Ohne
     * diese Zeile waere die eingeklappte Karte leer bis auf den Titel.</p>
     */
    @Column(nullable = false, length = MAX_SUMMARY_LENGTH)
    private String summary;

    /**
     * Der Lehrplan als Markdown.
     *
     * <p>{@code columnDefinition = "TEXT"} steht hier ab der ersten Fassung, und
     * das ist kein Feinschliff: {@code ddl-auto=update} vergleicht bestehende
     * Spaltentypen <b>nie</b>. Wer das Feld erst als schlichtes {@code String}
     * ausliefert, hat fuer immer ein {@code varchar(255)}, und ein spaeteres
     * Nachruesten dieser Zeile bewirkt <b>nichts</b> - dann braeuchte es eine
     * eigene Wanderungsklasse nach dem Muster von
     * {@code MiningMoneyColumnMigration}. Eine Einzeile spart eine ganze Klasse.</p>
     *
     * <p>Nullbar: ein frisch angelegtes Thema darf zunaechst nur Titel und
     * Kurzzeile haben. Ein nachtraegliches {@code nullable = false} scheitert
     * unter {@code ddl-auto=update} an einer bereits gefuellten Tabelle - die
     * Entscheidung faellt hier und bleibt.</p>
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Ob das Thema im Reiter "Themen" erscheint.
     *
     * <p>Statt zu loeschen: ein abgeschaltetes Thema behaelt seine Bekundungen,
     * ein geloeschtes verliert sie. Wer ein Thema nur pausieren will, soll die
     * Nachfrage nicht wegwerfen muessen.</p>
     */
    @Column(nullable = false)
    private boolean active = true;

    /** Von Hand im Dienst gesetzt - das Projekt kennt kein {@code @CreationTimestamp}. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Wer zuletzt geschrieben hat - die Account-/Main-ID, nicht der Charakter.
     *
     * <p>Nullbar, weil die Zeilen aus der Zeit vor diesem Feld keinen Wert
     * haetten und eine erfundene ID schlechter waere als eine fehlende.</p>
     */
    @Column(name = "updated_by_account_id")
    private Long updatedByAccountId;

    /**
     * Welche Rollen dieses Thema halten duerfen - und wer damit die Namen der
     * Interessenten sieht.
     *
     * <p>Aufbau woertlich wie {@code AuthGroup.leaderRoleNames}: eigene Tabelle,
     * EAGER, direkt initialisiert, damit die Menge nie {@code null} ist. Sie
     * wird bei jeder Sichtkreispruefung gebraucht; LAZY erzwaenge dafuer eine
     * offene Sitzung und eine Nachfrage je Thema.</p>
     *
     * <p><b>Die leere Menge ist der tragende Fall:</b> sie heisst "Rueckfall auf
     * den festen Autorenkreis" - nicht "niemand" und schon gar nicht "jeder".
     * Beide Missdeutungen sind je eine Zeile Code entfernt und beide waeren
     * falsch: "niemand" naehme der Fuehrung die Einsicht in ein Thema, das
     * jemand ohne Ausbilderrolle angelegt hat, "jeder" gaebe die Namensliste an
     * jeden Angemeldeten. Der Dienst prueft deshalb den Autorenkreis
     * <em>unabhaengig</em> von dieser Menge; {@code anyMatch} beantwortet die
     * leere Menge von selbst mit {@code false}.</p>
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "academy_topic_teacher_roles",
            joinColumns = @JoinColumn(name = "topic_id"))
    private Set<String> teacherRoleNames = new HashSet<>();
}
