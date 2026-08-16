package com.eve.own.auth.backend.domain.assets.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.assets.dto.AssetDtos;
import com.eve.own.auth.backend.domain.assets.service.AssetAnalyticsService;
import com.eve.own.auth.backend.domain.assets.service.AssetLocationService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Asset-Endpunkte fuer Direktoren")
class AssetControllerTest {

    @Mock private AssetAnalyticsService analyticsService;
    @Mock private AssetLocationService locationService;

    private AssetController controller;

    @BeforeEach
    void setUp() {
        controller = new AssetController(analyticsService, locationService);
        when(analyticsService.exportCsv(any())).thenReturn("Kopfzeile\nZeile\n");
    }

    private static <T> AssetDtos.PageDto<T> emptyPage() {
        return new AssetDtos.PageDto<>(List.of(), 0, 50, 0L, 0, 0d, 0d);
    }

    @Test
    @DisplayName("waehlt anhand des Schalters die flache oder die gruppierte Suche")
    void picksSearchVariant() {
        when(analyticsService.search(any())).thenReturn(emptyPage());
        when(analyticsService.searchGrouped(any())).thenReturn(emptyPage());

        controller.search(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, "value", "desc", 0, 50, false);
        verify(analyticsService).search(any());

        controller.search(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, "value", "desc", 0, 50, true);
        verify(analyticsService).searchGrouped(any());
    }

    @Test
    @DisplayName("reicht die Filter unveraendert an die Auswertung durch")
    void passesFiltersThrough() {
        when(analyticsService.search(any())).thenReturn(emptyPage());

        controller.search("Nestor", 1L, 2L, 3L, 4L, 5L, 6L, 7L, "Delve", "Hangar",
                10L, 100.0, true, "CORPORATION", "quantity", "asc", 2, 25, false);

        ArgumentCaptor<AssetDtos.AssetSearchRequest> captor =
                ArgumentCaptor.forClass(AssetDtos.AssetSearchRequest.class);
        verify(analyticsService).search(captor.capture());
        assertThat(captor.getValue().q()).isEqualTo("Nestor");
        assertThat(captor.getValue().ownerType()).isEqualTo("CORPORATION");
        assertThat(captor.getValue().page()).isEqualTo(2);
    }

    @Test
    @DisplayName("begrenzt die Zahl der Typ-Vorschlaege")
    void capsSuggestionLimit() {
        when(analyticsService.suggestTypes(anyString(), anyInt())).thenReturn(List.of());

        controller.suggestTypes("nes", 500);

        verify(analyticsService).suggestTypes("nes", 50);
    }

    @Test
    @DisplayName("reicht die Auswertungen durch")
    void delegatesReadEndpoints() {
        controller.holders(587L);
        verify(analyticsService).holdersOfType(587L);

        controller.summary();
        verify(analyticsService).summary();

        controller.filters(6L);
        verify(analyticsService).filterOptions(6L);

        controller.memberDetail(1000L);
        verify(analyticsService).memberDetail(1000L);

        controller.doctrines();
        verify(analyticsService).doctrineNames();

        controller.doctrineReadiness("Armor");
        verify(analyticsService).doctrineReadiness("Armor");
    }

    @Test
    @DisplayName("liefert den Export als Datei mit UTF-8-Kennung")
    void exportsCsvAsDownload() {
        var response = controller.export(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, "value", "desc", false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment").contains(".csv");

        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        // Ohne die Byte-Order-Mark zeigt Excel die Umlaute falsch an.
        assertThat(body).startsWith("﻿").contains("Kopfzeile");
    }

    @Test
    @DisplayName("holt fuer den Export bis zu 500 Zeilen der ersten Seite")
    void exportsFirstPage() {
        controller.export(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, "value", "desc", true);

        ArgumentCaptor<AssetDtos.AssetSearchRequest> captor =
                ArgumentCaptor.forClass(AssetDtos.AssetSearchRequest.class);
        verify(analyticsService).exportCsv(captor.capture());
        assertThat(captor.getValue().page()).isZero();
        assertThat(captor.getValue().size()).isEqualTo(500);
        assertThat(captor.getValue().grouped()).isTrue();
    }

    @Test
    @DisplayName("stoesst die Standort-Aufloesung an und bestaetigt das")
    void triggersLocationResolution() {
        var response = controller.resolveLocations();

        verify(locationService).resolvePendingLocations();
        assertThat(response.getBody().message()).contains("Standort-Aufloesung");
    }
}
