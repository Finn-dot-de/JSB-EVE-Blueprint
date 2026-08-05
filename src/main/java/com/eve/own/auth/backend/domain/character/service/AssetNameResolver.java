package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.esi.EsiHttpStatus;
import com.eve.own.auth.backend.esi.EsiService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * Holt die ingame vergebenen Namen zusammengebauter Items.
 *
 * <p>Der Namens-Endpunkt ist teuer und eigenwillig: er nimmt maximal
 * {@link EsiService#ASSET_NAMES_MAX_IDS} IDs pro Aufruf, liefert keine ETags und
 * antwortet fuer nicht benennbare Typen mit einem Platzhalter. Diese Eigenheiten
 * sind hier einmal abgehandelt - fuer Charakter- und Corp-Bestaende
 * gleichermassen, die sich zuvor zwei fast identische Methoden geteilt haben.</p>
 */
@Slf4j
@Service
public class AssetNameResolver {

    /**
     * ESI antwortet fuer unbenannte Schiffe und Container mit dem Literal "None".
     * Das ist kein Name, sondern ein Platzhalter - ohne die Normalisierung stuende
     * im Frontend spaeter bei jedem ungetauften Schiff "None".
     */
    private static final String ESI_UNNAMED_PLACEHOLDER = "None";

    /** Leerstring heisst: abgefragt, aber kein Name vergeben. Siehe CharacterAsset#getCustomName. */
    private static final String QUERIED_WITHOUT_NAME = "";

    private final InvTypeRepository invTypeRepo;

    public AssetNameResolver(InvTypeRepository invTypeRepo) {
        this.invTypeRepo = invTypeRepo;
    }

    /**
     * Laedt einen Block von item_ids beim jeweiligen ESI-Endpunkt.
     *
     * <p>Charakter- und Corp-Endpunkt unterscheiden sich nur in dieser einen
     * Zeile, deshalb reicht sie als Parameter.</p>
     */
    @FunctionalInterface
    public interface BatchFetcher {
        EsiService.EsiAssetNameResponse[] fetch(List<Long> itemIds);
    }

    /**
     * Ermittelt die Custom-Namen aller benennbaren Bestaende.
     *
     * @param assets  die Bestaende, aus denen die benennbaren herausgesucht werden
     * @param owner   Name des Besitzers, nur fuer die Protokollierung
     * @param fetcher Zugriff auf den passenden ESI-Endpunkt
     * @return item_id auf Name; Leerstring bedeutet "abgefragt, kein Name vergeben"
     * @throws RestClientResponseException bei 420, damit die zentrale Drosselung greift
     */
    public Map<Long, String> resolve(List<EsiService.EsiAssetResponse> assets, String owner, BatchFetcher fetcher) {
        List<Long> itemIds = nameableItemIds(assets);
        if (itemIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> names = new HashMap<>();
        for (int start = 0; start < itemIds.size(); start += EsiService.ASSET_NAMES_MAX_IDS) {
            int end = Math.min(start + EsiService.ASSET_NAMES_MAX_IDS, itemIds.size());
            fetchBatch(itemIds.subList(start, end), owner, start, fetcher, names);
        }

        log.debug("Asset-Namen fuer {}: {} benennbare Items in {} Block/Bloecken.",
                owner, itemIds.size(), batchCount(itemIds.size()));
        return names;
    }

    private void fetchBatch(List<Long> batch, String owner, int offset,
                            BatchFetcher fetcher, Map<Long, String> names) {
        try {
            EsiService.EsiAssetNameResponse[] response = fetcher.fetch(batch);
            if (response == null) {
                return;
            }
            // Erst nach erfolgreicher Antwort als "abgefragt" markieren - sonst
            // gaelten die Namen als geholt, obwohl der Aufruf gescheitert ist.
            batch.forEach(itemId -> names.put(itemId, QUERIED_WITHOUT_NAME));
            for (EsiService.EsiAssetNameResponse entry : response) {
                if (entry.item_id() != null) {
                    names.put(entry.item_id(), normalize(entry.name()));
                }
            }
        } catch (RestClientResponseException e) {
            if (EsiHttpStatus.isErrorLimited(e)) {
                throw e;
            }
            log.warn("Asset-Namen fuer {} (Block ab {}, {} IDs) nicht abrufbar: {} - {}",
                    owner, offset, batch.size(), e.getStatusCode(), e.getResponseBodyAsString());
            if (EsiHttpStatus.isNotFound(e)) {
                // Dauerhaft nicht aufloesbar: als abgefragt vermerken, damit der
                // naechste Lauf nicht erneut daran haengenbleibt.
                batch.forEach(itemId -> names.putIfAbsent(itemId, QUERIED_WITHOUT_NAME));
            }
        }
    }

    /**
     * Nur zusammengebaute Items koennen einen Namen tragen, und auch das nur in
     * bestimmten SDE-Kategorien (Schiffe, Container, Strukturen).
     */
    private List<Long> nameableItemIds(List<EsiService.EsiAssetResponse> assets) {
        List<EsiService.EsiAssetResponse> singletons = assets.stream()
                .filter(asset -> Boolean.TRUE.equals(asset.is_singleton()))
                .filter(asset -> asset.item_id() != null && asset.type_id() != null)
                .toList();

        if (singletons.isEmpty()) {
            return List.of();
        }

        Set<Long> nameableTypes = new HashSet<>(invTypeRepo.findNameableTypeIds(
                singletons.stream().map(EsiService.EsiAssetResponse::type_id).distinct().toList()));

        return singletons.stream()
                .filter(asset -> nameableTypes.contains(asset.type_id()))
                .map(EsiService.EsiAssetResponse::item_id)
                .distinct()
                .toList();
    }

    private static String normalize(String name) {
        if (name == null || name.isBlank() || ESI_UNNAMED_PLACEHOLDER.equals(name.trim())) {
            return QUERIED_WITHOUT_NAME;
        }
        return name.trim();
    }

    private static int batchCount(int itemCount) {
        return (itemCount + EsiService.ASSET_NAMES_MAX_IDS - 1) / EsiService.ASSET_NAMES_MAX_IDS;
    }
}
