package com.eve.own.auth.backend.domain.assets.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.assets.dto.AssetDtos;
import com.eve.own.auth.backend.domain.assets.repository.AssetQueryRepository;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.testsupport.FakeTuple;
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

/**
 * Die Mitglieder-Sicht darf ausschliesslich den eigenen Account zeigen. Diese
 * Grenze wird serverseitig gezogen - genau das pruefen die Tests.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Selbstauskunft ueber die eigenen Assets")
class MyAssetServiceTest {

    private static final Long MAIN_ID = 1000L;
    private static final Long ALT_ID = 1001L;
    private static final Long FOREIGN_ID = 9999L;

    @Mock private AssetQueryRepository queryRepo;
    @Mock private CharacterRepository characterRepo;
    @Mock private AssetAnalyticsService analyticsService;

    private MyAssetService service;

    @BeforeEach
    void setUp() {
        service = new MyAssetService(queryRepo, characterRepo, analyticsService);

        Character main = new Character();
        main.setId(MAIN_ID);
        main.setMainCharacterId(MAIN_ID);
        when(characterRepo.findById(MAIN_ID)).thenReturn(Optional.of(main));

        when(queryRepo.charactersOfMain(MAIN_ID)).thenReturn(List.of(
                FakeTuple.of("id", MAIN_ID, "name", "Main"),
                FakeTuple.of("id", ALT_ID, "name", "Alt")));
        when(queryRepo.search(any())).thenReturn(page());
        when(queryRepo.searchGrouped(any())).thenReturn(page());
        when(queryRepo.memberByCategory(anyLong())).thenReturn(List.of());
        when(queryRepo.memberByLocation(anyLong())).thenReturn(List.of());
        when(queryRepo.distinctCategoriesForMain(anyLong())).thenReturn(List.of());
        when(queryRepo.distinctGroupsForMain(anyLong(), any())).thenReturn(List.of());
        when(queryRepo.distinctLocationsForMain(anyLong())).thenReturn(List.of());
        when(queryRepo.distinctRegionsForMain(anyLong())).thenReturn(List.of());
        when(queryRepo.distinctLocationFlagsForMain(anyLong())).thenReturn(List.of());
        when(queryRepo.suggestTypesForMain(anyLong(), any(), anyInt())).thenReturn(List.of());
    }

    private static <T> AssetDtos.PageDto<T> page() {
        return new AssetDtos.PageDto<>(List.of(), 0, 50, 0L, 0, 0d, 0d);
    }

    /** Eine Anfrage, wie sie das Frontend schickt. */
    private static AssetDtos.AssetSearchRequest request(Long characterId, Long mainId,
                                                        Long corporationId, String ownerType) {
        return new AssetDtos.AssetSearchRequest(null, null, null, null, characterId, mainId,
                corporationId, null, null, null, null, null, null, ownerType,
                "value", "desc", 0, 50, false);
    }

    private AssetDtos.AssetSearchRequest capturedSearchRequest() {
        ArgumentCaptor<AssetDtos.AssetSearchRequest> captor =
                ArgumentCaptor.forClass(AssetDtos.AssetSearchRequest.class);
        org.mockito.Mockito.verify(queryRepo).search(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("Ermittlung des eigenen Accounts")
    class AccountScope {

        @Test
        @DisplayName("nimmt bei einem Main ihn selbst")
        void resolvesMainToItself() {
            assertThat(service.resolveMainId(MAIN_ID)).isEqualTo(MAIN_ID);
        }

        @Test
        @DisplayName("nimmt bei einem Alt dessen Main")
        void resolvesAltToMain() {
            Character alt = new Character();
            alt.setId(ALT_ID);
            alt.setMainCharacterId(MAIN_ID);
            when(characterRepo.findById(ALT_ID)).thenReturn(Optional.of(alt));

            assertThat(service.resolveMainId(ALT_ID)).isEqualTo(MAIN_ID);
        }

        @Test
        @DisplayName("nimmt bei einem Charakter ohne Main-Eintrag ihn selbst")
        void resolvesStandaloneCharacter() {
            Character standalone = new Character();
            standalone.setId(7L);
            when(characterRepo.findById(7L)).thenReturn(Optional.of(standalone));

            assertThat(service.resolveMainId(7L)).isEqualTo(7L);
        }

        @Test
        @DisplayName("weist einen nicht registrierten Charakter ab")
        void rejectsUnregisteredCharacter() {
            when(characterRepo.findById(FOREIGN_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolveMainId(FOREIGN_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nicht registriert");
        }

        @Test
        @DisplayName("kennt die Charaktere des Accounts")
        void listsOwnCharacters() {
            assertThat(service.ownCharacterIds(MAIN_ID)).containsExactlyInAnyOrder(MAIN_ID, ALT_ID);
        }
    }

    @Nested
    @DisplayName("Erzwungener Account-Filter")
    class ScopeEnforcement {

        @Test
        @DisplayName("setzt den Account immer auf den eigenen")
        void alwaysScopesToOwnAccount() {
            // Selbst wenn die Anfrage einen fremden Account mitschickt.
            service.search(MAIN_ID, request(null, FOREIGN_ID, null, null));

            assertThat(capturedSearchRequest().mainId()).isEqualTo(MAIN_ID);
        }

        @Test
        @DisplayName("verwirft eine fremde Charakter-ID im Filter")
        void dropsForeignCharacterFilter() {
            service.search(MAIN_ID, request(FOREIGN_ID, null, null, null));

            assertThat(capturedSearchRequest().characterId()).isNull();
        }

        @Test
        @DisplayName("laesst einen eigenen Charakter als Filter zu")
        void keepsOwnCharacterFilter() {
            service.search(MAIN_ID, request(ALT_ID, null, null, null));

            assertThat(capturedSearchRequest().characterId()).isEqualTo(ALT_ID);
        }

        @Test
        @DisplayName("verwirft einen Corporations-Filter")
        void dropsCorporationFilter() {
            service.search(MAIN_ID, request(null, null, 98000001L, null));

            assertThat(capturedSearchRequest().corporationId()).isNull();
        }

        @Test
        @DisplayName("nagelt die Sicht auf persoenliche Bestaende fest")
        void forcesCharacterOwnership() {
            service.search(MAIN_ID, request(null, null, null, "CORPORATION"));

            assertThat(capturedSearchRequest().ownerType()).isEqualTo("CHARACTER");
        }

        @Test
        @DisplayName("wendet denselben Filter auf die gruppierte Suche an")
        void scopesGroupedSearch() {
            service.searchGrouped(MAIN_ID, request(FOREIGN_ID, FOREIGN_ID, 1L, "CORPORATION"));

            ArgumentCaptor<AssetDtos.AssetSearchRequest> captor =
                    ArgumentCaptor.forClass(AssetDtos.AssetSearchRequest.class);
            org.mockito.Mockito.verify(queryRepo).searchGrouped(captor.capture());
            assertThat(captor.getValue().mainId()).isEqualTo(MAIN_ID);
            assertThat(captor.getValue().characterId()).isNull();
            assertThat(captor.getValue().corporationId()).isNull();
        }

        @Test
        @DisplayName("wendet denselben Filter auf den Export an")
        void scopesExport() {
            when(analyticsService.exportCsv(any())).thenReturn("Kopfzeile\n");

            service.exportCsv(MAIN_ID, request(FOREIGN_ID, FOREIGN_ID, 1L, null));

            ArgumentCaptor<AssetDtos.AssetSearchRequest> captor =
                    ArgumentCaptor.forClass(AssetDtos.AssetSearchRequest.class);
            org.mockito.Mockito.verify(analyticsService).exportCsv(captor.capture());
            assertThat(captor.getValue().mainId()).isEqualTo(MAIN_ID);
        }
    }

    @Nested
    @DisplayName("Uebersicht und Filterlisten")
    class OverviewAndFilters {

        @Test
        @DisplayName("wertet die Uebersicht immer fuer den eigenen Account aus")
        void buildsSummaryForOwnAccount() {
            AssetDtos.MemberAssetDetailDto expected = new AssetDtos.MemberAssetDetailDto(
                    MAIN_ID, "Main", "portrait", "Corp", 0d, 0L, List.of(), List.of(), List.of());
            when(analyticsService.memberDetail(MAIN_ID)).thenReturn(expected);

            assertThat(service.summary(MAIN_ID)).isSameAs(expected);
            org.mockito.Mockito.verify(analyticsService).memberDetail(MAIN_ID);
        }

        @Test
        @DisplayName("wertet fuer einen Alt den Account seines Mains aus")
        void buildsSummaryForAltsAccount() {
            Character alt = new Character();
            alt.setId(ALT_ID);
            alt.setMainCharacterId(MAIN_ID);
            when(characterRepo.findById(ALT_ID)).thenReturn(Optional.of(alt));

            service.summary(ALT_ID);

            org.mockito.Mockito.verify(analyticsService).memberDetail(MAIN_ID);
        }

        @Test
        @DisplayName("liefert nur die Filterlisten des eigenen Accounts")
        void buildsScopedFilterOptions() {
            // Ein Mitglied darf ueber die Dropdowns nicht sehen, wo die ganze
            // Corp ihre Sachen stehen hat.
            service.filterOptions(MAIN_ID, 6L);

            org.mockito.Mockito.verify(queryRepo).distinctCategoriesForMain(MAIN_ID);
            org.mockito.Mockito.verify(queryRepo).distinctGroupsForMain(MAIN_ID, 6L);
            org.mockito.Mockito.verify(queryRepo).distinctLocationsForMain(MAIN_ID);
            org.mockito.Mockito.verify(queryRepo).distinctRegionsForMain(MAIN_ID);
            org.mockito.Mockito.verify(queryRepo).distinctLocationFlagsForMain(MAIN_ID);
        }

        @Test
        @DisplayName("schlaegt nur Typen aus dem eigenen Bestand vor")
        void suggestsOwnTypesOnly() {
            service.suggestTypes(MAIN_ID, "nes", 10);

            org.mockito.Mockito.verify(queryRepo).suggestTypesForMain(MAIN_ID, "nes", 10);
        }
    }

    @Nested
    @DisplayName("Wo der Bestand liegt")
    class Placements {

        private AssetDtos.AssetSearchRequest capturedPlacementRequest() {
            ArgumentCaptor<AssetDtos.AssetSearchRequest> captor =
                    ArgumentCaptor.forClass(AssetDtos.AssetSearchRequest.class);
            org.mockito.Mockito.verify(queryRepo).placements(captor.capture(), anyInt());
            return captor.getValue();
        }

        @Test
        @DisplayName("gibt Ort, Fach und Behaelter zurueck")
        void returnsLocationFlagAndContainer() {
            when(queryRepo.placements(any(), anyInt())).thenReturn(List.of(FakeTuple.of(
                    "characterId", MAIN_ID, "characterName", "Main",
                    "locationId", 60003760L, "locationName", "Jita IV - Moon 4",
                    "systemName", "Jita", "regionName", "The Forge",
                    "locationFlag", "Hangar",
                    "containerName", "Munikiste", "containerTypeName", "Giant Secure Container",
                    "quantity", 42L, "totalValue", 1234.5)));

            assertThat(service.placements(MAIN_ID, request(null, null, null, null)))
                    .singleElement()
                    .satisfies(place -> {
                        assertThat(place.locationName()).isEqualTo("Jita IV - Moon 4");
                        assertThat(place.systemName()).isEqualTo("Jita");
                        assertThat(place.locationFlag()).isEqualTo("Hangar");
                        assertThat(place.containerName()).isEqualTo("Munikiste");
                        assertThat(place.containerTypeName()).isEqualTo("Giant Secure Container");
                        assertThat(place.quantity()).isEqualTo(42L);
                    });
        }

        @Test
        @DisplayName("laesst den Behaelter leer, wenn der Bestand direkt im Hangar steht")
        void leavesTheContainerEmpty() {
            when(queryRepo.placements(any(), anyInt())).thenReturn(List.of(FakeTuple.of(
                    "characterId", MAIN_ID, "characterName", "Main",
                    "locationId", 60003760L, "locationName", "Jita IV - Moon 4",
                    "systemName", "Jita", "regionName", "The Forge",
                    "locationFlag", "Hangar",
                    "quantity", 1L, "totalValue", 0d)));

            assertThat(service.placements(MAIN_ID, request(null, null, null, null)))
                    .singleElement()
                    .satisfies(place -> {
                        assertThat(place.containerName()).isNull();
                        assertThat(place.containerTypeName()).isNull();
                    });
        }

        @Test
        @DisplayName("zwingt den eigenen Account auf, egal was ankommt")
        void alwaysScopesToOwnAccount() {
            // Derselbe Schutz wie bei der Suche - sonst waere das hier ein
            // Fenster in fremde Hangars.
            when(queryRepo.placements(any(), anyInt())).thenReturn(List.of());

            service.placements(MAIN_ID, request(FOREIGN_ID, 4242L, 98000001L, "CORPORATION"));

            AssetDtos.AssetSearchRequest sent = capturedPlacementRequest();
            assertThat(sent.mainId()).isEqualTo(MAIN_ID);
            assertThat(sent.characterId()).isNull();
            assertThat(sent.corporationId()).isNull();
            assertThat(sent.ownerType()).isEqualTo("CHARACTER");
        }

        @Test
        @DisplayName("behaelt einen eigenen Charakter als Filter")
        void keepsOwnCharacterFilter() {
            when(queryRepo.placements(any(), anyInt())).thenReturn(List.of());

            service.placements(MAIN_ID, request(ALT_ID, null, null, null));

            assertThat(capturedPlacementRequest().characterId()).isEqualTo(ALT_ID);
        }

        @Test
        @DisplayName("begrenzt die Zahl der Orte")
        void limitsTheNumberOfPlaces() {
            when(queryRepo.placements(any(), anyInt())).thenReturn(List.of());

            service.placements(MAIN_ID, request(null, null, null, null));

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            org.mockito.Mockito.verify(queryRepo).placements(any(), limit.capture());
            assertThat(limit.getValue()).isPositive();
        }
    }
}
