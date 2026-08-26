package com.eve.own.auth.backend.domain.market;

import com.eve.own.auth.backend.common.MarketPriceRules;
import com.eve.own.auth.backend.esi.EsiService;
import com.eve.own.auth.backend.esi.EsiService.EsiMarketOrder;
import com.eve.own.auth.backend.esi.client.EsiRequestExecutor.UncachedPage;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Zieht das Orderbuch einer Region ab und macht daraus Stationspreise.
 *
 * <p><b>Warum die ganze Region und nicht Typ fuer Typ.</b> ESI kann auch
 * {@code ?type_id=34} - nur waere das teurer, nicht billiger. Gebraucht werden
 * 6.484 Typen; das sind 6.484 Anfragen gegen 411 fuer die ganze Region, also
 * Faktor sechzehn, und im Kontingent 12.968 Token gegen 822. Der Regionsabzug
 * liefert nebenbei alle 17.373 gehandelten Typen statt nur der bekannten, dazu
 * die Kaufseite.</p>
 *
 * <p><b>Warum stationsgenau gefiltert wird, obwohl die Region geholt wird.</b>
 * Weil es nichts kostet: jede Order traegt ihre {@code location_id}. Gemessen
 * ueber alle 278.668 Verkaufsorders in The Forge liegt der Median der
 * Abweichung zwischen Regions- und Stationsminimum bei 0,0 % - aber 1.069 Typen
 * weichen um mehr als 10 % ab, und ein Typ lag bei 1,01 ISK in der Region gegen
 * 4.501 ISK in Jita 4-4. Wer die Region ohnehin durchlaeuft, bekommt die
 * Genauigkeit geschenkt und muss sich gar nicht entscheiden.</p>
 *
 * <p><b>Was der Abzug nicht sieht.</b> Nur NPC-Stationen und oeffentliche
 * Strukturen. Private Strukturen liegen hinter
 * {@code /markets/structures/{id}} und braeuchten dort einen Scope. Fuer Jita
 * 4-4 - eine NPC-Station - ist das ohne Belang, und deshalb bleiben die
 * ESI-Scopes unangetastet.</p>
 */
@Slf4j
@Service
public class MarketSnapshotService {

    private final EsiService esiService;
    private final MarketOrderProperties props;

    public MarketSnapshotService(EsiService esiService, MarketOrderProperties props) {
        this.esiService = esiService;
        this.props = props;
    }

    /**
     * Holt alle Seiten und bildet je Typ den guenstigsten Verkauf und das
     * hoechste Kaufgebot an der Zielstation.
     *
     * @return ein vollstaendiger Abzug
     * @throws MarketSnapshotUnavailableException wenn er es nicht wurde - dann darf
     *                                            der Aufrufer nichts schreiben
     */
    public MarketSnapshot pull() {
        long begonnen = System.nanoTime();
        long region = props.regionId();
        long station = props.stationId();

        Map<Long, Double> guenstigsterVerkauf = new HashMap<>();
        Map<Long, Double> hoechstesKaufgebot = new HashMap<>();

        int seite = 1;
        int seitenGesamt = 1;
        long ordersGesamt = 0;
        long ordersAnStation = 0;

        while (seite <= seitenGesamt) {
            UncachedPage<EsiMarketOrder> antwort = holeSeite(region, seite, seitenGesamt);
            pruefeKontingent(antwort, seite);

            EsiMarketOrder[] orders = antwort.items();
            if (orders == null) {
                // 200, aber der Body liess sich nicht lesen. Diese Seite fehlte
                // sonst still im Ergebnis - bei 411 Seiten faellt das niemandem
                // auf, und genau solche unsichtbaren Luecken sind der Grund fuer
                // diesen ganzen Umbau.
                throw abbruch("Seite " + seite + " von " + seitenGesamt
                        + " kam ohne lesbaren Inhalt zurueck");
            }

            ordersGesamt += orders.length;
            for (EsiMarketOrder order : orders) {
                if (uebernimm(order, station, guenstigsterVerkauf, hoechstesKaufgebot)) {
                    ordersAnStation++;
                }
            }

            seitenGesamt = Math.max(seitenGesamt, antwort.totalPages());
            seite++;
            pause();
        }

        Map<Long, StationPrice> preise = zusammenfuehren(guenstigsterVerkauf, hoechstesKaufgebot);

        if (preise.size() < props.minUsablePrices()) {
            // Der Kern der Lehre aus dem Fuzzwork-Vorfall: ein Durchlauf, der
            // formal glatt lief, aber kaum einen Preis traegt, ist ein Ausfall
            // der Quelle - und muss so heissen. Frueher las sich genau dieser
            // Fall als "2165 Typen gespeichert, 0 Batches fehlgeschlagen".
            log.warn("Marktabzug lieferte nur {} brauchbare Preise an Station {} "
                            + "(erwartet mindestens {}; {} Orders auf {} Seiten, davon {} an der Station). "
                            + "Das ist ein Ausfall der Preisquelle - die alten Preise bleiben stehen.",
                    preise.size(), station, props.minUsablePrices(),
                    ordersGesamt, seitenGesamt, ordersAnStation);
            throw new MarketSnapshotUnavailableException(
                    "nur " + preise.size() + " brauchbare Preise, mindestens "
                            + props.minUsablePrices() + " erwartet");
        }

        // Gezaehlt werden brauchbare PREISE, nicht abgeholte Seiten und nicht
        // geschriebene Zeilen. Die Verwechslung von "gespeichert" mit "bekannt"
        // hat den letzten Ausfall einen ganzen Tag lang verdeckt.
        log.info("Marktabzug: {} Typen mit brauchbarem Preis an Station {} "
                        + "({} mit Verkaufsangebot, {} mit Kaufgebot) aus {} Orders auf {} Seiten in {} s.",
                preise.size(), station,
                guenstigsterVerkauf.size(), hoechstesKaufgebot.size(),
                ordersGesamt, seitenGesamt,
                Duration.ofNanos(System.nanoTime() - begonnen).toSeconds());

        return new MarketSnapshot(Map.copyOf(preise), station, Instant.now());
    }

    // ===========================================================
    //  Interna
    // ===========================================================

    private UncachedPage<EsiMarketOrder> holeSeite(long region, int seite, int seitenGesamt) {
        try {
            return esiService.getMarketOrdersPage(region, seite);
        } catch (RuntimeException e) {
            // Kein Teilergebnis. Ein halber Markt ist schlimmer als ein alter:
            // die fehlenden Seiten saehen aus wie Typen ohne Angebot, und der
            // Preis, den jemand vor einer Stunde noch sah, waere weg.
            throw abbruch("Seite " + seite + " von " + seitenGesamt
                    + " nicht abrufbar: " + e.getMessage(), e);
        }
    }

    /**
     * Bricht ab, bevor CCP uns bremst.
     *
     * <p>Weiterzuhaemmern, wenn das Kontingent zur Neige geht, verlaengert nur
     * das Zeitfenster - und ein 420 trifft <em>alle</em> ESI-Routen, also auch
     * den Job- und Charakterabgleich, die mit dem Markt nichts zu tun haben.</p>
     */
    private void pruefeKontingent(UncachedPage<EsiMarketOrder> antwort, int seite) {
        Integer restKontingent = antwort.rateLimitRemaining();
        if (restKontingent != null && restKontingent < props.rateLimitReserve()) {
            throw abbruch("Kontingent fast aufgebraucht (noch " + restKontingent
                    + ", Reserve " + props.rateLimitReserve() + ") bei Seite " + seite);
        }
        Integer restFehler = antwort.errorLimitRemaining();
        if (restFehler != null && restFehler < props.errorLimitReserve()) {
            throw abbruch("Fehlerkontingent fast aufgebraucht (noch " + restFehler
                    + ", Reserve " + props.errorLimitReserve() + ") bei Seite " + seite);
        }
    }

    /**
     * Verrechnet eine Order, sofern sie an der Zielstation liegt und einen
     * brauchbaren Preis traegt.
     *
     * @return ob sie gezaehlt wurde
     */
    private boolean uebernimm(EsiMarketOrder order, long station,
                              Map<Long, Double> verkauf, Map<Long, Double> kauf) {
        if (order == null || order.type_id() == null) {
            return false;
        }
        // Orders anderer Standorte fliegen raus. Ohne diese Zeile stuende der
        // guenstigste Preis IRGENDWO in der Region in der Tabelle - an einem
        // Ort, an dem niemand einkauft.
        if (order.location_id() == null || order.location_id() != station) {
            return false;
        }
        // Die eine Regel, hier nicht nachgebaut: <= 0 heisst "unbekannt".
        // In EVE liegt der Mindestpreis einer Order bei 0,01 ISK.
        Double preis = MarketPriceRules.usable(order.price());
        if (preis == null) {
            return false;
        }
        if (Boolean.TRUE.equals(order.is_buy_order())) {
            kauf.merge(order.type_id(), preis, Math::max);
        } else {
            verkauf.merge(order.type_id(), preis, Math::min);
        }
        return true;
    }

    /**
     * Legt beide Seiten je Typ zusammen.
     *
     * <p>Ein Typ taucht nur auf, wenn mindestens eine Seite besetzt ist - eine
     * Huelle mit zwei {@code null} waere wieder das, was der Aufrufer nur auf
     * Vorhandensein prueft und dann als Preis schreibt.</p>
     */
    private Map<Long, StationPrice> zusammenfuehren(Map<Long, Double> verkauf, Map<Long, Double> kauf) {
        Set<Long> typen = new HashSet<>(verkauf.keySet());
        typen.addAll(kauf.keySet());

        Map<Long, StationPrice> preise = new HashMap<>(typen.size() * 2);
        for (Long typeId : typen) {
            preise.put(typeId, new StationPrice(kauf.get(typeId), verkauf.get(typeId)));
        }
        return preise;
    }

    private void pause() {
        long millis = props.pageDelayMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw abbruch("Marktabzug unterbrochen");
        }
    }

    private MarketSnapshotUnavailableException abbruch(String grund) {
        return new MarketSnapshotUnavailableException(grund);
    }

    private MarketSnapshotUnavailableException abbruch(String grund, Throwable ursache) {
        return new MarketSnapshotUnavailableException(grund, ursache);
    }
}
