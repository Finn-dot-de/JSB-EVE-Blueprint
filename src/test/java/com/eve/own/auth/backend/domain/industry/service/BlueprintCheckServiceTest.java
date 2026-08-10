package com.eve.own.auth.backend.domain.industry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.assets.service.MyAssetService;
import com.eve.own.auth.backend.domain.industry.dto.IndustryDtos;
import com.eve.own.auth.backend.domain.industry.entity.CharacterBlueprint;
import com.eve.own.auth.backend.domain.industry.repository.CharacterBlueprintRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository;
import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository.BlueprintInfo;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Die Blaupausen-Pruefung.
 *
 * <p>Zwei Fragen, die man nicht verwechseln darf: gibt es eine, und reichen die
 * Laeufe. Eine Kopie mit fuenf Laeufen traegt keinen Auftrag ueber fuenfzig -
 * das faellt sonst erst auf, wenn sie mitten im Auftrag aufgebraucht ist.</p>
 */
class BlueprintCheckServiceTest {

    private static final long RAVEN = 638L;
    private static final long RAVEN_BP = 688L;
    private static final Set<Long> CHARS = Set.of(1L, 2L);

    private CharacterBlueprintRepository blueprintRepo;
    private IndustryQueryRepository queryRepo;
    private BlueprintCheckService service;

    @BeforeEach
    void setUp() {
        blueprintRepo = Mockito.mock(CharacterBlueprintRepository.class);
        queryRepo = Mockito.mock(IndustryQueryRepository.class);
        MyAssetService assetService = Mockito.mock(MyAssetService.class);
        service = new BlueprintCheckService(blueprintRepo, queryRepo, assetService);

        when(assetService.resolveMainId(anyLong())).thenReturn(1L);
        when(assetService.ownCharacterIds(1L)).thenReturn(CHARS);
        when(queryRepo.blueprintFor(RAVEN)).thenReturn(new BlueprintInfo(
                RAVEN_BP, "Raven Blueprint", RAVEN, "Raven", 1, 1, 10, 18_000));
    }

    private static CharacterBlueprint bp(int runs, boolean kopie, int me, int te) {
        CharacterBlueprint b = new CharacterBlueprint();
        b.setItemId((long) runs);
        b.setCharacterId(1L);
        b.setTypeId(RAVEN_BP);
        b.setRuns(runs);
        b.setCopy(kopie);
        b.setMaterialEfficiency(me);
        b.setTimeEfficiency(te);
        return b;
    }

    @Test
    @DisplayName("ein Original reicht immer")
    void originalReichtImmer() {
        when(blueprintRepo.findBest(any(), any())).thenReturn(List.of(bp(-1, false, 10, 20)));

        var zeilen = service.check(1L, RAVEN, 50, List.of());

        assertThat(zeilen).singleElement().satisfies(z -> {
            assertThat(z.owned()).isTrue();
            assertThat(z.sufficient()).isTrue();
            // -1 steht für "unbegrenzt" - ein Original hat keine Laufzahl.
            assertThat(z.availableRuns()).isEqualTo(-1);
            assertThat(z.note()).isNull();
        });
    }

    @Test
    @DisplayName("meldet, wenn die Läufe einer Kopie nicht reichen")
    void zuWenigLaeufe() {
        when(blueprintRepo.findBest(any(), any())).thenReturn(List.of(bp(5, true, 10, 20)));

        var zeilen = service.check(1L, RAVEN, 50, List.of());

        assertThat(zeilen).singleElement().satisfies(z -> {
            assertThat(z.owned()).isTrue();
            assertThat(z.sufficient()).isFalse();
            assertThat(z.availableRuns()).isEqualTo(5);
            assertThat(z.note()).contains("fehlen 45");
        });
    }

    @Test
    @DisplayName("addiert die Läufe mehrerer Kopien")
    void mehrereKopienSummierenSich() {
        // Drei Kopien zu je zwanzig tragen einen Auftrag über sechzig genauso
        // wie eine Kopie mit sechzig.
        when(blueprintRepo.findBest(any(), any())).thenReturn(List.of(
                bp(20, true, 10, 20), bp(20, true, 8, 16), bp(20, true, 5, 10)));

        var zeilen = service.check(1L, RAVEN, 60, List.of());

        assertThat(zeilen).singleElement().satisfies(z -> {
            assertThat(z.availableRuns()).isEqualTo(60);
            assertThat(z.sufficient()).isTrue();
            // Die Werte der besten Blaupause werden ausgewiesen.
            assertThat(z.materialEfficiency()).isEqualTo(10);
        });
    }

    @Test
    @DisplayName("sagt deutlich, wenn gar keine Blaupause da ist")
    void garKeineBlaupause() {
        when(blueprintRepo.findBest(any(), any())).thenReturn(List.of());

        var zeilen = service.check(1L, RAVEN, 50, List.of());

        assertThat(zeilen).singleElement().satisfies(z -> {
            assertThat(z.owned()).isFalse();
            assertThat(z.sufficient()).isFalse();
            assertThat(z.note()).contains("nicht starten");
        });
    }

    @Test
    @DisplayName("prüft auch die Komponenten, die gebaut werden sollen")
    void gebauteKomponentenWerdenGeprueft() {
        long komponente = 11399L;
        long komponenteBp = 11400L;
        when(queryRepo.blueprintFor(komponente)).thenReturn(new BlueprintInfo(
                komponenteBp, "Komponente Blueprint", komponente, "Komponente", 1, 10, 100, 600));
        when(blueprintRepo.findBest(any(), any())).thenReturn(List.of(bp(-1, false, 10, 20)));

        var gebaut = new IndustryDtos.RequirementDto(
                komponente, "Komponente", 1000, 0, 1000, "BUILDABLE", true, "BUILD",
                1, null, null, false, 1.0, 0, 0);
        var gekauft = new IndustryDtos.RequirementDto(
                34L, "Tritanium", 5000, 0, 5000, "MINERAL", false, "BUY",
                1, null, null, false, 0.01, 0, 0);

        var zeilen = service.check(1L, RAVEN, 50, List.of(gebaut, gekauft));

        // Endprodukt plus die Komponente. Tritanium hat keine Blaupause und
        // fällt heraus; die Komponente erscheint auch dann, wenn sie gekauft
        // werden soll - dann aber als blosse Auskunft.
        assertThat(zeilen).hasSize(2);
        assertThat(zeilen).extracting(IndustryDtos.BlueprintCheckDto::productTypeId)
                .containsExactly(RAVEN, komponente);
    }

    @Test
    @DisplayName("zeigt auch Vorlagen für Gekauftes, aber nicht als Mangel")
    void gekaufteTeileErscheinenAlsAuskunft() {
        long reaktionsprodukt = 30370L;
        when(queryRepo.blueprintFor(reaktionsprodukt)).thenReturn(new BlueprintInfo(
                46166L, "Carbon Fiber Reaction Formula", reaktionsprodukt,
                "Reinforced Carbon Fiber", 11, 10_000, 100, 10_800));
        when(blueprintRepo.findBest(any(), any())).thenReturn(List.of());

        var gekauft = new IndustryDtos.RequirementDto(
                reaktionsprodukt, "Reinforced Carbon Fiber", 7791, 0, 7791,
                "REACTION", true, "BUY", 2, null, null, false, 1.0, 0, 0);

        var zeilen = service.check(1L, RAVEN, 50, List.of(gekauft));

        // Ohne diese Zeile sieht man nie, was man bräuchte, um alles selbst
        // zu machen - Reaktionsformeln tauchten sonst gar nicht auf.
        assertThat(zeilen).filteredOn(z -> z.productTypeId() == reaktionsprodukt)
                .singleElement()
                .satisfies(z -> {
                    assertThat(z.required()).isFalse();
                    assertThat(z.kind()).isEqualTo("Reaktionsformel");
                    assertThat(z.note()).contains("falls du das selbst");
                });
    }

    @Test
    @DisplayName("rechnet den Bedarf der Komponente in Läufe um")
    void komponentenbedarfInLaeufe() {
        long komponente = 11399L;
        when(queryRepo.blueprintFor(komponente)).thenReturn(new BlueprintInfo(
                11400L, "Komponente Blueprint", komponente, "Komponente", 1, 10, 100, 600));
        when(blueprintRepo.findBest(any(), any())).thenReturn(List.of(bp(-1, false, 0, 0)));

        var gebaut = new IndustryDtos.RequirementDto(
                komponente, "Komponente", 1000, 0, 1000, "BUILDABLE", true, "BUILD",
                1, null, null, false, 1.0, 0, 0);

        var zeilen = service.check(1L, RAVEN, 50, List.of(gebaut));

        // Ein Lauf liefert zehn Stück, gebraucht werden tausend: hundert Läufe.
        assertThat(zeilen).filteredOn(z -> z.productTypeId() == komponente)
                .singleElement()
                .satisfies(z -> assertThat(z.neededRuns()).isEqualTo(100));
    }
}
