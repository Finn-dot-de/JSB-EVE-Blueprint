package com.eve.own.auth.backend.domain.market;

import java.util.Locale;
import java.util.Map;

/**
 * Entscheidet, ob ein <em>Kaufgebot</em> die Zielstation erreicht.
 *
 * <p><b>Warum es diese Klasse ueberhaupt gibt.</b> Der Marktabzug hat beide
 * Seiten des Orderbuchs gleich behandelt und auf {@code location_id ==
 * Zielstation} gefiltert. Fuer Verkaufsangebote ist das richtig, fuer
 * Kaufgebote falsch - und der Unterschied ist keine Feinheit, sondern eine
 * Regel des Spiels: ein Verkaufsangebot liegt in einer Kiste an einer Station
 * und wer es haben will, muss hinfliegen; ein Kaufgebot hat eine
 * <em>Reichweite</em> und kauft dir die Ware ueberall innerhalb dieser
 * Reichweite ab.</p>
 *
 * <p><b>Der belegte Schaden.</b> Fuer White Glaze IV-Grade (Typ 17976) stehen
 * in The Forge genau drei Gebote: 181.000 ISK mit Reichweite "1" aus Perimeter
 * (30000144), 18.000 ISK mit Reichweite "4" von derselben Struktur und 1,00 ISK
 * mit Reichweite "region" physisch in Jita 4-4. Der Stationsfilter behielt
 * ausgerechnet den Lockvogel zu 1 ISK. Bei Bistot faellt das nicht auf, weil
 * dort genug Gebote wirklich in Jita 4-4 liegen; bei Nischenwaren wie Eis
 * liegen sie eben nicht dort.</p>
 *
 * <p><b>Was mit den Verkaufsangeboten passiert: nichts.</b> Sie bleiben
 * stationsgebunden. Das ist keine vergessene Vereinheitlichung, sondern der
 * Sachverhalt - das Feld {@code range} traegt bei ihnen ohnehin keine
 * Auskunft: an einer Stichprobe von 89 Verkaufsangeboten fuer Tritanium in The
 * Forge stand ausnahmslos "region", und trotzdem kann man in Jita nichts
 * kaufen, was in Perimeter liegt.</p>
 *
 * @param station       Zielstation, auf die Verkaufsangebote gefiltert werden
 * @param stationSystem das Sonnensystem dieser Station
 * @param jumpsToTarget Spruenge je System bis zur Zielstation; ein System, das
 *                      hier fehlt, ist ueber Tore nicht erreichbar
 */
public record MarketOrderReach(long station, long stationSystem, Map<Long, Integer> jumpsToTarget) {

    public MarketOrderReach {
        jumpsToTarget = Map.copyOf(jumpsToTarget);
    }

    /**
     * Ob ein Kaufgebot mit dieser Reichweite unsere Zielstation bedient.
     *
     * <p>Die Reichweiten, die ESI kennt: {@code "station"}, {@code
     * "solarsystem"}, {@code "region"} und die Sprungzahlen 1, 2, 3, 4, 5, 10,
     * 20, 30, 40.</p>
     *
     * @param locationId Standort des Gebots - bei Strukturen eine Zahl weit
     *                   jenseits der NPC-Stationen, das System steht trotzdem
     *                   im eigenen Feld
     * @param systemId   Sonnensystem des Gebots
     * @param range      das Feld {@code range} der Order
     */
    public boolean deckt(Long locationId, Long systemId, String range) {
        if (range == null || range.isBlank()) {
            // Ohne Reichweitenangabe bleibt nur der Standort. Grosszuegig zu
            // raten hiesse hier, ein Gebot mitzuzaehlen, das die Ware
            // vielleicht gar nicht abnimmt - und der Preis daraus ginge
            // ungeprueft in eine Rechnung.
            return anDerStation(locationId);
        }
        String reichweite = range.trim().toLowerCase(Locale.ROOT);

        return switch (reichweite) {
            // Nur wer wirklich hier steht. Das ist die einzige Reichweite, bei
            // der ein Gebot aus dem Nachbarsystem nichts nuetzt.
            case "station" -> anDerStation(locationId);

            // Das ganze System, also auch die Struktur nebenan im selben
            // System - nicht nur die eine Station.
            case "solarsystem" -> systemId != null && systemId == stationSystem;

            // Wir fragen ohnehin nur eine Region ab; alles darin ist gedeckt.
            // Genau diese Reichweite hatte das 1-ISK-Gebot - sie ist also
            // nicht der Fehler, der Stationsfilter war es.
            case "region" -> true;

            default -> innerhalbVonSpruengen(systemId, reichweite);
        };
    }

    private boolean anDerStation(Long locationId) {
        return locationId != null && locationId == station;
    }

    /**
     * Die Zahlenreichweiten.
     *
     * <p>Ein unbekannter Wert zaehlt nicht. Sollte CCP die Liste erweitern,
     * faellt die neue Reichweite heraus statt den Abzug abzureissen - ein
     * fehlendes Gebot ist ein zu niedriger Preis, eine Ausnahme mitten im
     * Durchlauf kostet den ganzen Abzug.</p>
     */
    private boolean innerhalbVonSpruengen(Long systemId, String reichweite) {
        int erlaubteSpruenge;
        try {
            erlaubteSpruenge = Integer.parseInt(reichweite);
        } catch (NumberFormatException e) {
            return false;
        }
        if (systemId == null) {
            return false;
        }
        Integer spruenge = jumpsToTarget.get(systemId);
        // Ein System, das die Sprungkarte nicht kennt, ist ueber Tore nicht
        // erreichbar - Wurmloecher, abgeschnittene Ecken. Es als 0 Spruenge zu
        // behandeln waere die schlimmste der drei Moeglichkeiten: dann
        // gewaenne jedes beliebige Gebot aus dem Nirgendwo.
        return spruenge != null && spruenge <= erlaubteSpruenge;
    }
}
