package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.assets.service.AssetLocationService;
import com.eve.own.auth.backend.domain.character.entity.CharacterAsset;
import com.eve.own.auth.backend.domain.character.entity.CorporationAsset;
import com.eve.own.auth.backend.esi.EsiService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Uebersetzung der ESI-Bestaende")
class AssetMapperTest {

    private static final Long CHARACTER_ID = 1000L;
    private static final Long CORPORATION_ID = 98000001L;
    private static final Long STATION = 60003760L;

    @Mock private AssetLocationService assetLocationService;

    private AssetMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new AssetMapper(assetLocationService);
        when(assetLocationService.resolveRootLocation(anyMap(), any()))
                .thenAnswer(call -> call.getArgument(1));
    }

    private static EsiService.EsiAssetResponse asset(Long itemId, Long locationId, Integer quantity) {
        return new EsiService.EsiAssetResponse(itemId, 587L, locationId, quantity, true,
                "Hangar", "station", false);
    }

    @Test
    @DisplayName("uebernimmt alle Felder eines persoenlichen Bestands")
    void mapsCharacterAsset() {
        List<CharacterAsset> assets = mapper.toCharacterAssets(CHARACTER_ID,
                List.of(asset(1L, STATION, 5)), Map.of(1L, "Rostlaube"));

        assertThat(assets).singleElement().satisfies(asset -> {
            assertThat(asset.getItemId()).isEqualTo(1L);
            assertThat(asset.getCharacterId()).isEqualTo(CHARACTER_ID);
            assertThat(asset.getTypeId()).isEqualTo(587L);
            assertThat(asset.getLocationId()).isEqualTo(STATION);
            assertThat(asset.getRootLocationId()).isEqualTo(STATION);
            assertThat(asset.getLocationFlag()).isEqualTo("Hangar");
            assertThat(asset.getLocationType()).isEqualTo("station");
            assertThat(asset.getSingleton()).isTrue();
            assertThat(asset.getBlueprintCopy()).isFalse();
            assertThat(asset.getCustomName()).isEqualTo("Rostlaube");
            assertThat(asset.getQuantity()).isEqualTo(5);
        });
    }

    @Test
    @DisplayName("uebernimmt alle Felder eines Corp-Bestands")
    void mapsCorporationAsset() {
        List<CorporationAsset> assets = mapper.toCorporationAssets(CORPORATION_ID,
                List.of(asset(1L, STATION, 5)), Map.of());

        assertThat(assets).singleElement().satisfies(asset -> {
            assertThat(asset.getCorporationId()).isEqualTo(CORPORATION_ID);
            assertThat(asset.getItemId()).isEqualTo(1L);
            assertThat(asset.getCustomName()).isNull();
            assertThat(asset.getQuantity()).isEqualTo(5);
        });
    }

    @Test
    @DisplayName("setzt fehlende Mengen auf ein Stueck")
    void defaultsQuantityToOne() {
        // ESI laesst die Menge bei zusammengebauten Einzelstuecken weg.
        List<CharacterAsset> assets = mapper.toCharacterAssets(CHARACTER_ID,
                List.of(asset(1L, STATION, null)), Map.of());

        assertThat(assets.getFirst().getQuantity()).isEqualTo(1);
    }

    @Test
    @DisplayName("reicht die Container-Kette zur Aufloesung des Standorts durch")
    void passesContainerChainToLocationService() {
        // Modul liegt in einem Container, der Container in der Station.
        EsiService.EsiAssetResponse container = asset(100L, STATION, 1);
        EsiService.EsiAssetResponse module = asset(200L, 100L, 1);

        mapper.toCharacterAssets(CHARACTER_ID, List.of(container, module), Map.of());

        ArgumentCaptor<Map<Long, Long>> chain = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(assetLocationService, org.mockito.Mockito.atLeastOnce())
                .resolveRootLocation(chain.capture(), any());
        assertThat(chain.getValue()).containsEntry(100L, STATION).containsEntry(200L, 100L);
    }

    @Test
    @DisplayName("laesst Bestaende ohne item_id nicht in die Kette einfliessen")
    void ignoresAssetsWithoutItemId() {
        mapper.toCharacterAssets(CHARACTER_ID, List.of(asset(null, STATION, 1)), Map.of());

        ArgumentCaptor<Map<Long, Long>> chain = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(assetLocationService)
                .resolveRootLocation(chain.capture(), any());
        assertThat(chain.getValue()).isEmpty();
    }

    @Test
    @DisplayName("kommt mit einer leeren Bestandsliste zurecht")
    void handlesEmptyInput() {
        assertThat(mapper.toCharacterAssets(CHARACTER_ID, List.of(), Map.of())).isEmpty();
        assertThat(mapper.toCorporationAssets(CORPORATION_ID, List.of(), Map.of())).isEmpty();
    }
}
