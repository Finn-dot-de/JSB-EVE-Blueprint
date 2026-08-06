package com.eve.own.auth.backend.domain.assets.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.assets.dto.AssetDtos;
import com.eve.own.auth.backend.testsupport.FakeTuple;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Die Suche baut ihr SQL zur Laufzeit zusammen. Getestet wird deshalb, welches
 * SQL entsteht und welche Werte als Parameter gebunden werden - insbesondere,
 * dass Sortierspalten <em>nie</em> aus der Anfrage in das SQL wandern.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Asset-Suche: erzeugtes SQL")
class AssetQueryRepositoryTest {

    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private AssetQueryRepository repository;

    /** Alle SQL-Texte, die an den EntityManager gegangen sind. */
    private final List<String> executedSql = new ArrayList<>();

    /** Alle gebundenen Parameter, in Reihenfolge der Bindung. */
    private final Map<String, Object> boundParameters = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        repository = new AssetQueryRepository();
        // @PersistenceContext wird sonst vom Container gesetzt.
        Field em = AssetQueryRepository.class.getDeclaredField("em");
        em.setAccessible(true);
        em.set(repository, entityManager);

        when(entityManager.createNativeQuery(anyString(), eq(Tuple.class))).thenAnswer(call -> {
            executedSql.add(call.getArgument(0));
            return query;
        });
        when(entityManager.createNativeQuery(anyString())).thenAnswer(call -> {
            executedSql.add(call.getArgument(0));
            return query;
        });
        when(query.setParameter(anyString(), any())).thenAnswer(call -> {
            boundParameters.put(call.getArgument(0), call.getArgument(1));
            return query;
        });
        when(query.getResultList()).thenReturn(List.of());
        when(query.getSingleResult()).thenReturn(0L);
    }

    private String lastSql() {
        return executedSql.getLast();
    }

    private String allSql() {
        return String.join("\n", executedSql);
    }

    /** Eine Anfrage, bei der nur die angegebenen Felder gesetzt sind. */
    private static AssetDtos.AssetSearchRequest request(String sort, String direction) {
        return new AssetDtos.AssetSearchRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, sort, direction, 0, 50, false);
    }

    @Nested
    @DisplayName("Sortierung")
    class Sorting {

        @Test
        @DisplayName("uebersetzt einen erlaubten Schluessel in seine Spalte")
        void mapsWhitelistedSortKey() {
            repository.search(request("typeName", "asc"));

            assertThat(allSql()).contains("ORDER BY t.\"typeName\" ASC NULLS LAST");
        }

        @Test
        @DisplayName("faellt bei einem unbekannten Schluessel auf den Wert zurueck")
        void fallsBackForUnknownSortKey() {
            // Entscheidend fuer die Sicherheit: der Text der Anfrage darf niemals
            // im SQL landen, sonst waere die Sortierung eine offene Tuer.
            repository.search(request("'; DROP TABLE characters; --", "asc"));

            assertThat(allSql()).doesNotContain("DROP TABLE");
            assertThat(allSql()).contains("ORDER BY");
        }

        @Test
        @DisplayName("sortiert ohne Angabe absteigend nach Wert")
        void defaultsToValueDescending() {
            repository.search(request(null, null));

            assertThat(allSql()).contains("DESC NULLS LAST");
        }

        @Test
        @DisplayName("kennt in der gruppierten Suche eine eigene Spaltenliste")
        void groupedSortUsesOwnWhitelist() {
            repository.searchGrouped(request("quantity", "asc"));

            assertThat(allSql()).contains("SUM(a.quantity) ASC");
        }
    }

    @Nested
    @DisplayName("Filter")
    class Filters {

        private AssetDtos.AssetSearchRequest fullyFiltered() {
            return new AssetDtos.AssetSearchRequest(
                    "  Nestor  ", 1L, 2L, 3L, 4L, 5L, 6L, 7L, " Delve ", " Hangar ",
                    10L, 1000.0, true, "CORPORATION", "value", "desc", 0, 50, false);
        }

        @Test
        @DisplayName("bindet jeden gesetzten Filter als Parameter")
        void bindsEveryFilter() {
            repository.search(fullyFiltered());

            assertThat(boundParameters)
                    .containsEntry("q", "Nestor")
                    .containsEntry("typeId", 1L)
                    .containsEntry("groupId", 2L)
                    .containsEntry("categoryId", 3L)
                    .containsEntry("characterId", 4L)
                    .containsEntry("mainId", 5L)
                    .containsEntry("corporationId", 6L)
                    .containsEntry("locationId", 7L)
                    .containsEntry("regionName", "Delve")
                    .containsEntry("locationFlag", "Hangar")
                    .containsEntry("minQuantity", 10L)
                    .containsEntry("minValue", 1000.0);
        }

        @Test
        @DisplayName("laesst die WHERE-Klausel ohne Filter ganz weg")
        void omitsWhereWithoutFilters() {
            repository.search(request(null, null));

            assertThat(executedSql.getFirst()).doesNotContain("WHERE");
        }

        @Test
        @DisplayName("beschraenkt auf Corp-Bestaende oder persoenliche Bestaende")
        void filtersByOwnerType() {
            repository.search(new AssetDtos.AssetSearchRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, "CHARACTER", null, null, 0, 50, false));
            assertThat(allSql()).contains("a.is_corp = FALSE");

            executedSql.clear();
            repository.search(new AssetDtos.AssetSearchRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, "corporation", null, null, 0, 50, false));
            assertThat(allSql()).contains("a.is_corp = TRUE");
        }

        @Test
        @DisplayName("beschraenkt auf Schiffe ohne dafuer einen Parameter zu binden")
        void filtersShipsOnly() {
            repository.search(new AssetDtos.AssetSearchRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null, true, null, null, null, 0, 50, false));

            assertThat(allSql()).contains("g.\"categoryID\" = 6");
        }

        @Test
        @DisplayName("ignoriert leere Textfilter und Nullmengen")
        void ignoresEmptyFilters() {
            repository.search(new AssetDtos.AssetSearchRequest("   ", null, null, null, null, null,
                    null, null, "  ", "  ", 0L, 0.0, false, null, null, null, 0, 50, false));

            assertThat(boundParameters).doesNotContainKeys("q", "regionName", "locationFlag",
                    "minQuantity", "minValue");
        }
    }

    @Nested
    @DisplayName("Seitenaufteilung")
    class Paging {

        @Test
        @DisplayName("begrenzt die Seitengroesse nach oben")
        void capsPageSize() {
            repository.search(new AssetDtos.AssetSearchRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, 0, 100_000, false));

            assertThat(boundParameters).containsEntry("limit", 500);
        }

        @Test
        @DisplayName("faengt unsinnige Seitenangaben ab")
        void correctsInvalidPaging() {
            repository.search(new AssetDtos.AssetSearchRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, -5, 0, false));

            assertThat(boundParameters).containsEntry("limit", 50).containsEntry("offset", 0L);
        }

        @Test
        @DisplayName("rechnet den Versatz aus Seite und Groesse")
        void computesOffset() {
            repository.search(new AssetDtos.AssetSearchRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, 3, 25, false));

            assertThat(boundParameters).containsEntry("offset", 75L);
        }

        @Test
        @DisplayName("nimmt fehlende Seitenangaben als erste Seite mit Standardgroesse")
        void defaultsPaging() {
            repository.search(new AssetDtos.AssetSearchRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null));

            assertThat(boundParameters).containsEntry("limit", 50).containsEntry("offset", 0L);
        }
    }

    @Nested
    @DisplayName("Ergebnisaufbereitung")
    class ResultMapping {

        @Test
        @DisplayName("uebertraegt eine Zeile vollstaendig in das DTO")
        void mapsRow() {
            when(query.getResultList()).thenReturn(List.of(FakeTuple.of(
                    "itemId", 1L, "characterId", 2L, "characterName", "Pilot",
                    "mainId", 3L, "mainName", "Main", "corporationId", 4L, "corporationName", "Corp",
                    "typeId", 587L, "typeName", "Rifter", "groupId", 25L, "groupName", "Frigate",
                    "categoryId", 6L, "categoryName", "Ship", "quantity", 5L,
                    "locationName", "Jita IV", "systemName", "Jita", "regionName", "The Forge",
                    "locationFlag", "Hangar", "singleton", true, "customName", "Rostlaube",
                    "isBlueprintCopy", false, "isCorp", false, "unitPrice", 100.0, "totalValue", 500.0)));
            when(query.getSingleResult()).thenReturn(1L);

            AssetDtos.PageDto<AssetDtos.AssetRowDto> page = repository.search(request(null, null));

            assertThat(page.content()).singleElement().satisfies(row -> {
                assertThat(row.typeName()).isEqualTo("Rifter");
                assertThat(row.customName()).isEqualTo("Rostlaube");
                assertThat(row.totalValue()).isEqualTo(500.0);
                assertThat(row.singleton()).isTrue();
            });
            assertThat(page.pageValue()).isEqualTo(500.0);
        }

        @Test
        @DisplayName("liest fehlende Spalten als neutrale Werte")
        void readsMissingColumnsAsNeutral() {
            when(query.getResultList()).thenReturn(List.of(FakeTuple.of("typeName", "Rifter")));

            AssetDtos.PageDto<AssetDtos.AssetRowDto> page = repository.search(request(null, null));

            assertThat(page.content()).singleElement().satisfies(row -> {
                assertThat(row.itemId()).isZero();
                assertThat(row.totalValue()).isZero();
                assertThat(row.customName()).isNull();
                assertThat(row.isCorp()).isFalse();
            });
        }

        @Test
        @DisplayName("rechnet die Seitenanzahl aus Treffern und Seitengroesse")
        void computesTotalPages() {
            when(query.getSingleResult()).thenReturn(101L);

            AssetDtos.PageDto<AssetDtos.AssetRowDto> page = repository.search(
                    new AssetDtos.AssetSearchRequest(null, null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, 0, 50, false));

            assertThat(page.totalElements()).isEqualTo(101L);
            assertThat(page.totalPages()).isEqualTo(3);
        }

        @Test
        @DisplayName("fasst in der gruppierten Suche zu Stapeln zusammen")
        void mapsGroupedRow() {
            when(query.getResultList()).thenReturn(List.of(FakeTuple.of(
                    "typeId", 587L, "typeName", "Rifter", "groupName", "Frigate",
                    "categoryName", "Ship", "mainId", 3L, "mainName", "Main",
                    "corporationName", "Corp", "isBlueprintCopy", false, "isCorp", true,
                    "quantity", 12L, "locationCount", 3L, "unitPrice", 100.0, "totalValue", 1200.0)));

            AssetDtos.PageDto<AssetDtos.AssetStackDto> page = repository.searchGrouped(request(null, null));

            assertThat(page.content()).singleElement().satisfies(stack -> {
                assertThat(stack.quantity()).isEqualTo(12L);
                assertThat(stack.locationCount()).isEqualTo(3);
                assertThat(stack.isCorp()).isTrue();
            });
        }
    }

    @Nested
    @DisplayName("Auswertungen und Filterlisten")
    class Aggregations {

        @Test
        @DisplayName("liefert die Kennzahlen der Uebersicht")
        void readsTotals() {
            when(query.getResultList()).thenReturn(List.of(FakeTuple.of("stacks", 10L, "value", 5.0)));

            assertThat(repository.totals()).isNotNull();
        }

        @Test
        @DisplayName("gibt ohne Ergebniszeile nichts zurueck")
        void totalsWithoutRow() {
            assertThat(repository.totals()).isNull();
            assertThat(repository.findTypeInfo(1L)).isNull();
        }

        @Test
        @DisplayName("fuehrt die Eimer-Auswertungen ueber dieselbe Vorlage aus")
        void runsBucketQueries() {
            repository.valueByCorporation();
            repository.valueByCategory();
            repository.valueByRegion();

            assertThat(executedSql).hasSize(3);
            assertThat(boundParameters).containsKey("limit");
        }

        @Test
        @DisplayName("begrenzt die Bestenlisten auf die angeforderte Menge")
        void limitsTopLists() {
            repository.topTypes(15);
            assertThat(boundParameters).containsEntry("limit", 15);

            repository.topHolders(5);
            assertThat(boundParameters).containsEntry("limit", 5);
        }

        @Test
        @DisplayName("fragt die Detailauswertungen auf einen Account eingegrenzt ab")
        void scopesMemberQueries() {
            repository.memberByCategory(42L);
            assertThat(boundParameters).containsEntry("mainId", 42L);

            repository.memberByLocation(42L);
            assertThat(lastSql()).contains("a.owner_id = :mainId");
        }

        @Test
        @DisplayName("fragt die Doktrin-Bestaende ueber eine Typenliste ab")
        void queriesDoctrineOwnership() {
            repository.doctrineOwnership(List.of(1L, 2L));

            assertThat(boundParameters).containsEntry("typeIds", List.of(1L, 2L));
        }

        @Test
        @DisplayName("liefert die globalen Filterlisten")
        void readsGlobalFilterOptions() {
            repository.distinctCategories();
            repository.distinctLocations();
            repository.distinctRegions();
            repository.distinctLocationFlags();
            repository.distinctCorporations();
            repository.distinctMains();

            assertThat(executedSql).hasSize(6);
        }

        @Test
        @DisplayName("filtert die Gruppenliste optional nach Kategorie")
        void filtersGroupsByCategory() {
            repository.distinctGroups(6L);
            assertThat(boundParameters).containsEntry("categoryId", 6L);

            boundParameters.clear();
            repository.distinctGroups(null);
            assertThat(boundParameters).doesNotContainKey("categoryId");
        }

        @Test
        @DisplayName("grenzt die Filterlisten eines Mitglieds auf seinen Account ein")
        void scopesMemberFilterOptions() {
            // Sonst saehe ein Mitglied ueber die Dropdowns, wo die ganze Corp
            // ihre Sachen stehen hat.
            repository.distinctCategoriesForMain(42L);
            repository.distinctLocationsForMain(42L);
            repository.distinctRegionsForMain(42L);
            repository.distinctLocationFlagsForMain(42L);
            repository.charactersOfMain(42L);

            assertThat(executedSql).allSatisfy(sql -> assertThat(sql).contains(":mainId"));
            assertThat(boundParameters).containsEntry("mainId", 42L);
        }

        @Test
        @DisplayName("grenzt auch die Gruppenliste eines Mitglieds ein")
        void scopesMemberGroups() {
            repository.distinctGroupsForMain(42L, 6L);

            assertThat(lastSql()).contains("a.owner_id = :mainId").contains("cat.\"categoryID\"");
            assertThat(boundParameters).containsEntry("mainId", 42L).containsEntry("categoryId", 6L);
        }

        @Test
        @DisplayName("sucht Typen global und auf einen Account eingegrenzt")
        void suggestsTypes() {
            repository.suggestTypes("nes", 10);
            assertThat(boundParameters).containsEntry("term", "nes").containsEntry("limit", 10);

            repository.suggestTypesForMain(42L, null, 10);
            assertThat(boundParameters).containsEntry("term", "").containsEntry("mainId", 42L);
        }

        @Test
        @DisplayName("liefert die Besitzer eines Typs")
        void findsHolders() {
            repository.findHoldersOfType(587L);

            assertThat(boundParameters).containsEntry("typeId", 587L);
            assertThat(lastSql()).contains("GROUP BY");
        }
    }

    @Nested
    @DisplayName("Lesen einzelner Spalten")
    class ColumnReaders {

        private final Tuple row = FakeTuple.of(
                "zahl", 42, "kommazahl", 1.5, "text", "wert", "wahrheit", "true", "leer", null);

        @Test
        @DisplayName("liest Zahlen, Text und Wahrheitswerte")
        void readsTypedValues() {
            assertThat(AssetQueryRepository.lng(row, "zahl")).isEqualTo(42L);
            assertThat(AssetQueryRepository.dbl(row, "kommazahl")).isEqualTo(1.5);
            assertThat(AssetQueryRepository.str(row, "text")).isEqualTo("wert");
            assertThat(AssetQueryRepository.bool(row, "wahrheit")).isTrue();
        }

        @Test
        @DisplayName("gibt fuer leere Spalten neutrale Werte zurueck")
        void readsNullAsNeutral() {
            assertThat(AssetQueryRepository.lng(row, "leer")).isZero();
            assertThat(AssetQueryRepository.dbl(row, "leer")).isZero();
            assertThat(AssetQueryRepository.str(row, "leer")).isNull();
            assertThat(AssetQueryRepository.bool(row, "leer")).isFalse();
        }

        @Test
        @DisplayName("gibt fuer unbekannte Spalten neutrale Werte zurueck")
        void readsUnknownAliasAsNeutral() {
            // Nicht jede Query liefert jede Spalte - das darf nicht knallen.
            assertThat(AssetQueryRepository.lng(row, "gibtsnicht")).isZero();
            assertThat(AssetQueryRepository.str(row, "gibtsnicht")).isNull();
        }
    }
}
