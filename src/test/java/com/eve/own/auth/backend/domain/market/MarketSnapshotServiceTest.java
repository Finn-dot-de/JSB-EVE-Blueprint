package com.eve.own.auth.backend.domain.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.eve.own.auth.backend.esi.EsiService;
import com.eve.own.auth.backend.esi.EsiService.EsiMarketOrder;
import com.eve.own.auth.backend.esi.client.EsiRequestExecutor.UncachedPage;
import com.eve.own.auth.backend.testsupport.LogCapture;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

/**
 * Der Marktabzug: alle Seiten holen, auf die Zielstation filtern, je Typ die
 * beste Order behalten - und laut sein, wenn dabei etwas schiefgeht.
 *
 * <p>Der Anlass steht in {@code MarketPriceRules}: eine Preisquelle, die mit
 * HTTP 200 und lauter Nullen antwortete, lief einen Tag lang stuendlich durch
 * und meldete jedes Mal Erfolg. Deshalb pruefen die Tests hier nicht nur, was
 * herauskommt, sondern auch, was gemeldet wird.</p>
 */
class MarketSnapshotServiceTest {

    private static final long REGION = 10_000_002L;
    private static final long JITA_44 = 60_003_760L;
    private static final long ANDERE_STATION = 60_011_866L;

    private static final long TRITANIUM = 34L;
    private static final long PYERITE = 35L;

    private EsiService esiService;

    @BeforeEach
    void setUp() {
        esiService = mock(EsiService.class);
    }

    // ===========================================================
    //  Hilfsmittel
    // ===========================================================

    private MarketSnapshotService dienst(int mindestensPreise) {
        return new MarketSnapshotService(esiService,
                new MarketOrderProperties(REGION, JITA_44, mindestensPreise, 200, 10, 0L));
    }

    /** Ein Dienst, dem ein einziger Preis genuegt - fuer alles ausser der Ausfallschwelle. */
    private MarketSnapshotService dienst() {
        return dienst(1);
    }

    private static EsiMarketOrder verkauf(long typeId, long stationId, double preis) {
        return order(typeId, stationId, preis, false);
    }

    private static EsiMarketOrder kauf(long typeId, long stationId, double preis) {
        return order(typeId, stationId, preis, true);
    }

    private static EsiMarketOrder order(long typeId, long stationId, double preis, boolean istKauf) {
        return new EsiMarketOrder(1L, typeId, stationId, 30_000_142L,
                100L, 100L, 1L, 90L, preis, istKauf, "station", Instant.EPOCH);
    }

    private static UncachedPage<EsiMarketOrder> seite(int seitenGesamt, EsiMarketOrder... orders) {
        return new UncachedPage<>(orders, seitenGesamt, 11_000, null);
    }

    private void antwortet(int seite, UncachedPage<EsiMarketOrder> antwort) {
        when(esiService.getMarketOrdersPage(REGION, seite)).thenReturn(antwort);
    }

    // ===========================================================
    //  Seiten einsammeln
    // ===========================================================

    @Test
    @DisplayName("sammelt alle Seiten ein, nicht nur die erste")
    void alleSeitenWerdenEingesammelt() {
        antwortet(1, seite(3, verkauf(TRITANIUM, JITA_44, 5.00)));
        antwortet(2, seite(3, verkauf(PYERITE, JITA_44, 17.60)));
        antwortet(3, seite(3, verkauf(11_399L, JITA_44, 19_000.00)));

        MarketSnapshot abzug = dienst().pull();

        // Ohne die Schleife ueber X-Pages endete der Abzug nach Seite 1. Bei
        // 411 Seiten waeren das 0,2 % des Marktes - und alles andere saehe aus
        // wie "kein Angebot vorhanden", also wie ein echtes Ergebnis.
        assertThat(abzug.prices()).containsOnlyKeys(TRITANIUM, PYERITE, 11_399L);
        verify(esiService).getMarketOrdersPage(REGION, 3);
    }

    @Test
    @DisplayName("laeuft genau bis X-Pages und nicht darueber hinaus")
    void keineSeiteZuViel() {
        antwortet(1, seite(2, verkauf(TRITANIUM, JITA_44, 5.00)));
        antwortet(2, seite(2, verkauf(PYERITE, JITA_44, 17.60)));

        dienst().pull();

        // Eine Seite hinter X-Pages antwortet mit 404 - nachgemessen - und ein
        // 4xx kostet 5 Token statt 2. Ins Leere zu laufen ist hier also nicht
        // nur unsauber, sondern teurer als der eigentliche Abruf.
        verify(esiService, never()).getMarketOrdersPage(anyLong(), eq(3));
    }

    // ===========================================================
    //  Station
    // ===========================================================

    @Test
    @DisplayName("wirft Orders anderer Stationen raus")
    void fremdeStationenFliegenRaus() {
        antwortet(1, seite(1,
                verkauf(TRITANIUM, ANDERE_STATION, 1.01),
                verkauf(TRITANIUM, JITA_44, 3.85)));

        MarketSnapshot abzug = dienst().pull();

        // Ohne diesen Filter stuende hier 1,01 - der guenstigste Preis
        // IRGENDWO in der Region. Nachgemessen gibt es genau diesen Fall:
        // Typ 15625 kostet in der Region 1,01 ISK und in Jita 4-4 4.501 ISK.
        // Der Preis saehe brauchbar aus und waere am gefragten Ort nicht zu
        // bekommen.
        assertThat(abzug.price(TRITANIUM).sell()).isEqualTo(3.85);
    }

    @Test
    @DisplayName("ignoriert eine Order ohne Standort")
    void orderOhneStandortZaehltNicht() {
        antwortet(1, seite(1,
                new EsiMarketOrder(1L, TRITANIUM, null, 30_000_142L,
                        100L, 100L, 1L, 90L, 0.01, false, "station", Instant.EPOCH),
                verkauf(TRITANIUM, JITA_44, 3.85)));

        MarketSnapshot abzug = dienst().pull();

        // Ohne die Null-Pruefung fliegt hier eine NullPointerException und
        // reisst den ganzen Abzug mit - wegen einer einzigen kaputten Order
        // unter 411.000.
        assertThat(abzug.price(TRITANIUM).sell()).isEqualTo(3.85);
    }

    // ===========================================================
    //  Beste Order je Typ
    // ===========================================================

    @Test
    @DisplayName("behaelt je Typ den guenstigsten Verkauf und das hoechste Kaufgebot")
    void besteOrderJeSeite() {
        antwortet(1, seite(2,
                verkauf(TRITANIUM, JITA_44, 4.20),
                kauf(TRITANIUM, JITA_44, 3.50)));
        antwortet(2, seite(2,
                verkauf(TRITANIUM, JITA_44, 3.85),
                kauf(TRITANIUM, JITA_44, 3.77)));

        MarketSnapshot abzug = dienst().pull();

        // Die Richtung ist je Seite eine andere: kaufen will man zum
        // niedrigsten Angebot, verkaufen zum hoechsten Gebot. Wer beide gleich
        // behandelt, dreht den Spread um und macht jede Bauen-gegen-Kaufen-
        // Rechnung falsch - und zwar unauffaellig, weil die Zahl plausibel
        // bleibt. Und beides muss ueber SEITENGRENZEN hinweg gelten: der
        // guenstigste Verkauf stand hier auf Seite 2.
        assertThat(abzug.price(TRITANIUM).sell()).isEqualTo(3.85);
        assertThat(abzug.price(TRITANIUM).buy()).isEqualTo(3.77);
    }

    @Test
    @DisplayName("behaelt eine Seite, wenn es die andere nicht gibt")
    void halbeAuskunftBleibtErhalten() {
        antwortet(1, seite(1, verkauf(TRITANIUM, JITA_44, 3.85)));

        MarketSnapshot abzug = dienst().pull();

        // "Niemand bietet" darf nicht zu "ist nichts wert" werden. Der Typ
        // faellt nicht weg, nur weil eine Seite leer ist - bei duenn
        // gehandelten Typen ist das der Normalfall.
        assertThat(abzug.price(TRITANIUM).sell()).isEqualTo(3.85);
        assertThat(abzug.price(TRITANIUM).buy()).isNull();
    }

    @Test
    @DisplayName("gibt einem Typ ohne Order gar keinen Preis, nicht die Null")
    void keinPreisStattNull() {
        antwortet(1, seite(1, verkauf(TRITANIUM, JITA_44, 3.85)));

        MarketSnapshot abzug = dienst().pull();

        // Der Kern der ganzen Umstellung. Ohne diese Zeile bekaeme Pyerit eine
        // Huelle mit zwei Nullen - und jeder Aufrufer, der nur prueft, ob die
        // Huelle da ist, schriebe daraus einen Preis von 0 ISK. Genau so
        // entstanden die 6.698 Nullzeilen in market_prices.
        assertThat(abzug.price(PYERITE)).isNull();
        assertThat(abzug.prices()).doesNotContainKey(PYERITE);
    }

    @Test
    @DisplayName("zaehlt eine Order zum Preis 0 nicht als Preis")
    void nullpreisIstKeinPreis() {
        antwortet(1, seite(1,
                verkauf(TRITANIUM, JITA_44, 0.0),
                kauf(TRITANIUM, JITA_44, -1.0),
                verkauf(PYERITE, JITA_44, 17.60)));

        MarketSnapshot abzug = dienst().pull();

        // In EVE liegt der Mindestpreis einer Order bei 0,01 ISK; eine Order zu
        // 0 kann es nicht geben. Kaeme sie doch, waere sie eine Falschmeldung
        // und kein Schnaeppchen - ohne diese Zeile stuende Tritanium wieder auf
        // 0 ISK und der Einkauf saehe kostenlos aus.
        assertThat(abzug.price(TRITANIUM)).isNull();
        assertThat(abzug.price(PYERITE).sell()).isEqualTo(17.60);
    }

    // ===========================================================
    //  Abbruch
    // ===========================================================

    @Test
    @DisplayName("liefert bei einem Abbruch mittendrin gar kein Ergebnis")
    void abbruchLiefertKeinTeilergebnis() {
        antwortet(1, seite(3, verkauf(TRITANIUM, JITA_44, 3.85)));
        when(esiService.getMarketOrdersPage(REGION, 2))
                .thenThrow(new RestClientException("502 Bad Gateway"));

        // Ohne den Abbruch kaeme ein Abzug aus einer von drei Seiten zurueck -
        // und der saehe aus wie ein Markt, in dem zwei Drittel aller Typen
        // ueber Nacht ihr Angebot verloren haben. Die Verbraucher wuerden
        // brauchbare Preise durch Luecken ersetzen. Ein halber Markt ist
        // schlimmer als ein alter.
        assertThatThrownBy(() -> dienst().pull())
                .isInstanceOf(MarketSnapshotUnavailableException.class)
                .hasMessageContaining("Seite 2 von 3");
    }

    @Test
    @DisplayName("bricht ab, wenn eine Seite ohne lesbaren Inhalt zurueckkommt")
    void unlesbareSeiteIstEinAbbruch() {
        antwortet(1, new UncachedPage<>(null, 2, 11_000, null));

        // Der Deserialisierer schluckt Parse-Fehler und gibt null zurueck. Ohne
        // diese Pruefung fehlte die Seite einfach im Ergebnis - bei 411 Seiten
        // faellt das niemandem auf. Genau solche unsichtbaren Luecken sind der
        // Grund fuer diesen Umbau.
        assertThatThrownBy(() -> dienst().pull())
                .isInstanceOf(MarketSnapshotUnavailableException.class)
                .hasMessageContaining("ohne lesbaren Inhalt");
    }

    @Test
    @DisplayName("hoert auf, bevor das Kontingent aufgebraucht ist")
    void kontingentwaechterBrichtAb() {
        antwortet(1, new UncachedPage<>(
                new EsiMarketOrder[]{verkauf(TRITANIUM, JITA_44, 3.85)}, 5, 199, null));

        // 12.000 Token je 15 Minuten in der Gruppe "market-order", ein 2xx
        // kostet 2. Weiterzuhaemmern, wenn nur noch 199 uebrig sind, verlaengert
        // nur das Zeitfenster - und ein 420 trifft ALLE ESI-Routen, also auch
        // den Job- und Charakterabgleich, die mit dem Markt nichts zu tun haben.
        assertThatThrownBy(() -> dienst().pull())
                .isInstanceOf(MarketSnapshotUnavailableException.class)
                .hasMessageContaining("Kontingent");
        verify(esiService, never()).getMarketOrdersPage(anyLong(), eq(2));
    }

    @Test
    @DisplayName("hoert auch auf, wenn das aeltere Fehlerkontingent zur Neige geht")
    void fehlerkontingentwaechterBrichtAb() {
        antwortet(1, new UncachedPage<>(
                new EsiMarketOrder[]{verkauf(TRITANIUM, JITA_44, 3.85)}, 5, 11_000, 9));

        // Das aeltere Verfahren: 100 Nicht-2xx je Minute, danach 420 auf allen
        // Routen. Diese Route schickt die Kopfzeile heute nicht mit; wenn sie
        // es eines Tages tut, soll der Abzug lange vor der Sperre aufhoeren.
        assertThatThrownBy(() -> dienst().pull())
                .isInstanceOf(MarketSnapshotUnavailableException.class)
                .hasMessageContaining("Fehlerkontingent");
    }

    // ===========================================================
    //  Was gemeldet wird
    // ===========================================================

    @Test
    @DisplayName("meldet auffallend wenige Preise als Ausfall mit WARN, nicht als Erfolg")
    void zuWenigePreiseSindEinAusfall() {
        antwortet(1, seite(1, verkauf(TRITANIUM, JITA_44, 3.85)));

        try (LogCapture protokoll = new LogCapture(MarketSnapshotService.class)) {
            // Schwelle 1.000; gemessen haben an Jita 4-4 16.885 Typen ein
            // Verkaufsangebot. Ein Durchlauf mit einem einzigen Preis ist
            // formal glatt gelaufen und trotzdem ein Ausfall der Quelle.
            assertThatThrownBy(() -> dienst(1_000).pull())
                    .isInstanceOf(MarketSnapshotUnavailableException.class);

            // Ohne diese Zeile stuende der Ausfall als INFO im Protokoll -
            // genau die Verwechslung, die den Fuzzwork-Ausfall einen ganzen Tag
            // lang verdeckt hat.
            assertThat(protokoll.meldungen(Level.WARN))
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("Ausfall der Preisquelle")
                    .contains("nur 1 brauchbare Preise");
            assertThat(protokoll.meldungen(Level.INFO)).isEmpty();
        }
    }

    @Test
    @DisplayName("wertet einen Durchlauf ganz ohne brauchbaren Preis als Ausfall")
    void abzugOhneEinenEinzigenPreis() {
        // Der belegte Fall in seiner reinen Form: die Quelle antwortet formal
        // einwandfrei, und es steht nichts drin. Frueher war das von einem
        // Erfolg nicht zu unterscheiden, weil die Antwort ja nicht leer war,
        // sondern voller Nullen.
        antwortet(1, seite(1,
                verkauf(TRITANIUM, JITA_44, 0.0),
                verkauf(PYERITE, JITA_44, 0.0)));

        try (LogCapture protokoll = new LogCapture(MarketSnapshotService.class)) {
            assertThatThrownBy(() -> dienst(1).pull())
                    .isInstanceOf(MarketSnapshotUnavailableException.class);

            assertThat(protokoll.meldungen(Level.WARN))
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("nur 0 brauchbare Preise");
        }
    }

    @Test
    @DisplayName("zaehlt in der Erfolgsmeldung brauchbare Preise, nicht Orders oder Seiten")
    void erfolgsmeldungZaehltBrauchbarePreise() {
        // Vier Orders, drei davon an der Zielstation, zwei Typen mit Preis.
        antwortet(1, seite(1,
                verkauf(TRITANIUM, JITA_44, 3.85),
                kauf(TRITANIUM, JITA_44, 3.77),
                verkauf(PYERITE, JITA_44, 17.60),
                verkauf(11_399L, ANDERE_STATION, 19_000.00)));

        try (LogCapture protokoll = new LogCapture(MarketSnapshotService.class)) {
            dienst().pull();

            // Die Erfolgsmeldung muss die Zahl nennen, auf die es ankommt: wie
            // viele Typen einen brauchbaren Preis haben. Die alte Meldung
            // zaehlte geschriebene Zeilen - und die stimmte auch dann noch,
            // wenn in jeder Zeile eine 0 stand.
            assertThat(protokoll.meldungen(Level.INFO))
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("2 Typen mit brauchbarem Preis")
                    .contains("aus 4 Orders");
            assertThat(protokoll.meldungen(Level.WARN)).isEmpty();
        }
    }

    @Test
    @DisplayName("fragt die konfigurierte Region ab, nicht eine fest verdrahtete")
    void regionKommtAusDerKonfiguration() {
        long amarr = 10_000_043L;
        long amarrHub = 60_008_494L;
        when(esiService.getMarketOrdersPage(eq(amarr), anyInt()))
                .thenReturn(seite(1, verkauf(TRITANIUM, amarrHub, 4.10)));

        MarketSnapshot abzug = new MarketSnapshotService(esiService,
                new MarketOrderProperties(amarr, amarrHub, 1, 200, 10, 0L)).pull();

        // Ohne die Konfiguration waeren Region und Station Konstanten im Code -
        // und ein Umzug des Handelsplatzes eine Codeaenderung samt Neubau.
        assertThat(abzug.station()).isEqualTo(amarrHub);
        assertThat(abzug.price(TRITANIUM).sell()).isEqualTo(4.10);
    }
}
