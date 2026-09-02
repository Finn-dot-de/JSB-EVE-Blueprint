package com.eve.own.auth.backend.domain.market;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stellschrauben des Marktabzugs, konfigurierbar ueber {@code eve.market.*}.
 *
 * <p>Region und Station stehen hier und nicht im Code, weil sie eine
 * Betriebsentscheidung sind: wer seinen Handel nach Amarr verlegt, aendert eine
 * Zeile in der Konfiguration und nicht eine Konstante in einer Klasse. Die
 * Vorgaben sind die heutigen Werte - The Forge und Jita IV-4.</p>
 *
 * @param regionId          Region, deren Orderbuch abgezogen wird. 10000002 = The Forge.
 * @param stationId         Zielstation. 60003760 = Jita IV - Moon 4 - Caldari Navy Assembly Plant.
 * @param stationSystemId   Sonnensystem dieser Station. 30000142 = Jita.
 * @param minUsablePrices   Untergrenze, ab der ein Durchlauf als Ausfall gilt.
 * @param rateLimitReserve  Wieviel vom Kontingent uebrig bleiben muss, sonst wird abgebrochen.
 * @param errorLimitReserve Dasselbe fuer das aeltere Fehlerkontingent.
 * @param pageDelayMillis   Zusaetzliche Pause zwischen zwei Seiten.
 */
@ConfigurationProperties(prefix = "eve.market")
public record MarketOrderProperties(
        Long regionId,
        Long stationId,
        Long stationSystemId,
        Integer minUsablePrices,
        Integer rateLimitReserve,
        Integer errorLimitReserve,
        Long pageDelayMillis
) {

    public MarketOrderProperties {
        if (regionId == null) regionId = 10_000_002L;
        if (stationId == null) stationId = 60_003_760L;

        // Das System der Zielstation steht hier und wird nicht nachgeschlagen,
        // weil es im SDE nicht zu finden ist: das Schema evesde fuehrt zwar
        // mapDenormalize, aber keine Stationen - eine Abfrage nach 60003760
        // liefert nachgemessen null Zeilen. Gebraucht wird es fuer die
        // Reichweite der Kaufgebote (MarketOrderReach): ein Gebot mit
        // Reichweite "1" aus Perimeter erreicht Jita, eines aus Amarr nicht.
        // Wer die Station umstellt, muss diese Zeile mit umstellen - deshalb
        // meldet der Abzug einen Widerspruch zwischen beiden ausdruecklich.
        if (stationSystemId == null) stationSystemId = 30_000_142L;

        // An einem echten Durchlauf gemessen: 18.799 Typen mit brauchbarem
        // Preis an Jita 4-4, davon 16.887 mit Verkaufsangebot. Selbst ein sehr
        // schlechter Tag bleibt weit oberhalb von 1.000 - diese Schwelle wird
        // also nur dann unterschritten, wenn die Quelle kaputt ist. Sie ist
        // bewusst nicht knapp gesetzt: sie soll den Totalausfall fangen, nicht
        // eine ruhige Handelsstunde als Stoerung melden.
        if (minUsablePrices == null) minUsablePrices = 1_000;

        // Das Kontingent der Gruppe "market-order" ist 12.000 je 15 Minuten,
        // ein 2xx kostet 2. Ein voller Durchlauf ueber 411 Seiten kostet also
        // 822 - knapp 7 %. Bleiben weniger als 200 uebrig, laeuft neben uns
        // etwas aus dem Ruder; dann ist Aufhoeren richtig, denn das Fenster
        // fuellt sich von selbst wieder auf.
        if (rateLimitReserve == null) rateLimitReserve = 200;

        // Das aeltere Verfahren: 100 Nicht-2xx je Minute, danach 420 auf ALLEN
        // ESI-Routen. Diese Route schickt die Kopfzeile derzeit nicht mit -
        // wenn sie es eines Tages tut, soll der Abzug lange vor der Sperre
        // aufhoeren, weil die Sperre den ganzen Rest der Anwendung trifft.
        if (errorLimitReserve == null) errorLimitReserve = 10;

        // Kein kuenstlicher Abstand. Am echten Endpunkt gemessen: 411 Seiten,
        // 410.753 Orders, 110 Sekunden - also 0,27 s je Seite und 3,7 Anfragen
        // je Sekunde. Das Kontingent gibt 12.000 Token je 15 Minuten her und
        // der Durchlauf braucht 822; wir liegen bei knapp 7 % und sind nach
        // knapp zwei Minuten fertig. Der Rueckweg ist bereits der Abstand.
        // Eine zusaetzliche Pause wuerde nur das Zeitfenster verlaengern, in
        // dem ein Abbruch den ganzen Durchlauf wertlos macht. Die Schraube
        // bleibt trotzdem da, falls CCP enger wird.
        if (pageDelayMillis == null) pageDelayMillis = 0L;
    }
}
