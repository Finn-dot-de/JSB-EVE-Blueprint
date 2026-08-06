package com.eve.own.auth.backend.domain.mining.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.eve.entity.InvType;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxRate;
import com.eve.own.auth.backend.domain.mining.repository.MiningTaxRateRepository;
import com.eve.own.auth.backend.esi.EsiService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Abgleich der Jita-Referenzpreise")
class MiningPriceServiceTest {

    private static final Long VELDSPAR = 1230L;
    private static final Long COMPRESSED_VELDSPAR = 28430L;

    @Mock private EsiService esiService;
    @Mock private MiningTaxRateRepository taxRateRepo;
    @Mock private InvTypeRepository invTypeRepo;

    private MiningPriceService service;

    @BeforeEach
    void setUp() {
        service = new MiningPriceService(esiService, taxRateRepo, invTypeRepo);
        when(invTypeRepo.findByTypeNameIgnoreCase(anyString())).thenReturn(Optional.empty());
    }

    private static MiningTaxRate rate(Long typeId, String typeName) {
        MiningTaxRate rate = new MiningTaxRate();
        rate.setTypeId(typeId);
        rate.setTypeName(typeName);
        rate.setCurrentJitaBuy(0.0);
        return rate;
    }

    private static EsiService.FuzzworkPrice price(Double buyMax, Double sellMin) {
        return new EsiService.FuzzworkPrice(
                new EsiService.FuzzworkBuy(buyMax), new EsiService.FuzzworkSell(sellMin));
    }

    @Nested
    @DisplayName("Preisermittlung")
    class PriceSelection {

        @Test
        @DisplayName("bevorzugt das hoechste Kaufgebot")
        void prefersBuyOrder() {
            MiningTaxRate veldspar = rate(VELDSPAR, "Veldspar");
            when(taxRateRepo.findAll()).thenReturn(List.of(veldspar));
            when(esiService.getFuzzworkPrices(anyList()))
                    .thenReturn(Map.of(String.valueOf(VELDSPAR), price(12.0, 20.0)));

            service.refreshJitaPrices();

            assertThat(veldspar.getCurrentJitaBuy()).isEqualTo(12.0);
        }

        @Test
        @DisplayName("weicht auf das guenstigste Verkaufsangebot aus, wenn niemand kauft")
        void fallsBackToSellOrder() {
            MiningTaxRate veldspar = rate(VELDSPAR, "Veldspar");
            when(taxRateRepo.findAll()).thenReturn(List.of(veldspar));
            when(esiService.getFuzzworkPrices(anyList()))
                    .thenReturn(Map.of(String.valueOf(VELDSPAR), price(0.0, 20.0)));

            service.refreshJitaPrices();

            assertThat(veldspar.getCurrentJitaBuy()).isEqualTo(20.0);
        }

        @Test
        @DisplayName("laesst den Preis unangetastet, wenn der Markt gar nichts hergibt")
        void keepsPriceWhenMarketIsSilent() {
            MiningTaxRate veldspar = rate(VELDSPAR, "Veldspar");
            veldspar.setCurrentJitaBuy(99.0);
            when(taxRateRepo.findAll()).thenReturn(List.of(veldspar));
            when(esiService.getFuzzworkPrices(anyList()))
                    .thenReturn(Map.of(String.valueOf(VELDSPAR), price(null, null)));

            service.refreshJitaPrices();

            assertThat(veldspar.getCurrentJitaBuy()).isEqualTo(99.0);
        }
    }

    @Nested
    @DisplayName("Rueckfallebene ueber die komprimierte Variante")
    class CompressedFallback {

        @Test
        @DisplayName("uebernimmt den Preis der komprimierten Form eins zu eins")
        void usesCompressedPrice() {
            MiningTaxRate veldspar = rate(VELDSPAR, "Veldspar");
            when(taxRateRepo.findAll()).thenReturn(List.of(veldspar));
            when(esiService.getFuzzworkPrices(List.of(VELDSPAR)))
                    .thenReturn(Map.of(String.valueOf(VELDSPAR), price(0.0, 0.0)));

            InvType compressed = new InvType();
            compressed.setTypeId(COMPRESSED_VELDSPAR);
            when(invTypeRepo.findByTypeNameIgnoreCase("Compressed Veldspar"))
                    .thenReturn(Optional.of(compressed));
            when(esiService.getFuzzworkPrices(List.of(COMPRESSED_VELDSPAR)))
                    .thenReturn(Map.of(String.valueOf(COMPRESSED_VELDSPAR), price(55.0, null)));

            service.refreshJitaPrices();

            assertThat(veldspar.getCurrentJitaBuy()).isEqualTo(55.0);
        }

        @Test
        @DisplayName("kennt auch die Batch-Schreibweise der SDE")
        void findsBatchCompressedVariant() {
            MiningTaxRate ice = rate(VELDSPAR, "White Glaze");
            when(taxRateRepo.findAll()).thenReturn(List.of(ice));
            when(esiService.getFuzzworkPrices(List.of(VELDSPAR))).thenReturn(Map.of());

            InvType compressed = new InvType();
            compressed.setTypeId(COMPRESSED_VELDSPAR);
            when(invTypeRepo.findByTypeNameIgnoreCase("Batch Compressed White Glaze"))
                    .thenReturn(Optional.of(compressed));
            when(esiService.getFuzzworkPrices(List.of(COMPRESSED_VELDSPAR)))
                    .thenReturn(Map.of(String.valueOf(COMPRESSED_VELDSPAR), price(77.0, null)));

            service.refreshJitaPrices();

            assertThat(ice.getCurrentJitaBuy()).isEqualTo(77.0);
        }

        @Test
        @DisplayName("kommt ohne komprimierte Variante zurecht")
        void survivesMissingCompressedVariant() {
            MiningTaxRate exotic = rate(VELDSPAR, "Exotisches Erz");
            when(taxRateRepo.findAll()).thenReturn(List.of(exotic));
            when(esiService.getFuzzworkPrices(anyList())).thenReturn(Map.of());

            service.refreshJitaPrices();

            assertThat(exotic.getCurrentJitaBuy()).isZero();
            verify(taxRateRepo).saveAll(anyList());
        }

        @Test
        @DisplayName("ignoriert einen Steuersatz ohne Typnamen")
        void skipsRateWithoutTypeName() {
            MiningTaxRate nameless = rate(VELDSPAR, null);
            when(taxRateRepo.findAll()).thenReturn(List.of(nameless));
            when(esiService.getFuzzworkPrices(anyList())).thenReturn(Map.of());

            service.refreshJitaPrices();

            verify(invTypeRepo, never()).findByTypeNameIgnoreCase(any());
        }
    }

    @Test
    @DisplayName("macht ohne hinterlegte Steuersaetze gar nichts")
    void doesNothingWithoutRates() {
        when(taxRateRepo.findAll()).thenReturn(List.of());

        service.refreshJitaPrices();

        verify(esiService, never()).getFuzzworkPrices(anyList());
        verify(taxRateRepo, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("speichert alle Saetze, auch die unveraenderten")
    void savesAllRates() {
        when(taxRateRepo.findAll()).thenReturn(List.of(rate(VELDSPAR, "Veldspar")));
        when(esiService.getFuzzworkPrices(anyList())).thenReturn(null);

        service.refreshJitaPrices();

        verify(taxRateRepo).saveAll(anyList());
    }
}
