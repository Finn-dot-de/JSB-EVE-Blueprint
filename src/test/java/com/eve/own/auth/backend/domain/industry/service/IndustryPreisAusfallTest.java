package com.eve.own.auth.backend.domain.industry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.assets.entity.MarketPrice;
import com.eve.own.auth.backend.domain.assets.repository.MarketPriceRepository;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.industry.repository.CharacterBlueprintRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryJobRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository;
import com.eve.own.auth.backend.esi.EsiService;
import com.eve.own.auth.backend.esi.EsiService.FuzzworkBuy;
import com.eve.own.auth.backend.esi.EsiService.FuzzworkPrice;
import com.eve.own.auth.backend.esi.EsiService.FuzzworkSell;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Was der Preisabgleich tut, wenn die Quelle nichts mehr hergibt.
 *
 * <p>Die Antwort muss lauten: nichts anfassen. Ein alter Preis ist unangenehm,
 * ein falscher ist gefaehrlich - und eine 0 ist der falscheste, den es gibt,
 * weil sie den Kauf kostenlos macht.</p>
 *
 * <p>Die Antworten kommen hier bereits so, wie {@code getFuzzworkPrices} sie
 * liefert: von Nullen bereinigt. Ein Typ ohne brauchbaren Preis fehlt in der
 * Karte, eine Antwort ganz ohne Preis ist leer. Getestet wird also der
 * Schreibvorgang gegen diesen Vertrag - dass die Bereinigung selbst greift,
 * steht in {@code FuzzworkNullpreisTest}.</p>
 */
class IndustryPreisAusfallTest {

    private static final long TRITANIUM = 34L;
    private static final long PYERITE = 35L;

    private MarketPriceRepository priceRepo;
    private IndustryQueryRepository queryRepo;
    private EsiService esiService;
    private IndustrySyncService service;

    @BeforeEach
    void setUp() {
        priceRepo = Mockito.mock(MarketPriceRepository.class);
        queryRepo = Mockito.mock(IndustryQueryRepository.class);
        esiService = Mockito.mock(EsiService.class);
        service = new IndustrySyncService(
                Mockito.mock(CharacterRepository.class),
                Mockito.mock(AuthService.class),
                esiService,
                Mockito.mock(IndustryJobRepository.class),
                Mockito.mock(CharacterBlueprintRepository.class),
                priceRepo,
                Mockito.mock(IndustryStructureService.class),
                queryRepo);

        when(queryRepo.priceRelevantTypeIds()).thenReturn(List.of(TRITANIUM));
    }

    /** Der Preis von gestern, der schon in der Tabelle steht. */
    private MarketPrice bestehend() {
        MarketPrice alt = new MarketPrice();
        alt.setTypeId(TRITANIUM);
        alt.setJitaBuy(3.77);
        alt.setJitaSell(3.82);
        when(priceRepo.findById(TRITANIUM)).thenReturn(Optional.of(alt));
        return alt;
    }

    @SuppressWarnings("unchecked")
    private List<MarketPrice> gespeichert() {
        ArgumentCaptor<List<MarketPrice>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(priceRepo).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("überschreibt einen bekannten Preis nicht, wenn die Quelle ausfällt")
    void alterPreisBleibtStehen() {
        MarketPrice alt = bestehend();
        // Der beobachtete Ausfall: die Quelle antwortet, aber jede Zahl ist 0 -
        // bereinigt bleibt davon nichts übrig.
        when(esiService.getFuzzworkPrices(anyList())).thenReturn(Map.of());

        int geschrieben = service.syncIndustryPrices();

        // Nichts geschrieben, alter Wert unangetastet. Ohne diese Regel landete
        // die Null in der Tabelle: die frühere Verteidigung prüfte nur, ob die
        // Hülle da war, und eine Hülle mit dem Wert 0 ist nicht null. Genau so
        // stand Tritanium auf 0 ISK.
        assertThat(geschrieben).isZero();
        assertThat(alt.getJitaSell()).isEqualTo(3.82);
        assertThat(alt.getJitaBuy()).isEqualTo(3.77);
    }

    @Test
    @DisplayName("schreibt einen echten Preis weiterhin")
    void echterPreisWirdGeschrieben() {
        MarketPrice alt = bestehend();
        when(esiService.getFuzzworkPrices(anyList())).thenReturn(Map.of(
                String.valueOf(TRITANIUM),
                new FuzzworkPrice(new FuzzworkBuy(4.10), new FuzzworkSell(4.25))));

        int geschrieben = service.syncIndustryPrices();

        // Die Regel darf nur die Null fangen. Fängt sie mehr, friert der
        // Abgleich ein und niemand merkt es - derselbe Schaden, andere Richtung.
        assertThat(geschrieben).isEqualTo(1);
        assertThat(alt.getJitaSell()).isEqualTo(4.25);
        assertThat(gespeichert()).singleElement()
                .satisfies(p -> assertThat(p.getUpdatedAt()).isNotNull());
    }

    @Test
    @DisplayName("behält die eine Seite, für die es keinen Preis mehr gibt")
    void halbeAuskunftWirdUebernommen() {
        MarketPrice alt = bestehend();
        // Niemand bietet mehr, aber es gibt ein Verkaufsangebot. Das ist eine
        // echte Auskunft und kommt bei dünn gehandelten Typen laufend vor.
        when(esiService.getFuzzworkPrices(anyList())).thenReturn(Map.of(
                String.valueOf(TRITANIUM),
                new FuzzworkPrice(null, new FuzzworkSell(4.25))));

        service.syncIndustryPrices();

        // Der Verkaufspreis ist neu, das Kaufgebot bleibt das alte - statt auf
        // 0 zu fallen und damit jeden Bestand wertlos zu rechnen.
        assertThat(alt.getJitaSell()).isEqualTo(4.25);
        assertThat(alt.getJitaBuy()).isEqualTo(3.77);
    }

    @Test
    @DisplayName("legt für einen Typ ohne brauchbaren Preis keine Nullzeile an")
    void keineNullzeileFuerNeueTypen() {
        when(queryRepo.priceRelevantTypeIds()).thenReturn(List.of(TRITANIUM, PYERITE));
        when(priceRepo.findById(any())).thenReturn(Optional.empty());
        // Nur Pyerit hat einen Preis; Tritanium fehlt in der Antwort, weil die
        // Quelle dafür nur eine 0 hergab.
        when(esiService.getFuzzworkPrices(anyList())).thenReturn(Map.of(
                String.valueOf(PYERITE),
                new FuzzworkPrice(new FuzzworkBuy(12.0), new FuzzworkSell(12.5))));

        service.syncIndustryPrices();

        // Ohne diese Regel entstünde für Tritanium eine Zeile mit jita_sell = 0.
        // Die ist schlimmer als gar keine: "kein Preis vorhanden" ist eine
        // ehrliche Auskunft, "kostet 0 ISK" ist eine falsche - und genau daraus
        // wurden die 6.698 Nullzeilen in market_prices.
        assertThat(gespeichert()).singleElement()
                .satisfies(p -> assertThat(p.getTypeId()).isEqualTo(PYERITE));
    }
}
