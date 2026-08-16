package com.eve.buy.bot.backend.domain.buybot.service;

import com.eve.buy.bot.backend.domain.buybot.dto.MarketPriceDto;
import com.eve.buy.bot.backend.domain.buybot.dto.ParsedItemDto;
import com.eve.buy.bot.backend.domain.buybot.dto.ReprocessMaterialProjection;
import com.eve.buy.bot.backend.domain.buybot.dto.TypeDetailsProjection;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackCategoryRule;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackConfig;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackLocation;
import com.eve.buy.bot.backend.domain.buybot.entity.BuybackTypeRule;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackCategoryRuleRepository;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackConfigRepository;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackLocationRepository;
import com.eve.buy.bot.backend.domain.buybot.repository.BuybackTypeRuleRepository;
import com.eve.buy.bot.backend.domain.eve.repository.InvTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests der Preis-Engine.
 *
 * <p>Hier entscheidet sich, wie viel ISK ausgezahlt wird. Die Tests halten die Reihenfolge
 * der Regeln fest: Sperre schlägt alles, Einzelitem schlägt Kategorie, Kategorie schlägt
 * Standard - und die Gebühren werden erst danach abgezogen.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BuybackCalculationService")
class BuybackCalculationServiceTest {

    private static final long TRITANIUM = 34L;
    private static final long MINERAL_CATEGORY = 4L;

    @Mock private MarketService marketService;
    @Mock private BuybackConfigRepository configRepo;
    @Mock private BuybackLocationRepository locationRepo;
    @Mock private BuybackTypeRuleRepository typeRuleRepo;
    @Mock private BuybackCategoryRuleRepository categoryRuleRepo;
    @Mock private InvTypeRepository invTypeRepo;

    private BuybackCalculationService service;
    private BuybackConfig config;
    private BuybackLocation location;

    @BeforeEach
    void setUp() {
        service = new BuybackCalculationService(marketService, configRepo, locationRepo,
                typeRuleRepo, categoryRuleRepo, invTypeRepo);

        config = new BuybackConfig();
        config.setPriceBasis("buy");
        config.setGlobalModifier(90.0);
        config.setReprocessingRate(50.0);

        location = new BuybackLocation();
        location.setId(1L);
        location.setName("Teststation");
        location.setTransportFee(0.0);
        location.setSecurityFee(0.0);

        lenient().when(configRepo.findById(1L)).thenReturn(Optional.of(config));
        lenient().when(locationRepo.findById(1L)).thenReturn(Optional.of(location));
        lenient().when(typeRuleRepo.findById(anyLong())).thenReturn(Optional.empty());
        lenient().when(categoryRuleRepo.findById(anyLong())).thenReturn(Optional.empty());
        lenient().when(marketService.getJitaPrices(any())).thenReturn(Map.of(TRITANIUM, price(4.0, 5.0)));
    }

    @Nested
    @DisplayName("Whitelist")
    class Whitelist {

        @Test
        @DisplayName("lehnt ab, was weder als Kategorie noch als Item freigegeben ist")
        void rejectsUnlistedItems() {
            ParsedItemDto item = tritanium(100);

            service.calculatePrices(List.of(item), 1L);

            assertThat(item.getStatusCode()).isEqualTo(BuybackCalculationService.STATUS_NOT_LISTED);
            assertThat(item.getTotalPrice()).isZero();
        }

        @Test
        @DisplayName("kauft an, was über die Kategorie freigegeben ist")
        void acceptsItemsAllowedByCategory() {
            allowCategory(MINERAL_CATEGORY, 90.0);
            ParsedItemDto item = tritanium(100);

            service.calculatePrices(List.of(item), 1L);

            assertThat(item.getStatusCode()).isEqualTo(BuybackCalculationService.STATUS_OK);
            assertThat(item.getTotalPrice()).isEqualTo(360.0); // 4,00 Jita-Buy * 90 % * 100
        }

        @Test
        @DisplayName("sperrt ein Item auch dann, wenn seine Kategorie erlaubt ist")
        void blacklistedItemBeatsAllowedCategory() {
            allowCategory(MINERAL_CATEGORY, 90.0);
            BuybackTypeRule blocked = new BuybackTypeRule();
            blocked.setTypeId(TRITANIUM);
            blocked.setIsBlacklisted(true);
            when(typeRuleRepo.findById(TRITANIUM)).thenReturn(Optional.of(blocked));

            ParsedItemDto item = tritanium(100);
            service.calculatePrices(List.of(item), 1L);

            assertThat(item.getStatusCode()).isEqualTo(BuybackCalculationService.STATUS_BLOCKED);
            assertThat(item.getTotalPrice()).isZero();
        }

        @Test
        @DisplayName("kauft ein Einzelitem auch ohne freigegebene Kategorie an")
        void singleItemRuleAllowsWithoutCategory() {
            allowType(TRITANIUM, 80.0);

            ParsedItemDto item = tritanium(100);
            service.calculatePrices(List.of(item), 1L);

            assertThat(item.getStatusCode()).isEqualTo(BuybackCalculationService.STATUS_OK);
            assertThat(item.getAppliedModifier()).isEqualTo(80.0);
        }

        @Test
        @DisplayName("meldet nicht erkannte Namen als unbekannt")
        void marksUnresolvedItemsAsUnknown() {
            ParsedItemDto item = new ParsedItemDto();
            item.setRawName("Trittanium");
            item.addQuantity(5);

            service.calculatePrices(List.of(item), 1L);

            assertThat(item.getStatusCode()).isEqualTo(BuybackCalculationService.STATUS_UNKNOWN);
            assertThat(item.getTotalPrice()).isZero();
        }
    }

    @Nested
    @DisplayName("Modifikator")
    class Modifier {

        @Test
        @DisplayName("nimmt den Standard, wenn nur die Kategorie ohne eigenen Wert freigegeben ist")
        void fallsBackToGlobalModifier() {
            allowCategory(MINERAL_CATEGORY, null);

            ParsedItemDto item = tritanium(100);
            service.calculatePrices(List.of(item), 1L);

            assertThat(item.getAppliedModifier()).isEqualTo(90.0);
        }

        @Test
        @DisplayName("lässt den Item-Modifikator den der Kategorie überschreiben")
        void itemModifierOverridesCategory() {
            allowCategory(MINERAL_CATEGORY, 85.0);
            allowType(TRITANIUM, 95.0);

            ParsedItemDto item = tritanium(100);
            service.calculatePrices(List.of(item), 1L);

            assertThat(item.getAppliedModifier()).isEqualTo(95.0);
            assertThat(item.getTotalPrice()).isEqualTo(380.0); // 4,00 * 95 % * 100
        }

        @Test
        @DisplayName("rechnet auf Wunsch mit dem Jita-Verkaufspreis statt dem Kaufgebot")
        void usesSellPriceWhenConfigured() {
            config.setPriceBasis("sell");
            allowCategory(MINERAL_CATEGORY, 100.0);

            ParsedItemDto item = tritanium(10);
            service.calculatePrices(List.of(item), 1L);

            assertThat(item.getTotalPrice()).isEqualTo(50.0); // 5,00 Jita-Sell * 100 % * 10
        }
    }

    @Nested
    @DisplayName("Gebühren")
    class Fees {

        @Test
        @DisplayName("zieht die Transportgebühr je Kubikmeter ab")
        void subtractsTransportFeePerVolume() {
            allowCategory(MINERAL_CATEGORY, 100.0);
            location.setTransportFee(100.0); // 100 ISK je m3, Tritanium hat 0,01 m3 -> 1 ISK je Einheit

            ParsedItemDto item = tritanium(10);
            service.calculatePrices(List.of(item), 1L);

            assertThat(item.getUnitPrice()).isEqualTo(3.0); // 4,00 - 1,00
            assertThat(item.getTotalPrice()).isEqualTo(30.0);
        }

        @Test
        @DisplayName("zieht die Sicherheitsgebühr vom Warenwert ab")
        void subtractsSecurityFeeFromValue() {
            allowCategory(MINERAL_CATEGORY, 100.0);
            location.setSecurityFee(10.0); // 10 % vom Jita-Sell (5,00) = 0,50 je Einheit

            ParsedItemDto item = tritanium(10);
            service.calculatePrices(List.of(item), 1L);

            assertThat(item.getUnitPrice()).isEqualTo(3.5); // 4,00 - 0,50
        }

        @Test
        @DisplayName("zahlt nie weniger als null, wenn die Gebühren den Preis übersteigen")
        void neverPaysNegativePrices() {
            allowCategory(MINERAL_CATEGORY, 100.0);
            location.setTransportFee(100000.0);

            ParsedItemDto item = tritanium(10);
            service.calculatePrices(List.of(item), 1L);

            assertThat(item.getUnitPrice()).isZero();
            assertThat(item.getTotalPrice()).isZero();
        }
    }

    @Nested
    @DisplayName("Reprocessing")
    class Reprocessing {

        @Test
        @DisplayName("bewertet Erz über die Ausbeute und rechnet die Portionsgröße heraus")
        void valuesOreByReprocessingYield() {
            long veldspar = 1230L;
            BuybackCategoryRule rule = new BuybackCategoryRule();
            rule.setCategoryId(25L);
            rule.setModifier(100.0);
            rule.setUseReprocessedValue(true);
            when(categoryRuleRepo.findById(25L)).thenReturn(Optional.of(rule));

            // Veldspar: 400 Tritanium je 100 Einheiten = 4 je Einheit, bei 50 % Ausbeute also 2
            when(invTypeRepo.findReprocessMaterials(Set.of(veldspar)))
                    .thenReturn(List.of(new Yield(veldspar, TRITANIUM, 400L, 100)));
            when(marketService.getJitaPrices(any())).thenReturn(Map.of(TRITANIUM, price(4.0, 5.0)));

            ParsedItemDto item = new ParsedItemDto();
            item.setTypeId(veldspar);
            item.setRawName("Veldspar");
            item.setCategoryId(25L);
            item.setVolumeEach(0.1);
            item.setResolved(true);
            item.addQuantity(100);

            service.calculatePrices(List.of(item), 1L);

            assertThat(item.getPriceSource()).isEqualTo(BuybackCalculationService.SOURCE_REPROCESSED);
            assertThat(item.getUnitPrice()).isEqualTo(8.0); // 2 Tritanium * 4,00 ISK
            assertThat(item.getTotalPrice()).isEqualTo(800.0);
        }

        @Test
        @DisplayName("berechnet die Transportgebühr aus dem Volumen der Ausbeute")
        void chargesTransportOnReprocessedVolume() {
            long veldspar = 1230L;
            BuybackCategoryRule rule = new BuybackCategoryRule();
            rule.setCategoryId(25L);
            rule.setModifier(100.0);
            rule.setUseReprocessedValue(true);
            when(categoryRuleRepo.findById(25L)).thenReturn(Optional.of(rule));

            // Veldspar: 400 Tritanium je 100 Einheiten = 4 je Einheit, bei 50 % also 2.
            // Tritanium hat 0,01 m3 -> 0,02 m3 Ausbeute, das Erz selbst dagegen 0,1 m3.
            when(invTypeRepo.findReprocessMaterials(Set.of(veldspar)))
                    .thenReturn(List.of(new Yield(veldspar, TRITANIUM, 400L, 100)));
            when(invTypeRepo.findTypeDetailsByIds(Set.of(TRITANIUM)))
                    .thenReturn(List.of(new TypeDetails(TRITANIUM, "Tritanium", 0.01, MINERAL_CATEGORY)));
            when(marketService.getJitaPrices(any())).thenReturn(Map.of(TRITANIUM, price(4.0, 5.0)));

            location.setTransportFee(100.0); // 100 ISK je m3

            ParsedItemDto item = veldspar(veldspar, 100);
            service.calculatePrices(List.of(item), 1L);

            // 8,00 ISK Ausbeutewert minus 0,02 m3 * 100 ISK = 2,00 ISK Transport
            assertThat(item.getUnitPrice()).isEqualTo(6.0);
        }

        @Test
        @DisplayName("nimmt nicht das Volumen des Ausgangsitems, wenn über die Ausbeute bewertet wird")
        void ignoresRawVolumeWhenPricedByYield() {
            long veldspar = 1230L;
            BuybackCategoryRule rule = new BuybackCategoryRule();
            rule.setCategoryId(25L);
            rule.setModifier(100.0);
            rule.setUseReprocessedValue(true);
            when(categoryRuleRepo.findById(25L)).thenReturn(Optional.of(rule));

            when(invTypeRepo.findReprocessMaterials(Set.of(veldspar)))
                    .thenReturn(List.of(new Yield(veldspar, TRITANIUM, 400L, 100)));
            when(invTypeRepo.findTypeDetailsByIds(Set.of(TRITANIUM)))
                    .thenReturn(List.of(new TypeDetails(TRITANIUM, "Tritanium", 0.01, MINERAL_CATEGORY)));
            when(marketService.getJitaPrices(any())).thenReturn(Map.of(TRITANIUM, price(4.0, 5.0)));

            location.setTransportFee(100.0);

            // Dasselbe Item einmal mit sehr grossem Rohvolumen - am Preis darf sich nichts aendern
            ParsedItemDto klein = veldspar(veldspar, 100);
            ParsedItemDto gross = veldspar(veldspar, 100);
            gross.setVolumeEach(999.0);

            service.calculatePrices(List.of(klein), 1L);
            service.calculatePrices(List.of(gross), 1L);

            assertThat(gross.getUnitPrice()).isEqualTo(klein.getUnitPrice());
        }

        @Test
        @DisplayName("kommt ohne bekanntes Materialvolumen zurecht")
        void survivesMissingMaterialVolume() {
            long veldspar = 1230L;
            BuybackCategoryRule rule = new BuybackCategoryRule();
            rule.setCategoryId(25L);
            rule.setModifier(100.0);
            rule.setUseReprocessedValue(true);
            when(categoryRuleRepo.findById(25L)).thenReturn(Optional.of(rule));

            when(invTypeRepo.findReprocessMaterials(Set.of(veldspar)))
                    .thenReturn(List.of(new Yield(veldspar, TRITANIUM, 400L, 100)));
            when(invTypeRepo.findTypeDetailsByIds(Set.of(TRITANIUM))).thenReturn(List.of());
            when(marketService.getJitaPrices(any())).thenReturn(Map.of(TRITANIUM, price(4.0, 5.0)));

            location.setTransportFee(100.0);

            ParsedItemDto item = veldspar(veldspar, 100);
            service.calculatePrices(List.of(item), 1L);

            // Ohne Volumen faellt keine Transportgebuehr an, der Wert bleibt stehen
            assertThat(item.getUnitPrice()).isEqualTo(8.0);
        }

        @Test
        @DisplayName("fragt keine Volumen ab, wenn niemand über die Ausbeute bewertet")
        void doesNotQueryVolumesWithoutReprocessing() {
            allowCategory(MINERAL_CATEGORY, 90.0);

            service.calculatePrices(List.of(tritanium(100)), 1L);

            // Eine Abfrage mit leerer Liste wuerde als IN () auf der Datenbank scheitern
            verify(invTypeRepo, never()).findTypeDetailsByIds(any());
        }

        @Test
        @DisplayName("fällt auf den Marktpreis zurück, wenn ein Item keine Ausbeute hat")
        void fallsBackToMarketWhenItemHasNoYield() {
            allowCategoryWithReprocessing(MINERAL_CATEGORY);
            when(invTypeRepo.findReprocessMaterials(Set.of(TRITANIUM))).thenReturn(List.of());

            ParsedItemDto item = tritanium(100);
            service.calculatePrices(List.of(item), 1L);

            assertThat(item.getPriceSource()).isEqualTo(BuybackCalculationService.SOURCE_MARKET);
            assertThat(item.getStatusCode()).isEqualTo(BuybackCalculationService.STATUS_OK);
        }
    }

    @Nested
    @DisplayName("Fehlerfälle")
    class Failures {

        @Test
        @DisplayName("meldet einen unbekannten Abgabeort als fehlerhafte Anfrage")
        void unknownLocationIsABadRequest() {
            when(locationRepo.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.calculatePrices(List.of(tritanium(1)), 99L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("99");
        }

        @Test
        @DisplayName("meldet eine fehlende Konfiguration als Betriebsfehler")
        void missingConfigurationIsAServerProblem() {
            when(configRepo.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.calculatePrices(List.of(tritanium(1)), 1L))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ==========================================
    // Hilfsmittel
    // ==========================================

    private ParsedItemDto tritanium(long quantity) {
        ParsedItemDto item = new ParsedItemDto();
        item.setTypeId(TRITANIUM);
        item.setRawName("Tritanium");
        item.setCategoryId(MINERAL_CATEGORY);
        item.setVolumeEach(0.01);
        item.setResolved(true);
        item.addQuantity(quantity);
        return item;
    }

    private ParsedItemDto veldspar(long typeId, long quantity) {
        ParsedItemDto item = new ParsedItemDto();
        item.setTypeId(typeId);
        item.setRawName("Veldspar");
        item.setCategoryId(25L);
        item.setVolumeEach(0.1);
        item.setResolved(true);
        item.addQuantity(quantity);
        return item;
    }

    private void allowCategory(long categoryId, Double modifier) {
        BuybackCategoryRule rule = new BuybackCategoryRule();
        rule.setCategoryId(categoryId);
        rule.setModifier(modifier);
        when(categoryRuleRepo.findById(categoryId)).thenReturn(Optional.of(rule));
    }

    private void allowCategoryWithReprocessing(long categoryId) {
        BuybackCategoryRule rule = new BuybackCategoryRule();
        rule.setCategoryId(categoryId);
        rule.setModifier(100.0);
        rule.setUseReprocessedValue(true);
        when(categoryRuleRepo.findById(categoryId)).thenReturn(Optional.of(rule));
    }

    private void allowType(long typeId, Double modifier) {
        BuybackTypeRule rule = new BuybackTypeRule();
        rule.setTypeId(typeId);
        rule.setModifier(modifier);
        rule.setIsBlacklisted(false);
        when(typeRuleRepo.findById(typeId)).thenReturn(Optional.of(rule));
    }

    private MarketPriceDto price(double buyMax, double sellMin) {
        MarketPriceDto dto = new MarketPriceDto();
        dto.setBuyMax(buyMax);
        dto.setSellMin(sellMin);
        return dto;
    }

    /**
     * Testdoppel für die Stammdaten eines Items aus der Statikdatenbank.
     *
     * @param typeId     Type-ID
     * @param typeName   Name
     * @param volume     Volumen je Einheit
     * @param categoryId Kategorie
     */
    private record TypeDetails(Long typeId, String typeName, Double volume, Long categoryId)
            implements TypeDetailsProjection {

        @Override
        public Long getTypeId() {
            return typeId;
        }

        @Override
        public String getTypeName() {
            return typeName;
        }

        @Override
        public Double getVolume() {
            return volume;
        }

        @Override
        public Long getCategoryId() {
            return categoryId;
        }
    }

    /**
     * Testdoppel für eine Zeile der Reprocessing-Ausbeute.
     *
     * @param typeId         das zu verwertende Item
     * @param materialTypeId das gewonnene Material
     * @param quantity       Menge je Portion
     * @param portionSize    Portionsgröße
     */
    private record Yield(Long typeId, Long materialTypeId, Long quantity, Integer portionSize)
            implements ReprocessMaterialProjection {

        @Override
        public Long getTypeId() {
            return typeId;
        }

        @Override
        public Long getMaterialTypeId() {
            return materialTypeId;
        }

        @Override
        public Long getQuantity() {
            return quantity;
        }

        @Override
        public Integer getPortionSize() {
            return portionSize;
        }
    }
}
