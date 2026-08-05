package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.assets.service.AssetLocationService;
import com.eve.own.auth.backend.domain.character.entity.CharacterAsset;
import com.eve.own.auth.backend.domain.character.entity.CorporationAsset;
import com.eve.own.auth.backend.esi.EsiService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Uebersetzt ESI-Bestaende in die eigenen Entitaeten.
 *
 * <p>Persoenliche und Corp-Bestaende unterscheiden sich allein in der Spalte,
 * die den Besitzer festhaelt. Alles andere - Wurzelstandort, Custom-Name,
 * Mengen-Vorbelegung - ist identisch und steht deshalb nur einmal hier.</p>
 */
@Component
public class AssetMapper {

    /** ESI laesst die Menge bei zusammengebauten Einzelstuecken weg. */
    private static final int DEFAULT_QUANTITY = 1;

    private final AssetLocationService assetLocationService;

    public AssetMapper(AssetLocationService assetLocationService) {
        this.assetLocationService = assetLocationService;
    }

    public List<CharacterAsset> toCharacterAssets(Long characterId,
                                                  List<EsiService.EsiAssetResponse> esiAssets,
                                                  Map<Long, String> customNames) {
        return map(esiAssets, customNames, CharacterAsset::new, (asset, source) -> {
            asset.setCharacterId(characterId);
            asset.setItemId(source.item_id());
            asset.setTypeId(source.type_id());
            asset.setLocationId(source.location_id());
            asset.setLocationFlag(source.location_flag());
            asset.setLocationType(source.location_type());
            asset.setSingleton(source.is_singleton());
            asset.setBlueprintCopy(source.is_blueprint_copy());
        }, CharacterAsset::setRootLocationId, CharacterAsset::setCustomName, CharacterAsset::setQuantity);
    }

    public List<CorporationAsset> toCorporationAssets(Long corporationId,
                                                      List<EsiService.EsiAssetResponse> esiAssets,
                                                      Map<Long, String> customNames) {
        return map(esiAssets, customNames, CorporationAsset::new, (asset, source) -> {
            asset.setCorporationId(corporationId);
            asset.setItemId(source.item_id());
            asset.setTypeId(source.type_id());
            asset.setLocationId(source.location_id());
            asset.setLocationFlag(source.location_flag());
            asset.setLocationType(source.location_type());
            asset.setSingleton(source.is_singleton());
            asset.setBlueprintCopy(source.is_blueprint_copy());
        }, CorporationAsset::setRootLocationId, CorporationAsset::setCustomName, CorporationAsset::setQuantity);
    }

    private <T> List<T> map(List<EsiService.EsiAssetResponse> esiAssets,
                            Map<Long, String> customNames,
                            Supplier<T> factory,
                            BiConsumer<T, EsiService.EsiAssetResponse> commonFields,
                            BiConsumer<T, Long> rootLocationSetter,
                            BiConsumer<T, String> customNameSetter,
                            BiConsumer<T, Integer> quantitySetter) {

        Map<Long, Long> locationByItem = locationByItem(esiAssets);

        return esiAssets.stream().map(source -> {
            T asset = factory.get();
            commonFields.accept(asset, source);
            rootLocationSetter.accept(asset,
                    assetLocationService.resolveRootLocation(locationByItem, source.location_id()));
            customNameSetter.accept(asset, customNames.get(source.item_id()));
            quantitySetter.accept(asset,
                    source.quantity() != null ? source.quantity() : DEFAULT_QUANTITY);
            return asset;
        }).toList();
    }

    /**
     * item_id auf location_id, damit sich die Container-Kette hochlaufen laesst.
     *
     * <p>Beispiel: Modul liegt in einem Container, der Container in einem Schiff,
     * das Schiff steht in einer Citadel. Erst die Citadel ist der Standort, den
     * ein Mensch sucht.</p>
     */
    private static Map<Long, Long> locationByItem(List<EsiService.EsiAssetResponse> esiAssets) {
        Map<Long, Long> locationByItem = new HashMap<>();
        for (EsiService.EsiAssetResponse asset : esiAssets) {
            if (asset.item_id() != null) {
                locationByItem.put(asset.item_id(), asset.location_id());
            }
        }
        return locationByItem;
    }
}
