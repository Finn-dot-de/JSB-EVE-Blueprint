package com.eve.own.auth.backend.domain.mining.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Wer wem wann wieviel ISK gutgeschrieben hat.
 *
 * <p>Diese Tabelle ist die einzige Stelle der Anwendung, an der ein Mensch
 * einem anderen Geld zuspricht. Alles Uebrige rund um die Mining-Steuer
 * <em>rechnet</em> nur: die Schuld faellt aus Menge, Preis und Satz, die
 * Zahlung faellt aus dem Wallet-Journal. Hier entscheidet jemand. Deshalb ist
 * der Aufbau bewusst der von {@code RoleAssignmentAudit} - Buchung und Nachweis
 * sind dasselbe Objekt und nicht zwei, die auseinanderlaufen koennen.</p>
 *
 * <h2>Der Betrag ist {@link BigDecimal}, und das ist ein Stilbruch</h2>
 * <p>Jeder andere ISK-Betrag im Projekt liegt als {@code Double} in einer
 * {@code double precision}-Spalte: {@code MiningTaxInvoice.totalTax},
 * {@code CharacterActivity.value}, {@code CharacterStats.walletBalance},
 * {@code MarketPrice.jitaBuy}. Der Schaden ist im Bestand schon sichtbar - die
 * Summe der PVE-ISK steht mit {@code 1319981075.6900005} in der Datenbank,
 * Nachkommastellen, die keine Zahlung je hatte. Bei einer <em>gerechneten</em>
 * Zahl ist das haesslich; bei einer <em>zugesagten</em> waere es falsch. Wer
 * 12.500.000.000,01 ISK gutschreibt, muss 12.500.000.000,01 wiederfinden.</p>
 *
 * <p>{@code numeric(20,2)}: zwei Nachkommastellen, weil ISK ingame genau zwei
 * hat, und zwanzig Stellen, weil damit jeder Betrag bis 1e18 exakt bleibt -
 * weit ueber allem, was in EVE existiert. Vorbild fuer die Haltung ist
 * {@code IndustryMath}, das seine Begruendung fuer {@code BigDecimal} schon
 * ausformuliert hat; nur wird dort ausschliesslich gerechnet und nichts
 * gespeichert. Ein Vorbild fuer die Spalte gab es nicht.</p>
 *
 * <h2>Zuruecknehmen heisst gegenbuchen, nicht loeschen</h2>
 * <p>Eine Gutschrift wird nie geloescht und nie im Betrag geaendert. Wird sie
 * zurueckgenommen, entsteht eine <b>zweite</b> Zeile ueber den negativen Betrag
 * mit {@link #reversalOfCreditId} auf die erste; die erste wechselt auf
 * {@link #STATUS_REVERSED} und bleibt sonst unangetastet. Die urspruengliche
 * Buchung bleibt damit vollstaendig lesbar - Betrag, Handelnder, Zeitpunkt und
 * Grund von damals - und daneben steht, wer sie wann und warum kassiert hat.
 * Ein {@code UPDATE} des Betrags haette beides verloren.</p>
 *
 * <h2>Warum drei Zustaende und nicht zwei</h2>
 * <p>Die Gegenbuchung traegt einen <em>eigenen</em> Zustand
 * {@link #STATUS_REVERSAL} statt einfach {@link #STATUS_ACTIVE}. Das ist kein
 * Schmuck, sondern die Absicherung der Summenbildung. Es gilt:</p>
 * <pre>Summe ueber ALLE Zeilen == Summe ueber die Zeilen mit STATUS_ACTIVE</pre>
 * <p>Ein zurueckgenommenes Paar besteht aus {@code (+x, REVERSED)} und
 * {@code (-x, REVERSAL)}. Wer alles summiert, hebt das Paar rechnerisch auf;
 * wer auf {@code ACTIVE} filtert, laesst beide Haelften weg. Beide Wege liefern
 * dieselbe Zahl. Bekaeme die Gegenbuchung stattdessen {@code ACTIVE}, waere ein
 * Filter auf {@code ACTIVE} stillschweigend falsch: die Belastung bliebe drin,
 * die Gutschrift fiele raus, und der Saldo des Mitglieds ruschte um {@code x}
 * ins Minus. Genau diese Art Fehler faellt bei Geld erst auf, wenn sich jemand
 * beschwert.</p>
 *
 * <p>Der Zustand steht als Zeichenkette und nicht als {@code enum} - dieselbe
 * Ueberlegung wie bei {@code RoleAssignmentAudit.action}: die Werte liegen in
 * der Datenbank und lassen sich so ohne Wanderung des Schemas erweitern.</p>
 */
@Entity
@Table(name = "mining_tax_credits",
        indexes = {
                // Der haeufigste Zugriff ist "der Verlauf dieses einen Accounts",
                // und die Tabelle waechst nur - sie wird nie aufgeraeumt.
                @Index(name = "idx_mining_credit_account", columnList = "account_id"),
                @Index(name = "idx_mining_credit_occurred", columnList = "occurred_at")
        },
        uniqueConstraints = {
                // Eine Buchung laesst sich genau einmal zurueckziehen. Ohne diese
                // Bedingung wuerden zwei gleichzeitige Ruecknahmen zwei
                // Gegenbuchungen erzeugen und der Account bekaeme den Betrag
                // doppelt abgezogen. NULL ist in Postgres beliebig oft erlaubt,
                // die normalen Buchungen stoeren sich also nicht daran.
                @UniqueConstraint(name = "uk_mining_credit_reversal",
                        columnNames = "reversal_of_credit_id")
        })
@Getter
@Setter
public class MiningTaxCredit {

    /** Eine gueltige Gutschrift oder Belastung. */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** Eine Buchung, die durch eine Gegenbuchung aufgehoben wurde. */
    public static final String STATUS_REVERSED = "REVERSED";

    /** Die Gegenbuchung selbst - siehe die Erlaeuterung zur Summenbildung oben. */
    public static final String STATUS_REVERSAL = "REVERSAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Der Account, dem der Betrag zugute kommt - nicht der einzelne Charakter.
     *
     * <p>Anders als bei den Rollen, die am Charakter haengen: die Steuerschuld
     * wird ueber Main und Alts zusammen gefuehrt ({@code MiningTaxInvoice} tut
     * es genauso), und eine Gutschrift, die nur einem Alt gaelte, liesse sich
     * gegen diese Schuld gar nicht verrechnen. Es ist die Account-ID aus
     * {@code Character.getAccountId()}.</p>
     */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /**
     * Der Betrag in ISK, positiv bei einer Gutschrift, negativ bei einer
     * Gegenbuchung.
     *
     * <p>Kein {@code double} - siehe die Begruendung im Klassenkommentar.</p>
     */
    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;

    /** ACTIVE, REVERSED oder REVERSAL - siehe die Konstanten oben. */
    @Column(nullable = false, length = 16)
    private String status;

    /**
     * Bei einer Gegenbuchung die ID der Buchung, die sie aufhebt; sonst
     * {@code null}.
     *
     * <p>Als blanke ID und nicht als Fremdschluessel-Beziehung: dieselbe
     * Ueberlegung wie beim Rollennamen in {@code RoleAssignmentAudit}, ein
     * Nachweis soll nicht davon abhaengen, dass die Gegenseite noch existiert.
     * Geloescht wird hier zwar ohnehin nichts, aber die Zusicherung soll nicht
     * an einer Einstellung des Mappings haengen.</p>
     */
    @Column(name = "reversal_of_credit_id")
    private Long reversalOfCreditId;

    /** Wer gehandelt hat. Ohne dieses Feld waere die ganze Zeile wertlos. */
    @Column(name = "actor_character_id", nullable = false)
    private Long actorCharacterId;

    /**
     * Ob sich der Handelnde selbst Geld zugesprochen hat.
     *
     * <p>Eigene Spalte, obwohl aus {@link #accountId} und
     * {@link #actorCharacterId} ableitbar - genau wie
     * {@code RoleAssignmentAudit.selfAssigned}, und aus demselben Grund: das ist
     * der Fall, den jemand spaeter <em>suchen</em> wird. Als Spaltenvergleich
     * muesste er wissen, dass es ihn gibt; als Kennzeichen faellt er in jeder
     * Liste von selbst auf.</p>
     *
     * <p>Verboten ist die Selbstvergabe nicht. Das Leadership schuerft selbst und
     * hat denselben Anspruch auf eine Gutschrift wie jeder andere; eine Sperre
     * hier wuerde den Vorgang nur auf einen Weg ohne Nachweis draengen - der
     * Director bittet dann einen zweiten Director. Sichtbar muss er sein, nicht
     * unmoeglich.</p>
     */
    @Column(name = "self_granted", nullable = false)
    private boolean selfGranted;

    /**
     * Warum - freiwillige Angabe des Handelnden, {@code null} wenn er keine
     * machte.
     *
     * <p>Bewusst kein Pflichtfeld, dieselbe Ueberlegung wie bei
     * {@code RoleAssignmentAudit.reason}: ein erzwungener Grund wird zu "x", und
     * ein Protokoll voller Platzhalter ist schlechter als eines, in dem das
     * Fehlen der Angabe ehrlich sichtbar bleibt.</p>
     */
    @Column(length = 500)
    private String reason;

    /** Wann. Ohne Zeitpunkt liesse sich die Reihenfolge zweier Buchungen nicht sagen. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
