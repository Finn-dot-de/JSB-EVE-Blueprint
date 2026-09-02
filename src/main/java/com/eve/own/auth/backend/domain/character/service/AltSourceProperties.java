package com.eve.own.auth.backend.domain.character.service;

import java.math.BigDecimal;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Die Stellschrauben der vier neuen Datenquellen, konfigurierbar ueber
 * {@code eve.alt-sources.*}.
 *
 * <h2>Warum jede Erfassung einen eigenen Schalter hat</h2>
 * <p>Diese vier Quellen legen <b>personenbezogene Bewegungsdaten</b> an: mit wem
 * jemand Geld tauscht, wen er in seiner Kontaktliste fuehrt, mit wem er
 * schreibt, wann und wo er online war. Das ist etwas anderes als eine
 * Skillpunktzahl. Wer so etwas erhebt, muss es auch abstellen koennen - und
 * zwar einzeln, denn die vier sind unterschiedlich eingriffstief: eine
 * Kontaktliste ist eine bewusste Eintragung des Spielers, eine
 * Anwesenheitsreihe ist es nicht.</p>
 *
 * <p>Vorgabe ist ueberall <b>an</b>. Der Grund: die Quellen sind zu genau dem
 * Zweck gebaut, dem die Anwendung dient, und eine Erfassung, die standardmaessig
 * aus ist, ist in der Praxis eine, die nie laeuft und deren Ausfall niemand
 * bemerkt. Wer sie nicht will, schaltet sie mit einer Zeile ab.</p>
 *
 * <p>Bauart und Begruendungsstil folgen {@link AltDetectionProperties}: Klasse
 * mit Feldern statt Record, damit jede Begruendung direkt an ihrem Wert steht
 * statt in einem Block aus {@code @param}-Zeilen.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "eve.alt-sources")
public class AltSourceProperties {

    // ==================================================================
    // 1. ISK-Transfers
    // ==================================================================

    /**
     * Ob Spieler-Ueberweisungen aus dem Wallet-Journal festgehalten werden.
     *
     * <p>Diese Erfassung kostet <b>keinen einzigen zusaetzlichen ESI-Aufruf</b>:
     * das Journal wird fuer Kopfgelder und Steuerzahlungen ohnehin gelesen, der
     * bisherige Code hat die Gegenpartei nur weggeworfen. Abschalten spart also
     * nichts an Last, sondern ausschliesslich an gespeicherten Daten - und genau
     * deshalb ist der Schalter da.</p>
     */
    private boolean iskTransfersEnabled = true;

    /**
     * Betraege unterhalb dieser Grenze werden nicht festgehalten.
     *
     * <p><b>Vorgabe 0, also kein Filter</b>, und das ist eine bewusste
     * Entscheidung gegen die naheliegende Alternative. Eine Untergrenze wirkt
     * aufraeumend, verwirft aber lautlos Daten, die die Bewertung spaeter
     * braucht - und niemand kann hinterher sagen, was fehlt. Ob eine
     * 1-ISK-Ueberweisung Rauschen oder ein Testtransfer zwischen zwei eigenen
     * Charakteren ist, entscheidet die Bewertung besser als die Erfassung.
     * Hochdrehen kann, wer die Tabelle klein halten will.</p>
     */
    private BigDecimal iskTransferMinAmount = BigDecimal.ZERO;

    /**
     * Wie lange Ueberweisungen aufbewahrt werden.
     *
     * <p>Dieselbe Frist wie bei der Anwesenheit, und aus demselben Grund: ESI
     * gibt rund dreissig Tage Journal heraus, aber die Tabelle sammelt ueber
     * Monate. Ohne Frist waere sie die einzige der vier, die unbegrenzt waechst.</p>
     *
     * <p>Null oder negativ heisst <b>nicht loeschen</b>, nicht "alles loeschen".
     * Die umgekehrte Lesart waere ein Fussangel: ein versehentlicher Nullwert
     * wuerde beim naechsten naechtlichen Lauf den ganzen Bestand abraeumen.</p>
     */
    private Duration iskTransferRetention = Duration.ofDays(90);

    // ==================================================================
    // 2. Kontakte
    // ==================================================================

    /** Ob die Kontaktlisten registrierter Charaktere gespiegelt werden. */
    private boolean contactsEnabled = true;

    // ==================================================================
    // 3. Mail-Anzahl
    // ==================================================================

    /**
     * Ob Nachrichten <em>gezaehlt</em> werden. Mehr passiert hier nicht - siehe
     * die Zusage an {@code CharacterMailCount}.
     */
    private boolean mailEnabled = true;

    /**
     * Nachrichten mit mehr Empfaengern als diesem Wert zaehlen nicht mit.
     *
     * <p>Das ist die zweite tragende Regel der Alt-Erkennung, angewandt auf
     * Post: ein Merkmal, das alle Corp-Mitglieder teilen, ist kein
     * Fingerabdruck. Eine Rundmail an vierzig Leute verbindet niemanden mit
     * niemandem, wuerde aber vierzig Paare erzeugen - und zwar genau die Paare,
     * die ohnehin in derselben Corporation sind. Beim gemeinsamen Mining-Tag
     * hat dieser Fehler die Werte gemessen invertiert.</p>
     *
     * <p>Fuenf ist die Grenze zwischen "hat sich an jemanden gewandt" und
     * "hat verteilt". Hochdrehen holt Verteiler herein, runterdrehen verliert
     * die kleine Gruppe, in der ein Alt mitgemeint war.</p>
     */
    private int mailMaxRecipients = 5;

    // ==================================================================
    // 4. Anwesenheit
    // ==================================================================

    /**
     * Ob Standort und Ein-/Ausloggzeiten der Corp-Mitglieder mitgeschrieben
     * werden.
     *
     * <p>Von den vieren die eingriffstiefste Erfassung, weil sie als einzige
     * eine <em>Reihe</em> ueber die Zeit anlegt und als einzige auch Mitglieder
     * erfasst, die sich nie bei dieser Anwendung angemeldet haben. Sie ist
     * trotzdem vorgabemaessig an, weil sie das einzige Signal ist, das
     * unregistrierte Charaktere untereinander verbinden kann - und weil die
     * Aufbewahrungsfrist darunter wirklich laeuft.</p>
     */
    private boolean presenceEnabled = true;

    /**
     * Wie lange die Anwesenheitsaufzeichnung aufbewahrt wird.
     *
     * <p>Die vom Nutzer festgelegten 90 Tage. Sie stehen hier und nicht als
     * Konstante im Dienst, weil eine Aufbewahrungsfrist eine Zusage an die
     * Betroffenen ist und keine Implementierungsentscheidung.</p>
     *
     * <p>Null oder negativ heisst auch hier <b>nicht loeschen</b>.</p>
     */
    /**
     * Wie lange Kontaktlisten und Nachrichtenanzahlen aufgehoben werden.
     *
     * <p>Noetig, obwohl jeder Lauf die Zeilen eines Charakters ersetzt: das
     * geschieht <em>je Charakter</em> und nur, wenn er im Lauf vorkommt. Wer
     * sein Token entzieht, wessen Token ungueltig wird oder fuer wen die Quelle
     * abgeschaltet ist, faellt heraus - seine Zeilen blieben ohne diese Frist
     * fuer immer liegen, und die Zusage auf der Oberflaeche waere unwahr.</p>
     *
     * <p><b>Hoeher:</b> Momentaufnahmen ausgeschiedener Mitglieder ueberdauern
     * laenger. <b>Niedriger:</b> ein Charakter, der eine Weile nicht
     * synchronisiert wurde, verliert seine Daten und das Signal faellt fuer ihn
     * auf "nicht verfuegbar" - was ehrlicher ist als ein veralteter Wert.
     * <b>Null:</b> es wird NICHTS geloescht.</p>
     */
    private Duration snapshotRetention = Duration.ofDays(90);

    private Duration presenceRetention = Duration.ofDays(90);

    public Duration getSnapshotRetention() {
        return snapshotRetention;
    }

    public void setSnapshotRetention(Duration snapshotRetention) {
        this.snapshotRetention = snapshotRetention;
    }
}
