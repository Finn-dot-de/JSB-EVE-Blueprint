package com.eve.own.auth.backend.domain.mining.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.eve.entity.InvType;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxRate;
import com.eve.own.auth.backend.domain.mining.repository.MiningTaxRateRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Verwaltung der Steuersaetze")
class MiningTaxRateServiceTest {

    private static final Long VELDSPAR = 1230L;
    private static final Long WHITE_GLAZE = 16262L;

    @Mock private MiningTaxRateRepository taxRateRepo;
    @Mock private InvTypeRepository invTypeRepo;

    private MiningTaxRateService service;

    @BeforeEach
    void setUp() {
        service = new MiningTaxRateService(taxRateRepo, invTypeRepo);
        when(taxRateRepo.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private static InvType type(Long typeId, String name, Long groupId) {
        InvType type = new InvType();
        type.setTypeId(typeId);
        type.setTypeName(name);
        type.setGroupId(groupId);
        return type;
    }

    private static MiningTaxRate rate(Long typeId, String category, double percentage) {
        MiningTaxRate rate = new MiningTaxRate();
        rate.setTypeId(typeId);
        rate.setCategory(category);
        rate.setTaxPercentage(percentage);
        return rate;
    }

    @Nested
    @DisplayName("Nachtragen unbekannter Typen")
    class MissingRates {

        @Test
        @DisplayName("legt einen steuerfreien Satz mit der richtigen Klasse an")
        void createsRateFromSde() {
            when(invTypeRepo.findById(WHITE_GLAZE))
                    .thenReturn(Optional.of(type(WHITE_GLAZE, "White Glaze", 423L)));

            MiningTaxRate created = service.createMissingRate(WHITE_GLAZE);

            assertThat(created.getTypeName()).isEqualTo("White Glaze");
            assertThat(created.getCategory()).isEqualTo("ICE");
            assertThat(created.getTaxPercentage()).isZero();
            assertThat(created.getCurrentJitaBuy()).isZero();
            verify(taxRateRepo).save(created);
        }

        @Test
        @DisplayName("legt auch fuer einen der SDE unbekannten Typ etwas Sichtbares an")
        void createsPlaceholderForUnknownType() {
            when(invTypeRepo.findById(9999L)).thenReturn(Optional.empty());

            MiningTaxRate created = service.createMissingRate(9999L);

            assertThat(created.getTypeName()).isEqualTo("Unknown Ore (9999)");
            assertThat(created.getCategory()).isEqualTo("ORE");
        }
    }

    @Nested
    @DisplayName("Abgleich mit der SDE")
    class SdeSynchronisation {

        @Test
        @DisplayName("legt neue abbaubare Typen an")
        void addsNewTypes() {
            when(invTypeRepo.findAllMineables()).thenReturn(List.of(type(VELDSPAR, "Veldspar", 462L)));
            when(taxRateRepo.findAll()).thenReturn(new ArrayList<>());

            service.synchronizeWithSde();

            ArgumentCaptor<List<MiningTaxRate>> saved = ArgumentCaptor.captor();
            verify(taxRateRepo).saveAll(saved.capture());
            assertThat(saved.getValue()).singleElement()
                    .satisfies(rate -> {
                        assertThat(rate.getTypeId()).isEqualTo(VELDSPAR);
                        assertThat(rate.getCategory()).isEqualTo("ORE");
                        assertThat(rate.getTaxPercentage()).isZero();
                    });
        }

        @Test
        @DisplayName("korrigiert eine falsch einsortierte Klasse und laesst den Prozentsatz stehen")
        void fixesWrongCategoryKeepingPercentage() {
            MiningTaxRate wrong = rate(WHITE_GLAZE, "ORE", 15.0);
            when(invTypeRepo.findAllMineables())
                    .thenReturn(List.of(type(WHITE_GLAZE, "White Glaze", 423L)));
            when(taxRateRepo.findAll()).thenReturn(new ArrayList<>(List.of(wrong)));

            service.synchronizeWithSde();

            assertThat(wrong.getCategory()).isEqualTo("ICE");
            assertThat(wrong.getTaxPercentage()).isEqualTo(15.0);
        }

        @Test
        @DisplayName("schreibt nichts, wenn schon alles stimmt")
        void skipsWhenNothingChanged() {
            when(invTypeRepo.findAllMineables()).thenReturn(List.of(type(VELDSPAR, "Veldspar", 462L)));
            when(taxRateRepo.findAll()).thenReturn(new ArrayList<>(List.of(rate(VELDSPAR, "ORE", 10.0))));

            service.synchronizeWithSde();

            verify(taxRateRepo, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("entfernt Saetze zu nicht mehr abbaubaren Typen")
        void removesObsoleteRates() {
            MiningTaxRate obsolete = rate(999L, "ORE", 5.0);
            when(invTypeRepo.findAllMineables()).thenReturn(List.of(type(VELDSPAR, "Veldspar", 462L)));
            when(taxRateRepo.findAll()).thenReturn(new ArrayList<>(List.of(obsolete)));

            service.synchronizeWithSde();

            ArgumentCaptor<List<MiningTaxRate>> deleted = ArgumentCaptor.captor();
            verify(taxRateRepo).deleteAll(deleted.capture());
            assertThat(deleted.getValue()).containsExactly(obsolete);
        }

        @Test
        @DisplayName("laesst die Saetze in Ruhe, wenn die SDE nichts liefert")
        void doesNothingWhenSdeIsEmpty() {
            when(invTypeRepo.findAllMineables()).thenReturn(List.of());

            service.synchronizeWithSde();

            verify(taxRateRepo, never()).deleteAll(anyList());
            verify(taxRateRepo, never()).saveAll(anyList());
        }
    }

    @Nested
    @DisplayName("Alltagsbetrieb")
    class DailyOperations {

        @Test
        @DisplayName("setzt einen Prozentsatz fuer eine ganze Klasse, Gross- und Kleinschreibung egal")
        void updatesWholeCategory() {
            MiningTaxRate ore = rate(1L, "ORE", 0.0);
            MiningTaxRate ice = rate(2L, "ICE", 0.0);
            when(taxRateRepo.findAll()).thenReturn(List.of(ore, ice));

            int touched = service.updateCategory("ore", 12.5);

            assertThat(touched).isEqualTo(1);
            assertThat(ore.getTaxPercentage()).isEqualTo(12.5);
            assertThat(ice.getTaxPercentage()).isZero();
        }

        @Test
        @DisplayName("kommt mit einem Satz ohne Klasse zurecht")
        void toleratesRateWithoutCategory() {
            when(taxRateRepo.findAll()).thenReturn(List.of(rate(1L, null, 0.0)));

            assertThat(service.updateCategory("ORE", 10.0)).isZero();
        }

        @Test
        @DisplayName("schluesselt die Saetze nach Typ auf")
        void indexesByTypeId() {
            when(taxRateRepo.findAll()).thenReturn(List.of(rate(1L, "ORE", 5.0), rate(2L, "ICE", 7.0)));

            assertThat(service.findAllByTypeId()).containsOnlyKeys(1L, 2L);
        }

        @Test
        @DisplayName("reicht Lesen, Speichern und Loeschen durch")
        void delegatesSimpleOperations() {
            MiningTaxRate rate = rate(1L, "ORE", 5.0);
            when(taxRateRepo.findAll()).thenReturn(List.of(rate));

            assertThat(service.findAll()).containsExactly(rate);
            assertThat(service.save(rate)).isEqualTo(rate);

            service.delete(1L);
            verify(taxRateRepo).deleteById(1L);
        }
    }
}
