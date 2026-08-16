package com.eve.own.auth.backend.domain.assets.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.assets.dto.AssetDtos;
import com.eve.own.auth.backend.domain.assets.repository.AssetQueryRepository;
import com.eve.own.auth.backend.domain.fleet.entity.FleetDoctrine;
import com.eve.own.auth.backend.domain.fleet.repository.FleetDoctrineRepository;
import com.eve.own.auth.backend.testsupport.FakeTuple;
import java.util.List;
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
@DisplayName("Asset-Auswertung fuer Direktoren")
class AssetAnalyticsServiceTest {

    private static final Long TYPE_ID = 587L;
    private static final Long MAIN_ID = 1000L;
    private static final Long CHARACTER_ID = 1001L;
    private static final Long CORPORATION_ID = 98000001L;

    @Mock private AssetQueryRepository queryRepo;
    @Mock private FleetDoctrineRepository doctrineRepo;

    private AssetAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AssetAnalyticsService(queryRepo, doctrineRepo);

        when(queryRepo.findTypeInfo(anyLong())).thenReturn(null);
        when(queryRepo.findHoldersOfType(anyLong())).thenReturn(List.of());
        when(queryRepo.totals()).thenReturn(null);
        when(queryRepo.valueByCorporation()).thenReturn(List.of());
        when(queryRepo.valueByCategory()).thenReturn(List.of());
        when(queryRepo.valueByRegion()).thenReturn(List.of());
        when(queryRepo.topTypes(anyInt())).thenReturn(List.of());
        when(queryRepo.topHolders(anyInt())).thenReturn(List.of());
        when(queryRepo.memberByCategory(anyLong())).thenReturn(List.of());
        when(queryRepo.memberByLocation(anyLong())).thenReturn(List.of());
        when(queryRepo.searchGrouped(any())).thenReturn(emptyPage());
        when(queryRepo.search(any())).thenReturn(emptyPage());
        when(queryRepo.doctrineOwnership(any())).thenReturn(List.of());
        when(queryRepo.distinctCategories()).thenReturn(List.of());
        when(queryRepo.distinctGroups(any())).thenReturn(List.of());
        when(queryRepo.distinctLocations()).thenReturn(List.of());
        when(queryRepo.distinctRegions()).thenReturn(List.of());
        when(queryRepo.distinctLocationFlags()).thenReturn(List.of());
        when(queryRepo.distinctCorporations()).thenReturn(List.of());
        when(queryRepo.distinctMains()).thenReturn(List.of());
        when(queryRepo.suggestTypes(anyString(), anyInt())).thenReturn(List.of());
        when(doctrineRepo.findAll()).thenReturn(List.of());
    }

    private static <T> AssetDtos.PageDto<T> emptyPage() {
        return new AssetDtos.PageDto<>(List.of(), 0, 50, 0L, 0, 0d, 0d);
    }

    private static AssetDtos.AssetSearchRequest request(boolean grouped) {
        return new AssetDtos.AssetSearchRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, "value", "desc", 0, 50, grouped);
    }

    @Nested
    @DisplayName("Wer hat diesen Gegenstand?")
    class Holders {

        @Test
        @DisplayName("gruppiert die Bestaende nach Account und Charakter")
        void groupsByAccountAndCharacter() {
            when(queryRepo.findTypeInfo(TYPE_ID)).thenReturn(FakeTuple.of(
                    "typeName", "Rifter", "groupName", "Frigate", "unitPrice", 100.0));
            when(queryRepo.findHoldersOfType(TYPE_ID)).thenReturn(List.of(
                    FakeTuple.of("mainId", MAIN_ID, "mainName", "Main", "characterId", CHARACTER_ID,
                            "characterName", "Alt", "corporationName", "Corp", "locationId", 60003760L,
                            "locationName", "Jita IV", "systemName", "Jita", "regionName", "The Forge",
                            "locationFlag", "Hangar", "singleton", false, "customName", null,
                            "isCorp", false, "quantity", 5L, "value", 500.0),
                    FakeTuple.of("mainId", MAIN_ID, "mainName", "Main", "characterId", CHARACTER_ID,
                            "characterName", "Alt", "corporationName", "Corp", "locationId", 60003761L,
                            "locationName", "Amarr", "systemName", "Amarr", "regionName", "Domain",
                            "locationFlag", "Hangar", "singleton", false, "customName", null,
                            "isCorp", false, "quantity", 3L, "value", 300.0)));

            AssetDtos.TypeHoldersDto holders = service.holdersOfType(TYPE_ID);

            assertThat(holders.typeName()).isEqualTo("Rifter");
            assertThat(holders.holderCount()).isEqualTo(1);
            assertThat(holders.totalQuantity()).isEqualTo(8L);
            assertThat(holders.totalValue()).isEqualTo(800.0);
            assertThat(holders.holders().getFirst().characters()).singleElement()
                    .satisfies(character -> assertThat(character.locations()).hasSize(2));
        }

        @Test
        @DisplayName("gibt einer Corp-Zeile das Corporation-Logo")
        void usesCorporationLogoForCorpRows() {
            when(queryRepo.findHoldersOfType(TYPE_ID)).thenReturn(List.of(
                    FakeTuple.of("mainId", CORPORATION_ID, "mainName", "Corp", "characterId", 0L,
                            "characterName", "Corp", "corporationName", "Corp", "locationId", 1L,
                            "locationName", "Struktur", "systemName", null, "regionName", null,
                            "locationFlag", "CorpSAG1", "singleton", false, "customName", null,
                            "isCorp", true, "quantity", 1L, "value", 1.0)));

            AssetDtos.TypeHoldersDto holders = service.holdersOfType(TYPE_ID);

            assertThat(holders.holders().getFirst().portraitUrl()).contains("/corporations/");
        }

        @Test
        @DisplayName("sortiert die Besitzer nach Menge absteigend")
        void sortsByQuantity() {
            when(queryRepo.findHoldersOfType(TYPE_ID)).thenReturn(List.of(
                    FakeTuple.of("mainId", 1L, "mainName", "Wenig", "characterId", 1L,
                            "characterName", "A", "corporationName", "C", "locationId", 1L,
                            "locationName", "L", "systemName", null, "regionName", null,
                            "locationFlag", "Hangar", "singleton", false, "customName", null,
                            "isCorp", false, "quantity", 1L, "value", 1.0),
                    FakeTuple.of("mainId", 2L, "mainName", "Viel", "characterId", 2L,
                            "characterName", "B", "corporationName", "C", "locationId", 1L,
                            "locationName", "L", "systemName", null, "regionName", null,
                            "locationFlag", "Hangar", "singleton", false, "customName", null,
                            "isCorp", false, "quantity", 100L, "value", 100.0)));

            assertThat(service.holdersOfType(TYPE_ID).holders())
                    .extracting(AssetDtos.HolderDto::mainName)
                    .containsExactly("Viel", "Wenig");
        }

        @Test
        @DisplayName("benennt einen unbekannten Typ nachvollziehbar")
        void namesUnknownType() {
            AssetDtos.TypeHoldersDto holders = service.holdersOfType(TYPE_ID);

            assertThat(holders.typeName()).isEqualTo("Typ 587");
            assertThat(holders.unitPrice()).isZero();
        }

        @Test
        @DisplayName("beschriftet einen Standort ohne Namen mit seiner ID")
        void labelsUnnamedLocation() {
            when(queryRepo.findHoldersOfType(TYPE_ID)).thenReturn(List.of(
                    FakeTuple.of("mainId", 1L, "mainName", "Main", "characterId", 1L,
                            "characterName", "A", "corporationName", "C", "locationId", 12345L,
                            "locationName", null, "systemName", null, "regionName", null,
                            "locationFlag", "Hangar", "singleton", false, "customName", null,
                            "isCorp", false, "quantity", 1L, "value", 1.0)));

            assertThat(service.holdersOfType(TYPE_ID).holders().getFirst()
                    .characters().getFirst().locations().getFirst().locationName())
                    .isEqualTo("Unbekannter Ort (12345)");
        }
    }

    @Nested
    @DisplayName("Uebersicht")
    class Summary {

        @Test
        @DisplayName("liefert auch ohne jede Zeile eine vollstaendige Antwort")
        void survivesEmptyDatabase() {
            AssetDtos.SummaryDto summary = service.summary();

            assertThat(summary.totalStacks()).isZero();
            assertThat(summary.totalValue()).isZero();
            assertThat(summary.valueByCategory()).isEmpty();
        }

        @Test
        @DisplayName("uebernimmt die Kennzahlen aus der Datenbank")
        void readsTotals() {
            when(queryRepo.totals()).thenReturn(FakeTuple.of(
                    "stacks", 100L, "items", 5000L, "types", 42L, "chars", 7L, "value", 1_000_000.0));
            when(queryRepo.valueByCategory()).thenReturn(List.of(
                    FakeTuple.of("name", "Ship", "quantity", 10L, "value", 500_000.0)));

            AssetDtos.SummaryDto summary = service.summary();

            assertThat(summary.totalStacks()).isEqualTo(100L);
            assertThat(summary.distinctTypes()).isEqualTo(42L);
            assertThat(summary.valueByCategory()).singleElement()
                    .satisfies(bucket -> assertThat(bucket.name()).isEqualTo("Ship"));
        }

        @Test
        @DisplayName("gibt Spieler-Accounts ein Portraet und Corp-Zeilen ein Logo")
        void picksImagePerHolderKind() {
            when(queryRepo.topHolders(anyInt())).thenReturn(List.of(
                    FakeTuple.of("mainId", MAIN_ID, "mainName", "Spieler", "corporationName", "C",
                            "stacks", 5L, "value", 1.0, "isCorp", false),
                    FakeTuple.of("mainId", CORPORATION_ID, "mainName", "Corp", "corporationName", "C",
                            "stacks", 5L, "value", 1.0, "isCorp", true)));

            List<AssetDtos.TopHolderDto> holders = service.summary().topHolders();

            assertThat(holders.getFirst().portraitUrl()).contains("/characters/");
            assertThat(holders.get(1).portraitUrl()).contains("/corporations/");
        }

        @Test
        @DisplayName("versieht die Bestenliste der Typen mit Symbolen")
        void addsIconsToTopTypes() {
            when(queryRepo.topTypes(anyInt())).thenReturn(List.of(
                    FakeTuple.of("typeId", TYPE_ID, "typeName", "Rifter", "groupName", "Frigate",
                            "quantity", 10L, "value", 1000.0, "holders", 3L)));

            assertThat(service.summary().topTypes()).singleElement()
                    .satisfies(type -> assertThat(type.iconUrl()).contains("/types/587/icon"));
        }
    }

    @Nested
    @DisplayName("Detailansicht eines Accounts")
    class MemberDetail {

        @Test
        @DisplayName("summiert Wert und Stapel des Accounts")
        void sumsAccountTotals() {
            when(queryRepo.memberByCategory(MAIN_ID)).thenReturn(List.of(
                    FakeTuple.of("name", "Ship", "quantity", 5L, "value", 300.0),
                    FakeTuple.of("name", "Module", "quantity", 50L, "value", 200.0)));
            when(queryRepo.memberByLocation(MAIN_ID)).thenReturn(List.of(
                    FakeTuple.of("locationId", 1L, "locationName", "Jita", "systemName", "Jita",
                            "regionName", "The Forge", "stacks", 12L, "value", 500.0)));

            AssetDtos.MemberAssetDetailDto detail = service.memberDetail(MAIN_ID);

            assertThat(detail.totalValue()).isEqualTo(500.0);
            assertThat(detail.totalStacks()).isEqualTo(12L);
            assertThat(detail.portraitUrl()).contains("size=128");
        }

        @Test
        @DisplayName("benennt einen Account ohne Bestand als unbekannt")
        void handlesAccountWithoutAssets() {
            AssetDtos.MemberAssetDetailDto detail = service.memberDetail(MAIN_ID);

            assertThat(detail.mainName()).isEqualTo("Unbekannt");
            assertThat(detail.corporationName()).isNull();
        }

        @Test
        @DisplayName("nimmt Namen und Corporation aus dem groessten Posten")
        void readsNameFromTopItem() {
            when(queryRepo.searchGrouped(any())).thenReturn(new AssetDtos.PageDto<>(
                    List.of(new AssetDtos.AssetStackDto(TYPE_ID, "Rifter", "Frigate", "Ship",
                            MAIN_ID, "Der Main", "Die Corp", false, false, 1L, 1, 1.0, 1.0)),
                    0, 25, 1L, 1, 1.0, 1.0));

            AssetDtos.MemberAssetDetailDto detail = service.memberDetail(MAIN_ID);

            assertThat(detail.mainName()).isEqualTo("Der Main");
            assertThat(detail.corporationName()).isEqualTo("Die Corp");
        }
    }

    @Nested
    @DisplayName("Doktrin-Verfuegbarkeit")
    class DoctrineReadiness {

        private FleetDoctrine doctrine(String name, Long shipTypeId, String shipType) {
            FleetDoctrine fit = new FleetDoctrine();
            fit.setDoctrineName(name);
            fit.setShipTypeId(shipTypeId);
            fit.setShipType(shipType);
            return fit;
        }

        @Test
        @DisplayName("listet die Doktrin-Namen ohne Dubletten und sortiert")
        void listsDoctrineNames() {
            when(doctrineRepo.findAll()).thenReturn(List.of(
                    doctrine("Zeta", 1L, "A"), doctrine("Alpha", 2L, "B"),
                    doctrine("Alpha", 3L, "C"), doctrine(null, 4L, "D")));

            assertThat(service.doctrineNames()).containsExactly("Alpha", "Zeta");
        }

        @Test
        @DisplayName("meldet fuer eine Doktrin ohne Schiffe ein leeres Ergebnis")
        void handlesDoctrineWithoutShips() {
            AssetDtos.DoctrineReadinessDto readiness = service.doctrineReadiness("Leer");

            assertThat(readiness.rows()).isEmpty();
            assertThat(readiness.membersTotal()).isZero();
        }

        @Test
        @DisplayName("rechnet die Abdeckung je Account aus")
        void computesCoverage() {
            when(doctrineRepo.findAll()).thenReturn(List.of(
                    doctrine("Armor", 1L, "Nestor"), doctrine("Armor", 2L, "Guardian")));
            when(queryRepo.doctrineOwnership(any())).thenReturn(List.of(
                    FakeTuple.of("mainId", MAIN_ID, "mainName", "Voll", "corporationName", "C",
                            "typeId", 1L, "quantity", 1L),
                    FakeTuple.of("mainId", MAIN_ID, "mainName", "Voll", "corporationName", "C",
                            "typeId", 2L, "quantity", 1L),
                    FakeTuple.of("mainId", 2000L, "mainName", "Halb", "corporationName", "C",
                            "typeId", 1L, "quantity", 1L)));

            AssetDtos.DoctrineReadinessDto readiness = service.doctrineReadiness("Armor");

            assertThat(readiness.membersTotal()).isEqualTo(2);
            assertThat(readiness.membersReady()).isEqualTo(1);
            assertThat(readiness.rows().getFirst().coverage()).isEqualTo(1.0);
            assertThat(readiness.rows().get(1).coverage()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("benennt ein Schiff ohne Namen ueber seine Typ-ID")
        void namesShipWithoutName() {
            when(doctrineRepo.findAll()).thenReturn(List.of(doctrine("Armor", 1L, null)));

            assertThat(service.doctrineReadiness("Armor").requiredShips())
                    .singleElement()
                    .satisfies(ship -> assertThat(ship.typeName()).isEqualTo("Typ 1"));
        }

        @Test
        @DisplayName("beruecksichtigt alle Doktrinen, wenn keine vorgegeben ist")
        void includesAllDoctrinesWithoutFilter() {
            when(doctrineRepo.findAll()).thenReturn(List.of(
                    doctrine("Armor", 1L, "Nestor"), doctrine("Shield", 2L, "Basilisk")));

            assertThat(service.doctrineReadiness(null).requiredShips()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Filterlisten und Vorschlaege")
    class FiltersAndSuggestions {

        @Test
        @DisplayName("baut die Filterlisten zusammen")
        void buildsFilterOptions() {
            when(queryRepo.distinctCategories()).thenReturn(List.of(
                    FakeTuple.of("id", 6L, "name", "Ship"),
                    FakeTuple.of("id", 7L, "name", null)));

            AssetDtos.FilterOptionsDto options = service.filterOptions(null);

            // Eintraege ohne Namen fliegen raus - sie waeren im Dropdown leer.
            assertThat(options.categories()).singleElement()
                    .satisfies(category -> assertThat(category.name()).isEqualTo("Ship"));
        }

        @Test
        @DisplayName("versieht Typ-Vorschlaege mit Symbolen")
        void suggestsTypes() {
            when(queryRepo.suggestTypes(anyString(), anyInt())).thenReturn(List.of(
                    FakeTuple.of("typeId", TYPE_ID, "typeName", "Rifter",
                            "groupName", "Frigate", "quantity", 10L)));

            assertThat(service.suggestTypes("rif", 10)).singleElement()
                    .satisfies(suggestion -> assertThat(suggestion.iconUrl()).contains("/types/587/"));
        }
    }

    @Nested
    @DisplayName("CSV-Export")
    class CsvExport {

        @Test
        @DisplayName("schreibt fuer die Detailsicht eine Zeile je Bestand")
        void exportsFlatRows() {
            when(queryRepo.search(any())).thenReturn(new AssetDtos.PageDto<>(
                    List.of(new AssetDtos.AssetRowDto(1L, CHARACTER_ID, "Pilot", MAIN_ID, "Main",
                            CORPORATION_ID, "Corp", TYPE_ID, "Rifter", 25L, "Frigate", 6L, "Ship",
                            5L, "Jita IV", "Jita", "The Forge", "Hangar", false, null, false, false,
                            100.0, 500.0)),
                    0, 500, 1L, 1, 500.0, 500.0));

            String csv = service.exportCsv(request(false));

            assertThat(csv).startsWith("Charakter;Account;Corporation;");
            assertThat(csv).contains("Pilot;Main;Corp;Rifter;Frigate;Ship;5;Jita IV;Jita;The Forge;Hangar");
            assertThat(csv).contains("500,00");
        }

        @Test
        @DisplayName("schreibt fuer die gruppierte Sicht eine Zeile je Stapel")
        void exportsGroupedRows() {
            when(queryRepo.searchGrouped(any())).thenReturn(new AssetDtos.PageDto<>(
                    List.of(new AssetDtos.AssetStackDto(TYPE_ID, "Rifter", "Frigate", "Ship",
                            MAIN_ID, "Main", "Corp", false, false, 12L, 3, 100.0, 1200.0)),
                    0, 500, 1L, 1, 1200.0, 1200.0));

            String csv = service.exportCsv(request(true));

            assertThat(csv).startsWith("Typ;Gruppe;Kategorie;");
            assertThat(csv).contains("Rifter;Frigate;Ship;Main;Corp;12;3;");
        }

        @Test
        @DisplayName("entschaerft Trennzeichen und Zeilenumbrueche in Namen")
        void escapesSeparators() {
            // Ein Semikolon im Namen wuerde sonst eine Spalte erfinden.
            when(queryRepo.searchGrouped(any())).thenReturn(new AssetDtos.PageDto<>(
                    List.of(new AssetDtos.AssetStackDto(TYPE_ID, "Rif;ter\nII", "Frigate", "Ship",
                            MAIN_ID, "Ma\"in", "Corp", false, false, 1L, 1, 1.0, 1.0)),
                    0, 500, 1L, 1, 1.0, 1.0));

            String csv = service.exportCsv(request(true));

            assertThat(csv).contains("Rif,ter II").doesNotContain("Rif;ter");
            assertThat(csv).contains("Ma'in");
        }

        @Test
        @DisplayName("holt fuer den Export bis zu 500 Zeilen der ersten Seite")
        void exportsFirstPageOnly() {
            service.exportCsv(request(false));

            ArgumentCaptor<AssetDtos.AssetSearchRequest> captor =
                    ArgumentCaptor.forClass(AssetDtos.AssetSearchRequest.class);
            org.mockito.Mockito.verify(queryRepo).search(captor.capture());
            assertThat(captor.getValue().page()).isZero();
            assertThat(captor.getValue().size()).isEqualTo(500);
        }

        @Test
        @DisplayName("schreibt leere Felder als leere Spalten")
        void writesEmptyFields() {
            when(queryRepo.searchGrouped(any())).thenReturn(new AssetDtos.PageDto<>(
                    List.of(new AssetDtos.AssetStackDto(TYPE_ID, null, null, null,
                            MAIN_ID, null, null, false, false, 1L, 1, 0.0, 0.0)),
                    0, 500, 1L, 1, 0.0, 0.0));

            assertThat(service.exportCsv(request(true))).contains(";;;");
        }
    }

    @Test
    @DisplayName("reicht die Suche unveraendert an die Datenbank durch")
    void delegatesSearch() {
        service.search(request(false));
        service.searchGrouped(request(true));

        org.mockito.Mockito.verify(queryRepo).search(any());
        org.mockito.Mockito.verify(queryRepo).searchGrouped(any());
    }
}
