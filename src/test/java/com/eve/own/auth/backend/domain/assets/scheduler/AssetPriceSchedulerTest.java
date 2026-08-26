package com.eve.own.auth.backend.domain.assets.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.eve.own.auth.backend.domain.assets.entity.MarketPrice;
import com.eve.own.auth.backend.domain.assets.repository.MarketPriceRepository;
import com.eve.own.auth.backend.domain.assets.service.AssetLocationService;
import com.eve.own.auth.backend.domain.character.repository.CharacterAssetRepository;
import com.eve.own.auth.backend.domain.market.MarketSnapshot;
import com.eve.own.auth.backend.domain.market.StationPrice;
import com.eve.own.auth.backend.testsupport.LogCapture;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Der Asset-Preislauf schreibt {@code market_prices} aus dem Marktabzug.
 *
 * <p>Zu dieser Klasse gab es bis hierher keinen einzigen Test - obwohl sie
 * dieselbe Verteidigung traegt wie der Industrie-Preislauf, der einen ganzen
 * Satz davon hat. Genau hier landeten die 6.698 Nullzeilen.</p>
 */
class AssetPriceSchedulerTest {

    private static final long TRITANIUM = 34L;
    private static final long PYERITE = 35L;
    private static final long JITA_44 = 60_003_760L;

    private CharacterAssetRepository assetRepo;
    private MarketPriceRepository priceRepo;
    private AssetPriceScheduler scheduler;

    @BeforeEach
    void setUp() {
        assetRepo = mock(CharacterAssetRepository.class);
        priceRepo = mock(MarketPriceRepository.class);
        scheduler = new AssetPriceScheduler(assetRepo, priceRepo, mock(AssetLocationService.class));
        when(priceRepo.findById(any())).thenReturn(Optional.empty());
    }

    private static MarketSnapshot abzug(Map<Long, StationPrice> preise) {
        return new MarketSnapshot(preise, JITA_44, Instant.now());
    }

    @SuppressWarnings("unchecked")
    private List<MarketPrice> gespeichert() {
        ArgumentCaptor<List<MarketPrice>> captor = ArgumentCaptor.forClass(List.class);
        verify(priceRepo).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("schreibt beide Seiten aus dem Abzug")
    void schreibtBeideSeiten() {
        when(assetRepo.findDistinctAssetTypeIds()).thenReturn(List.of(TRITANIUM));

        int geschrieben = scheduler.updateAssetPrices(
                abzug(Map.of(TRITANIUM, new StationPrice(3.77, 3.85))));

        assertThat(geschrieben).isEqualTo(1);
        assertThat(gespeichert()).singleElement().satisfies(p -> {
            assertThat(p.getJitaBuy()).isEqualTo(3.77);
            assertThat(p.getJitaSell()).isEqualTo(3.85);
            assertThat(p.getUpdatedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("legt fuer einen Typ ohne Order an der Station keine Nullzeile an")
    void keineNullzeileOhneOrder() {
        when(assetRepo.findDistinctAssetTypeIds()).thenReturn(List.of(TRITANIUM, PYERITE));

        scheduler.updateAssetPrices(abzug(Map.of(PYERITE, new StationPrice(12.0, 12.5))));

        // Gemessen haben 488 von 17.373 gehandelten Typen in Jita 4-4 keine
        // Verkaufsorder. Ohne diese Zeile bekaeme jeder von ihnen eine Zeile
        // mit 0 ISK - und ein Bestand aus lauter Nullen sieht aus wie ein
        // wertloser Hangar, kein wie eine fehlende Auskunft.
        assertThat(gespeichert()).singleElement()
                .satisfies(p -> assertThat(p.getTypeId()).isEqualTo(PYERITE));
    }

    @Test
    @DisplayName("laesst den alten Wert der Seite stehen, die der Abzug nicht kennt")
    void alteSeiteBleibtStehen() {
        MarketPrice alt = new MarketPrice();
        alt.setTypeId(TRITANIUM);
        alt.setJitaBuy(3.77);
        alt.setJitaSell(3.82);
        when(assetRepo.findDistinctAssetTypeIds()).thenReturn(List.of(TRITANIUM));
        when(priceRepo.findById(TRITANIUM)).thenReturn(Optional.of(alt));

        scheduler.updateAssetPrices(abzug(Map.of(TRITANIUM, new StationPrice(null, 4.25))));

        // Niemand bietet mehr, aber es gibt ein Verkaufsangebot. Ohne diese
        // Zeile fiele jita_buy auf null zurueck und der ganze Hangar waere
        // beim Verkauf schlagartig nichts mehr wert.
        assertThat(alt.getJitaSell()).isEqualTo(4.25);
        assertThat(alt.getJitaBuy()).isEqualTo(3.77);
    }

    @Test
    @DisplayName("zaehlt in der Meldung Typen mit brauchbarem Preis, nicht die abgefragten")
    void meldungZaehltBrauchbarePreise() {
        when(assetRepo.findDistinctAssetTypeIds()).thenReturn(List.of(TRITANIUM, PYERITE));

        try (LogCapture protokoll = new LogCapture(AssetPriceScheduler.class)) {
            scheduler.updateAssetPrices(abzug(Map.of(PYERITE, new StationPrice(12.0, 12.5))));

            // Die alte Meldung lautete "2165 Typen gespeichert, 0 Batches
            // fehlgeschlagen" - und stand so auch dann da, wenn in jeder Zeile
            // eine 0 stand. Wer die Zahl liest, muss sehen, wie viele Typen
            // wirklich einen Preis haben und wie viele keinen.
            assertThat(protokoll.meldungen(Level.INFO))
                    .singleElement(InstanceOfAssertFactories.STRING)
                    .contains("1 von 2 Typen mit brauchbarem Jita-Preis")
                    .contains("1 ohne Order an der Station");
        }
    }

    @Test
    @DisplayName("macht ohne Assets gar nichts")
    void ohneAssetsPassiertNichts() {
        when(assetRepo.findDistinctAssetTypeIds()).thenReturn(List.of());

        assertThat(scheduler.updateAssetPrices(abzug(Map.of()))).isZero();

        verify(priceRepo, org.mockito.Mockito.never()).saveAll(any());
    }
}
