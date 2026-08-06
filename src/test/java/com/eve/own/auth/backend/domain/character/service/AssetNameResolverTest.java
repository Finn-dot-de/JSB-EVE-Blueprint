package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.esi.EsiHttpStatus;
import com.eve.own.auth.backend.esi.EsiService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Ingame vergebene Namen zusammengebauter Items")
class AssetNameResolverTest {

    private static final Long SHIP_TYPE = 17738L;
    private static final Long AMMO_TYPE = 12608L;

    @Mock private InvTypeRepository invTypeRepo;

    private AssetNameResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AssetNameResolver(invTypeRepo);
        when(invTypeRepo.findNameableTypeIds(anyList())).thenAnswer(call -> call.getArgument(0));
    }

    private static EsiService.EsiAssetResponse asset(Long itemId, Long typeId, boolean singleton) {
        return new EsiService.EsiAssetResponse(itemId, typeId, 60003760L, 1, singleton,
                "Hangar", "station", false);
    }

    private static EsiService.EsiAssetNameResponse name(Long itemId, String name) {
        return new EsiService.EsiAssetNameResponse(itemId, name);
    }

    private static RestClientResponseException httpError(int status) {
        return new RestClientResponseException("Fehler " + status, status, "", HttpHeaders.EMPTY, null, null);
    }

    @Test
    @DisplayName("liefert die Namen der benennbaren Items")
    void resolvesNames() {
        List<EsiService.EsiAssetResponse> assets = List.of(asset(1L, SHIP_TYPE, true));

        Map<Long, String> names = resolver.resolve(assets, "Pilot Eins",
                batch -> new EsiService.EsiAssetNameResponse[]{name(1L, "Rostlaube")});

        assertThat(names).containsEntry(1L, "Rostlaube");
    }

    @Test
    @DisplayName("wertet den ESI-Platzhalter 'None' als 'kein Name vergeben'")
    void normalisesEsiPlaceholder() {
        List<EsiService.EsiAssetResponse> assets = List.of(asset(1L, SHIP_TYPE, true));

        Map<Long, String> names = resolver.resolve(assets, "Pilot Eins",
                batch -> new EsiService.EsiAssetNameResponse[]{name(1L, "None")});

        assertThat(names).containsEntry(1L, "");
    }

    @Test
    @DisplayName("merkt sich abgefragte Items ohne Namen als Leerstring")
    void marksQueriedItemsWithoutName() {
        // Nur so laesst sich spaeter unterscheiden: nie abgefragt (null) oder
        // abgefragt und namenlos ("").
        List<EsiService.EsiAssetResponse> assets = List.of(asset(1L, SHIP_TYPE, true));

        Map<Long, String> names = resolver.resolve(assets, "Pilot Eins",
                batch -> new EsiService.EsiAssetNameResponse[0]);

        assertThat(names).containsEntry(1L, "");
    }

    @Test
    @DisplayName("fragt verpackte Bestaende gar nicht erst ab")
    void skipsPackagedAssets() {
        List<EsiService.EsiAssetResponse> assets = List.of(asset(1L, AMMO_TYPE, false));

        Map<Long, String> names = resolver.resolve(assets, "Pilot Eins", batch -> {
            throw new AssertionError("Fuer verpackte Bestaende darf kein Aufruf erfolgen.");
        });

        assertThat(names).isEmpty();
        verifyNoInteractions(invTypeRepo);
    }

    @Test
    @DisplayName("fragt Typen ab, die laut SDE gar keinen Namen tragen koennen")
    void skipsTypesThatCannotBeNamed() {
        when(invTypeRepo.findNameableTypeIds(anyList())).thenReturn(List.of());
        List<EsiService.EsiAssetResponse> assets = List.of(asset(1L, AMMO_TYPE, true));

        Map<Long, String> names = resolver.resolve(assets, "Pilot Eins", batch -> {
            throw new AssertionError("Nicht benennbare Typen duerfen keinen Aufruf ausloesen.");
        });

        assertThat(names).isEmpty();
    }

    @Test
    @DisplayName("teilt grosse Mengen in Bloecke der von ESI erlaubten Groesse")
    void splitsIntoBatches() {
        List<EsiService.EsiAssetResponse> assets = new ArrayList<>();
        int total = EsiService.ASSET_NAMES_MAX_IDS + 5;
        for (long itemId = 1; itemId <= total; itemId++) {
            assets.add(asset(itemId, SHIP_TYPE, true));
        }

        List<Integer> batchSizes = new ArrayList<>();
        resolver.resolve(assets, "Pilot Eins", batch -> {
            batchSizes.add(batch.size());
            return new EsiService.EsiAssetNameResponse[0];
        });

        assertThat(batchSizes).containsExactly(EsiService.ASSET_NAMES_MAX_IDS, 5);
    }

    @Test
    @DisplayName("reicht das aufgebrauchte Fehler-Budget nach oben durch")
    void propagatesErrorLimit() {
        List<EsiService.EsiAssetResponse> assets = List.of(asset(1L, SHIP_TYPE, true));

        assertThatThrownBy(() -> resolver.resolve(assets, "Pilot Eins", batch -> {
            throw httpError(EsiHttpStatus.ERROR_LIMITED);
        })).isInstanceOf(RestClientResponseException.class);
    }

    @Test
    @DisplayName("merkt dauerhaft unauffindbare Items als abgefragt vor")
    void marksNotFoundItemsAsQueried() {
        List<EsiService.EsiAssetResponse> assets = List.of(asset(1L, SHIP_TYPE, true));

        Map<Long, String> names = resolver.resolve(assets, "Pilot Eins", batch -> {
            throw httpError(404);
        });

        assertThat(names).containsEntry(1L, "");
    }

    @Test
    @DisplayName("laesst einen Block bei anderen Fehlern unmarkiert, damit er erneut laeuft")
    void leavesBatchOpenOnOtherErrors() {
        List<EsiService.EsiAssetResponse> assets = List.of(asset(1L, SHIP_TYPE, true));

        Map<Long, String> names = resolver.resolve(assets, "Pilot Eins", batch -> {
            throw httpError(500);
        });

        assertThat(names).isEmpty();
    }

    @Test
    @DisplayName("kommt mit einer leeren Antwort zurecht")
    void toleratesNullResponse() {
        List<EsiService.EsiAssetResponse> assets = List.of(asset(1L, SHIP_TYPE, true));

        assertThat(resolver.resolve(assets, "Pilot Eins", batch -> null)).isEmpty();
    }

    @Test
    @DisplayName("ignoriert Bestaende ohne item_id oder type_id")
    void ignoresIncompleteAssets() {
        List<EsiService.EsiAssetResponse> assets = List.of(
                asset(null, SHIP_TYPE, true), asset(1L, null, true));

        assertThat(resolver.resolve(assets, "Pilot Eins", batch -> {
            throw new AssertionError("Unvollstaendige Bestaende duerfen keinen Aufruf ausloesen.");
        })).isEmpty();
    }

    @Test
    @DisplayName("fragt jeden Typ nur einmal bei der SDE nach")
    void queriesEachTypeOnce() {
        List<EsiService.EsiAssetResponse> assets = List.of(
                asset(1L, SHIP_TYPE, true), asset(2L, SHIP_TYPE, true));

        resolver.resolve(assets, "Pilot Eins", batch -> new EsiService.EsiAssetNameResponse[0]);

        verify(invTypeRepo).findNameableTypeIds(List.of(SHIP_TYPE));
    }
}
