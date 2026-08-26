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
import com.eve.own.auth.backend.domain.market.MarketSnapshot;
import com.eve.own.auth.backend.domain.market.StationPrice;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxRate;
import com.eve.own.auth.backend.domain.mining.repository.MiningTaxRateRepository;
import java.math.BigDecimal;
import java.time.Instant;
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

/**
 * Abgleich der Jita-Referenzpreise.
 *
 * <p><b>Was sich gegenueber der Fuzzwork-Fassung geaendert hat:</b> die Preise
 * werden nicht mehr geholt, sondern gereicht. Alle Aussagen ueber die
 * <em>Preiswahl</em> und die <em>Rueckfallebene</em> gelten unveraendert und
 * stehen deshalb Wort fuer Wort noch hier. Eine Aussage gilt nicht mehr: "holt
 * die Preise gar nicht erst, wenn keine Steuersaetze hinterlegt sind" - es gibt
 * keinen Abruf mehr, den man unterlassen koennte. Uebrig bleibt davon der Teil,
 * der weiterhin zaehlt: dann wird auch nichts gespeichert.</p>
 *
 * <p>Eine Aussage hat sich geschaerft: der Fall "Kaufgebot 0, also
 * Verkaufsangebot nehmen" kann so gar nicht mehr auftreten, weil der Abzug
 * Nullen bereits an der Quelle streicht. Die Regel dahinter - kein Kaufgebot,
 * also das Verkaufsangebot - gilt weiter und wird jetzt mit {@code null}
 * geprueft statt mit 0. Das ist genau der Unterschied, um den es geht.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Abgleich der Jita-Referenzpreise")
class MiningPriceServiceTest {

    private static final Long VELDSPAR = 1230L;
    private static final Long COMPRESSED_VELDSPAR = 28430L;
    private static final long JITA_44 = 60_003_760L;

    @Mock private MiningTaxRateRepository taxRateRepo;
    @Mock private InvTypeRepository invTypeRepo;

    private MiningPriceService service;

    @BeforeEach
    void setUp() {
        service = new MiningPriceService(taxRateRepo, invTypeRepo);
        when(invTypeRepo.findByTypeNameIgnoreCase(anyString())).thenReturn(Optional.empty());
    }

    private static MiningTaxRate rate(Long typeId, String typeName) {
        MiningTaxRate rate = new MiningTaxRate();
        rate.setTypeId(typeId);
        rate.setTypeName(typeName);
        rate.setCurrentJitaBuy(BigDecimal.ZERO);
        return rate;
    }

    private static MarketSnapshot abzug(Map<Long, StationPrice> preise) {
        return new MarketSnapshot(preise, JITA_44, Instant.now());
    }

    private static MarketSnapshot leererAbzug() {
        return abzug(Map.of());
    }

    @Nested
    @DisplayName("Preisermittlung")
    class PriceSelection {

        @Test
        @DisplayName("bevorzugt das hoechste Kaufgebot")
        void prefersBuyOrder() {
            MiningTaxRate veldspar = rate(VELDSPAR, "Veldspar");
            when(taxRateRepo.findAll()).thenReturn(List.of(veldspar));

            service.refreshJitaPrices(abzug(Map.of(VELDSPAR, new StationPrice(12.0, 20.0))));

            // Das Kaufgebot ist der Betrag, den ein Spieler sofort erloesen
            // kann. Ohne diese Reihenfolge waere die Abgabe auf einen Preis
            // bemessen, den niemand zahlt.
            assertThat(veldspar.getCurrentJitaBuy()).isEqualByComparingTo("12.00");
        }

        @Test
        @DisplayName("weicht auf das guenstigste Verkaufsangebot aus, wenn niemand kauft")
        void fallsBackToSellOrder() {
            MiningTaxRate veldspar = rate(VELDSPAR, "Veldspar");
            when(taxRateRepo.findAll()).thenReturn(List.of(veldspar));

            // Kein Kaufgebot, also null - nicht 0. Der Abzug streicht Nullen
            // schon an der Quelle; genau das ist der Unterschied zwischen
            // "niemand bietet" und "ist nichts wert".
            service.refreshJitaPrices(abzug(Map.of(VELDSPAR, new StationPrice(null, 20.0))));

            assertThat(veldspar.getCurrentJitaBuy()).isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("laesst den Preis unangetastet, wenn der Markt gar nichts hergibt")
        void keepsPriceWhenMarketIsSilent() {
            MiningTaxRate veldspar = rate(VELDSPAR, "Veldspar");
            veldspar.setCurrentJitaBuy(new BigDecimal("99.00"));
            when(taxRateRepo.findAll()).thenReturn(List.of(veldspar));

            service.refreshJitaPrices(leererAbzug());

            // Ohne diese Zeile faellt der Steuersatz auf 0 - und damit waere
            // jede Menge Erz steuerfrei, ohne dass es jemandem auffiele.
            assertThat(veldspar.getCurrentJitaBuy()).isEqualByComparingTo("99.00");
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

            InvType compressed = new InvType();
            compressed.setTypeId(COMPRESSED_VELDSPAR);
            when(invTypeRepo.findByTypeNameIgnoreCase("Compressed Veldspar"))
                    .thenReturn(Optional.of(compressed));

            // Veldspar selbst wird in Jita nicht gehandelt und fehlt deshalb im
            // Abzug; die komprimierte Form steht drin.
            service.refreshJitaPrices(abzug(Map.of(COMPRESSED_VELDSPAR, new StationPrice(55.0, null))));

            assertThat(veldspar.getCurrentJitaBuy()).isEqualByComparingTo("55.00");
        }

        @Test
        @DisplayName("kennt auch die Batch-Schreibweise der SDE")
        void findsBatchCompressedVariant() {
            MiningTaxRate ice = rate(VELDSPAR, "White Glaze");
            when(taxRateRepo.findAll()).thenReturn(List.of(ice));

            InvType compressed = new InvType();
            compressed.setTypeId(COMPRESSED_VELDSPAR);
            when(invTypeRepo.findByTypeNameIgnoreCase("Batch Compressed White Glaze"))
                    .thenReturn(Optional.of(compressed));

            service.refreshJitaPrices(abzug(Map.of(COMPRESSED_VELDSPAR, new StationPrice(77.0, null))));

            // Ohne den zweiten Praefix bekaeme das ganze Eis keinen Preis - die
            // SDE fuehrt es nur unter "Batch Compressed".
            assertThat(ice.getCurrentJitaBuy()).isEqualByComparingTo("77.00");
        }

        @Test
        @DisplayName("kommt ohne komprimierte Variante zurecht")
        void survivesMissingCompressedVariant() {
            MiningTaxRate exotic = rate(VELDSPAR, "Exotisches Erz");
            when(taxRateRepo.findAll()).thenReturn(List.of(exotic));

            service.refreshJitaPrices(leererAbzug());

            assertThat(exotic.getCurrentJitaBuy()).isZero();
            verify(taxRateRepo).saveAll(anyList());
        }

        @Test
        @DisplayName("ignoriert einen Steuersatz ohne Typnamen")
        void skipsRateWithoutTypeName() {
            MiningTaxRate nameless = rate(VELDSPAR, null);
            when(taxRateRepo.findAll()).thenReturn(List.of(nameless));

            service.refreshJitaPrices(leererAbzug());

            // Ohne die Namenspruefung sucht die Rueckfallebene nach
            // "Compressed null" - eine sinnlose Abfrage je Steuersatz.
            verify(invTypeRepo, never()).findByTypeNameIgnoreCase(any());
        }
    }

    @Test
    @DisplayName("macht ohne hinterlegte Steuersaetze gar nichts")
    void doesNothingWithoutRates() {
        when(taxRateRepo.findAll()).thenReturn(List.of());

        service.refreshJitaPrices(leererAbzug());

        verify(taxRateRepo, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("speichert alle Saetze, auch die unveraenderten")
    void savesAllRates() {
        when(taxRateRepo.findAll()).thenReturn(List.of(rate(VELDSPAR, "Veldspar")));

        service.refreshJitaPrices(leererAbzug());

        verify(taxRateRepo).saveAll(anyList());
    }
}
