package com.eve.own.auth.backend.domain.industry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.assets.entity.MarketPrice;
import com.eve.own.auth.backend.domain.assets.repository.MarketPriceRepository;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.industry.repository.CharacterBlueprintRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryJobRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository;
import com.eve.own.auth.backend.domain.market.MarketSnapshot;
import com.eve.own.auth.backend.domain.market.StationPrice;
import com.eve.own.auth.backend.esi.EsiService;
import java.time.Instant;
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
 * <p>Die Preise kommen hier als fertiger Marktabzug herein, so wie
 * {@code MarketSnapshotService} ihn liefert: ein Typ ohne Order an der Station
 * fehlt in der Karte, eine Seite ohne Angebot ist {@code null}. Getestet wird
 * also der Schreibvorgang gegen diesen Vertrag - dass der Abzug selbst richtig
 * gebildet wird, steht in {@code MarketSnapshotServiceTest}.</p>
 *
 * <p><b>Was sich gegenueber der Fuzzwork-Fassung geaendert hat:</b> nur die
 * Quelle der Preise. Die vier Zusicherungen betreffen den Schreibvorgang und
 * gelten unveraendert weiter - deshalb sind sie Wort fuer Wort dieselben
 * geblieben. Weggefallen ist allein der Fall "die Quelle liefert einen ganzen
 * Block nicht": Bloecke gibt es nicht mehr, weil der Abzug in einem Stueck
 * kommt. An seine Stelle tritt der Fall "der Abzug entsteht gar nicht erst",
 * und der wird eine Ebene hoeher geprueft, im {@code MarketPriceScheduler} -
 * dort kommt diese Methode dann naemlich ueberhaupt nicht mehr zum Zug.</p>
 */
class IndustryPreisAusfallTest {

    private static final long TRITANIUM = 34L;
    private static final long PYERITE = 35L;
    private static final long JITA_44 = 60_003_760L;

    private MarketPriceRepository priceRepo;
    private IndustryQueryRepository queryRepo;
    private IndustrySyncService service;

    @BeforeEach
    void setUp() {
        priceRepo = Mockito.mock(MarketPriceRepository.class);
        queryRepo = Mockito.mock(IndustryQueryRepository.class);
        service = new IndustrySyncService(
                Mockito.mock(CharacterRepository.class),
                Mockito.mock(AuthService.class),
                Mockito.mock(EsiService.class),
                Mockito.mock(IndustryJobRepository.class),
                Mockito.mock(CharacterBlueprintRepository.class),
                priceRepo,
                Mockito.mock(IndustryStructureService.class),
                queryRepo);

        when(queryRepo.priceRelevantTypeIds()).thenReturn(List.of(TRITANIUM));
    }

    private static MarketSnapshot abzug(Map<Long, StationPrice> preise) {
        return new MarketSnapshot(preise, JITA_44, Instant.now());
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
        int geschrieben = service.syncIndustryPrices(abzug(Map.of()));

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

        int geschrieben = service.syncIndustryPrices(
                abzug(Map.of(TRITANIUM, new StationPrice(4.10, 4.25))));

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
        service.syncIndustryPrices(abzug(Map.of(TRITANIUM, new StationPrice(null, 4.25))));

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
        // Nur Pyerit hat einen Preis; Tritanium fehlt im Abzug, weil es an der
        // Station keine Order dafür gibt.
        service.syncIndustryPrices(abzug(Map.of(PYERITE, new StationPrice(12.0, 12.5))));

        // Ohne diese Regel entstünde für Tritanium eine Zeile mit jita_sell = 0.
        // Die ist schlimmer als gar keine: "kein Preis vorhanden" ist eine
        // ehrliche Auskunft, "kostet 0 ISK" ist eine falsche - und genau daraus
        // wurden die 6.698 Nullzeilen in market_prices.
        assertThat(gespeichert()).singleElement()
                .satisfies(p -> assertThat(p.getTypeId()).isEqualTo(PYERITE));
    }
}
