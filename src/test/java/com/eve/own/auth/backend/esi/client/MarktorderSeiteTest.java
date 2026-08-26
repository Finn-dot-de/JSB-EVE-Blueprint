package com.eve.own.auth.backend.esi.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.eve.own.auth.backend.esi.EsiService;
import com.eve.own.auth.backend.esi.EsiService.EsiMarketOrder;
import com.eve.own.auth.backend.esi.client.EsiRequestExecutor.UncachedPage;
import com.eve.own.auth.backend.esi.etag.EsiEtagStore;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * Eine Seite des Orderbuchs, vom Draht bis zum Datensatz.
 *
 * <p>Der Rumpf unten ist <em>echt</em>: zwei Orders, wortwoertlich aus
 * {@code /markets/10000002/orders/?order_type=all&page=1} vom 26.08.2026. Das
 * ist der Sinn dieses Tests - er prueft nicht eine erfundene Form, sondern die,
 * die ESI tatsaechlich schickt. Faellt hier ein Feldname um, faellt es hier auf
 * und nicht erst an einer stillen Luecke in {@code market_prices}.</p>
 */
class MarktorderSeiteTest {

    /** Genau die zwoelf Felder, die die Spez als {@code required} fuehrt. */
    private static final String ECHTE_SEITE = """
            [
              {"duration": 90, "is_buy_order": false, "issued": "2026-08-16T14:07:04Z",
               "location_id": 60003760, "min_volume": 1, "order_id": 7401897984,
               "price": 250000.0, "range": "region", "system_id": 30000142,
               "type_id": 25614, "volume_remain": 1000, "volume_total": 1000},
              {"duration": 30, "is_buy_order": true, "issued": "2026-08-20T09:11:00Z",
               "location_id": 1044752365771, "min_volume": 1, "order_id": 7401897985,
               "price": 3.77, "range": "station", "system_id": 30000142,
               "type_id": 34, "volume_remain": 5000000, "volume_total": 5000000}
            ]
            """;

    private MockRestServiceServer server;
    private EsiEtagStore etagStore;
    private EsiService esiService;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://esi.evetech.net");
        server = MockRestServiceServer.bindTo(builder).build();
        etagStore = mock(EsiEtagStore.class);
        esiService = new EsiService(new EsiRequestExecutor(builder.build(), etagStore, new ObjectMapper()));
    }

    private static HttpHeaders kopfzeilen(String pages, String kontingent) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Pages", pages);
        if (kontingent != null) {
            headers.set("X-Ratelimit-Remaining", kontingent);
        }
        return headers;
    }

    @Test
    @DisplayName("liest eine echte ESI-Seite vollstaendig in den Datensatz")
    void echteSeiteWirdGelesen() {
        server.expect(requestTo(
                        "https://esi.evetech.net/markets/10000002/orders/?order_type=all&page=1"))
                .andRespond(withSuccess(ECHTE_SEITE, MediaType.APPLICATION_JSON)
                        .headers(kopfzeilen("411", "11294")));

        UncachedPage<EsiMarketOrder> seite = esiService.getMarketOrdersPage(10_000_002L, 1);

        assertThat(seite.totalPages()).isEqualTo(411);
        assertThat(seite.rateLimitRemaining()).isEqualTo(11_294);
        assertThat(seite.items()).hasSize(2);

        EsiMarketOrder verkauf = seite.items()[0];
        assertThat(verkauf.is_buy_order()).isFalse();
        assertThat(verkauf.price()).isEqualTo(250_000.0);
        assertThat(verkauf.location_id()).isEqualTo(60_003_760L);
        // "issued" ist ein Zeitstempel und kein Text. Ohne die passende
        // Jackson-Konfiguration flaege hier eine Ausnahme - und die risse den
        // ganzen Abzug mit.
        assertThat(verkauf.issued()).isEqualTo(Instant.parse("2026-08-16T14:07:04Z"));

        EsiMarketOrder kauf = seite.items()[1];
        assertThat(kauf.is_buy_order()).isTrue();
        // Der eigentliche Grund fuer den Long: 1.044.752.365.771 ist eine
        // Spielerstruktur und passt in keinen Integer. Mit einem Integer
        // bekaeme man hier eine Ausnahme statt einer Order.
        assertThat(kauf.location_id()).isEqualTo(1_044_752_365_771L);
        assertThat(kauf.range()).isEqualTo("station");

        server.verify();
    }

    @Test
    @DisplayName("legt die Seite nicht im ETag-Cache ab")
    void seiteLandetNichtImCache() {
        server.expect(requestTo(
                        "https://esi.evetech.net/markets/10000002/orders/?order_type=all&page=1"))
                .andRespond(withSuccess(ECHTE_SEITE, MediaType.APPLICATION_JSON)
                        .headers(kopfzeilen("411", "11294")));

        esiService.getMarketOrdersPage(10_000_002L, 1);

        // 411 Seiten zu je 237.388 Bytes sind rund 95 MB - stuendlich in eine
        // text-Spalte geschrieben und wieder gelesen, fuer einen Cache, der bei
        // 300 s Pufferzeit und stuendlichem Lauf ohnehin nie trifft. Ausserdem
        // liegt eine Seite nur 9 % unter der Obergrenze von 262.144 Bytes.
        verify(etagStore, never()).recordChanged(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
        verify(etagStore, never()).find(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("reicht einen Serverfehler durch, statt ihn zu schlucken")
    void serverfehlerKommtDurch() {
        server.expect(requestTo(
                        "https://esi.evetech.net/markets/10000002/orders/?order_type=all&page=1"))
                .andRespond(withServerError());

        // Wuerde der Fehler hier geschluckt, kaeme eine leere Seite zurueck -
        // und der Abzug haette eine Luecke, die wie "kein Angebot" aussieht.
        // Der Aufrufer muss abbrechen koennen.
        assertThatThrownBy(() -> esiService.getMarketOrdersPage(10_000_002L, 1))
                .isInstanceOf(RestClientResponseException.class);
    }
}
