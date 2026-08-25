package com.eve.own.auth.backend.common;

/**
 * Die eine Regel, die fuer jeden Marktpreis gilt: null ISK ist kein Preis.
 *
 * <p>Anlass ist ein Ausfall, der einen ganzen Tag lang unbemerkt blieb. Der
 * Preisanbieter antwortete mit HTTP 200 und formal einwandfreiem JSON, in dem
 * jede Zahl 0 war. Jackson las das sauber, die vorhandene Verteidigung
 * ({@code buy() != null}) prueft die Huelle und nicht den Wert, und so standen
 * 6.698 Nullen in {@code market_prices} - Tritanium eingeschlossen. Auf dem
 * Bildschirm wurde daraus eine Einkaufsliste, deren "Kosten" ausschliesslich
 * Fracht waren, ohne dass irgendetwas davon sichtbar gewesen waere.</p>
 *
 * <p>Der Grund, warum sich das ueberhaupt so auswirken konnte: ein fehlender
 * Preis ist etwas anderes als ein Preis von null. Ein Preis von null macht
 * Kaufen scheinbar kostenlos - er verfaelscht damit nicht nur eine Summe,
 * sondern jede Entscheidung, die auf einem Vergleich beruht: Kaufen gegen
 * Bauen, Mineral gegen Erz. Ein fehlender Preis dagegen laesst sich melden.</p>
 *
 * <p>In EVE existiert kein Marktpreis von 0 ISK. Es gibt keine Order zu null,
 * und ein Typ ohne Order hat keinen Preis - nicht den Preis null. Deshalb ist
 * das hier keine Auslegungsfrage, sondern eine Umrechnung: {@code <= 0} heisst
 * "unbekannt", und "unbekannt" heisst {@code null}.</p>
 *
 * <p><b>Abgrenzung.</b> Die Regel gilt fuer Preise, die in einen
 * <em>Vergleich</em> oder eine <em>Kaufentscheidung</em> eingehen. Sie gilt
 * nicht automatisch fuer eine Bestandsbewertung: dort ist "unbekannt zaehlt als
 * 0" vertretbar, weil eine zu niedrige Bestandssumme erkennbar eine Summe
 * bleibt und niemand danach einkauft. Jede Fundstelle ist einzeln entschieden
 * und an Ort und Stelle begruendet.</p>
 */
public final class MarketPriceRules {

    private MarketPriceRules() {
    }

    /**
     * Macht aus einem unbrauchbaren Preis ein ehrliches {@code null}.
     *
     * <p>Negativ wird mitgefangen, obwohl es das nicht geben duerfte: ein
     * Vorzeichenfehler in einer fremden Quelle waere sonst der einzige Weg, auf
     * dem ein Kauf Geld einbringt.</p>
     *
     * @return der Preis, oder {@code null} wenn er fehlt oder nicht positiv ist
     */
    public static Double usable(Double preis) {
        return preis == null || preis <= 0 ? null : preis;
    }

    /**
     * Ob ein Preis brauchbar ist.
     *
     * <p>Fuer Stellen, die nur fragen und nicht umrechnen wollen.</p>
     */
    public static boolean isUsable(Double preis) {
        return usable(preis) != null;
    }
}
