package com.eve.own.auth.backend.domain.fleet.dto;

import java.time.Instant;
import java.util.List;

/**
 * Die Datensaetze der FAT-Statistik.
 *
 * <h2>Die eine Regel, die dieser Aufbau erzwingt</h2>
 * <p>Es gibt in diesen Datensaetzen <b>keinen Prozentwert</b>. Jede Quote
 * verlaesst das Backend als {@link Anteil} - Zaehler und Nenner zusammen. Das
 * ist keine Umstaendlichkeit, sondern die Lehre aus zwei Fehlschlaegen dieses
 * Projekts: "100 % Teilnahme" bei einer einzigen Flotte ist keine Aussage,
 * sieht aber wie eine aus. Wer nur den Quotienten weitergibt, kann diesen Fall
 * im Frontend nicht mehr erkennen; wer Zaehler und Nenner weitergibt, muss
 * "7 von 9" schreiben und hat das Problem gar nicht erst.</p>
 *
 * <p>Deshalb hat {@link Anteil} bewusst <em>keine</em> Methode, die den
 * Quotienten ausrechnet. Es gibt keinen Weg, aus diesen Datensaetzen eine
 * nackte Prozentzahl zu bekommen.</p>
 *
 * <h2>Und die zweite: leer ist nicht null</h2>
 * <p>Reicht die Datenlage nicht, wird die Seite nicht mit Nullen gefuellt und
 * auch nicht weggelassen. {@link FatStatistik#auswertbar()} wird false, und
 * {@link FatStatistik#hinweis()} traegt einen fertigen Satz, der die
 * tatsaechliche Fallzahl und die noetige Schwelle beide ausspricht. Eine
 * ehrliche Luecke ist besser als eine erfundene Genauigkeit.</p>
 */
public class FleetStatisticsDtos {

    /**
     * Eine Quote, die ihren Nenner mitbringt.
     *
     * <p>Absichtlich ohne {@code prozent()}: siehe Klassenkommentar.</p>
     *
     * @param zaehler die Faelle, um die es geht
     * @param nenner die Faelle, aus denen sie stammen. Darf 0 sein - dann gab
     *     es nichts zu messen, und genau das soll dastehen.
     */
    public record Anteil(long zaehler, long nenner) {

        public static final Anteil LEER = new Anteil(0, 0);
    }

    // ==================================================================
    // Kopf
    // ==================================================================

    /**
     * Der tatsaechlich ausgewertete Zeitraum.
     *
     * @param tageGewaehlt was angefragt wurde
     * @param tageAusgewertet was daraus wurde - kleiner, wenn die Sicherung
     *     gegriffen hat
     * @param ersteFlotteInsgesamt Beginn der aeltesten Flotte ueberhaupt;
     *     {@code null}, wenn es nie eine gab
     * @param tageMitDaten wie weit die Historie wirklich zurueckreicht.
     *     {@code null}, wenn es keine Flotte gibt. Kleiner als
     *     {@code tageAusgewertet} heisst: der Zeitraum ist laenger als die
     *     Corporation.
     * @param gekuerzt ob das Fenster wegen der Datenmenge verkleinert wurde -
     *     ein stilles Abschneiden waere die schlechtere Luege
     * @param hinweis der Satz zur Spanne, falls einer noetig ist
     */
    public record Zeitraum(int tageGewaehlt, int tageAusgewertet,
                           Instant von, Instant bis,
                           Instant ersteFlotteInsgesamt, Integer tageMitDaten,
                           boolean gekuerzt, String hinweis) {}

    /**
     * Die haeufigste Doktrin-Angabe - mit allem Vorbehalt, den sie verdient.
     *
     * <p>{@code doctrine} ist ein freies Textfeld im Anlege-Formular, ohne
     * Verbindung zu den gepflegten Doktrinen. "Ferox", "ferox" und
     * "Ferox/Eagle" sind darin drei Doktrinen. Gezaehlt wird deshalb ueber
     * Kleinschreibung und ohne Randleerzeichen, angezeigt wird die
     * Schreibweise des ersten Vorkommens - und gereiht wird gar nicht, wenn zu
     * viele Flotten ueberhaupt keine Angabe tragen.</p>
     *
     * @param gereiht ob {@code haeufigste} ueberhaupt gefuellt ist
     * @param ohneAngabe wie viele Flotten kein Doktrinfeld ausgefuellt haben
     */
    public record DoktrinAngabe(boolean gereiht, String haeufigste, Anteil anteil,
                                long ohneAngabe, String hinweis) {}

    /**
     * Die Grundlage, auf die sich jede Zahl weiter unten bezieht.
     *
     * <h3>Warum hier zwei Zahlen stehen, wo frueher "Piloten" stand</h3>
     * <p>Die Tafel darunter zaehlt je Account. Damit wechselt die Einheit der
     * Kopfzahl, und eine Zahl, die ihre Einheit wechselt, ohne dass die
     * Beschriftung mitgeht, ist eine stille Falschaussage - "37 Piloten" waere
     * nach dem Umbau schlicht falsch. Beide Zahlen bleiben aber
     * aussagekraeftig, und keine ersetzt die andere: {@code accounts} ist der
     * Nenner, gegen den eine Teilnahmezahl gelesen wird ("4 von 12 Accounts"),
     * {@code charaktere} sagt, wie viele Bildschirme dahinterstehen. Stehen da
     * 12 Accounts und 31 Charaktere, ist das die Corporation, die multiboxt -
     * und genau das war der Grund, je Account zu zaehlen.</p>
     *
     * @param accounts verschiedene Accounts, die mindestens einmal dabei waren
     * @param charaktere verschiedene Charaktere derselben Teilnahmen
     * @param liveAnteil LIVE-Flotten von allen - der Vorbehalt zur Tafel
     *     darunter: Bei einer LINK-Flotte steht nur drin, wer den Link
     *     geklickt hat, bei einer LIVE-Flotte, wen ESI gesehen hat. Je kleiner
     *     dieser Anteil, desto mehr misst die Teilnahmezahl das Klicken und
     *     nicht das Fliegen.
     */
    public record Kopfzeile(long flotten, long accounts, long charaktere, long fcs,
                            Anteil liveAnteil, DoktrinAngabe doktrin) {}

    // ==================================================================
    // Teilnahme je Account
    // ==================================================================

    /**
     * Eine Zeile des Nachschlagewerks - <b>ein Account</b>, nicht ein Charakter.
     *
     * <p>Wer drei Alts in dieselbe Flotte bringt, war einmal dabei. Sonst
     * misst die Tafel Multiboxing statt Beteiligung, und die Rangfolge waere
     * die Anzahl der Bildschirme.</p>
     *
     * <p>Bewusst <b>ohne Quote</b>. Ein Pilot, der vor drei Wochen beigetreten
     * ist, kann keine 40 Flotten haben; jeder Nenner waere entweder unfair
     * (die Gesamtzahl des Fensters) oder zirkulaer (ab seiner ersten Flotte).
     * Absolute Zahl plus erste und letzte Flotte - dann sieht der Leser in
     * einer Sekunde selbst, ob "4" wenig ist oder alles, was in zwei Wochen
     * ging.</p>
     *
     * @param accountId der Main - oder der Charakter selbst, wenn das Auth
     *     keinen Main zu ihm kennt
     * @param name der Name des <em>Mains</em>. Er kommt aus {@code characters}
     *     und nicht aus den Teilnahmezeilen, weil der Main selbst nie
     *     mitgeflogen sein muss - sonst stuende hier der Name des Alts, der
     *     zufaellig zuerst beigetreten ist.
     * @param charaktere wer aus diesem Account tatsaechlich geflogen ist, nach
     *     Namen sortiert. Ohne diese Liste kann ein Direktor nicht pruefen, ob
     *     die Zuordnung stimmt - und eine Gruppierung, die sich nicht pruefen
     *     laesst, muss man glauben.
     * @param verbunden ob das Auth zu diesem Account ueberhaupt eine
     *     Verknuepfung kennt. False heisst: ein einzelner Charakter ohne
     *     CharLink - moeglicherweise ein Einzelchar-Spieler, moeglicherweise
     *     ein nicht verknuepfter Alt, der hier faelschlich eine eigene Zeile
     *     bekommt. Siehe {@link Teilnahme#ohneVerbindung()}.
     */
    public record AccountZeile(Long accountId, String name, List<String> charaktere,
                               long flotten, Instant ersteFlotte, Instant letzteFlotte,
                               boolean verbunden) {}

    /**
     * @param zeilen Accounts mit mindestens zwei Flotten, nach Namen sortiert.
     *     Nach Flottenzahl absteigend waere eine Bestenliste, und eine
     *     Bestenliste hat immer ein unteres Ende.
     * @param einmalige Accounts mit genau einer Flotte - getrennt, damit die
     *     Tabelle nicht zur Haelfte aus Gaesten besteht, die einmal
     *     mitgeflogen sind
     * @param ohneVerbindung wie viele der gezaehlten Accounts das Auth als
     *     einzelnen Charakter ohne Verknuepfung kennt, von allen. <b>Die Zahl,
     *     ohne die diese Seite genauer aussieht, als sie ist:</b> Die
     *     Gruppierung ist nur so gut wie die CharLink-Daten. Ein Alt, der
     *     nicht mit seinem Main verbunden ist, zaehlt weiterhin einzeln - und
     *     dann stimmt die Tafel genau fuer die Leute nicht, um deretwillen sie
     *     umgebaut wurde.
     * @param hinweis der fertige Satz dazu, damit dieselbe Aussage nicht im
     *     Frontend ein zweites Mal zusammengesetzt wird und auseinanderdriftet
     */
    public record Teilnahme(List<AccountZeile> zeilen, List<AccountZeile> einmalige,
                            Anteil ohneVerbindung, String hinweis) {}

    // ==================================================================
    // Das Ganze
    // ==================================================================

    /**
     * @param auswertbar ob im Fenster ueberhaupt genug Flotten liegen. Ist es
     *     false, steht nur die Kopfzeile mit absoluten Zahlen da und ein Satz,
     *     warum die Tafel darunter nichts behauptet. Die Tafel selbst wird
     *     trotzdem gefuellt - sie zaehlt nur, sie reiht nicht.
     */
    public record FatStatistik(Zeitraum zeitraum, Kopfzeile kopf, boolean auswertbar,
                               String hinweis, Teilnahme teilnahme) {}
}
