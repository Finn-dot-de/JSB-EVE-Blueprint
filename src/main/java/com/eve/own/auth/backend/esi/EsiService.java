package com.eve.own.auth.backend.esi;

import com.eve.own.auth.backend.esi.client.EsiRequestExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.Instant;

/**
 * Typisierte Sicht auf die ESI-Endpunkte.
 *
 * <p>Diese Klasse beschreibt nur noch <em>welcher</em> Endpunkt mit welchem Typ
 * abgefragt wird. Das <em>wie</em> - konditionale Requests, 304-Behandlung,
 * Paginierung - liegt vollstaendig im {@link EsiRequestExecutor}. Deshalb gibt es
 * hier auch keine ETag-Parameter mehr: der Cache wird zentral verwaltet.</p>
 */
@Slf4j
@Service
public class EsiService {

    private static final String JITA_TRADE_HUB_STATION = "60003760";
    private static final String FUZZWORK_AGGREGATES_URL =
            "https://market.fuzzwork.co.uk/aggregates/?station=" + JITA_TRADE_HUB_STATION + "&types=";

    private final EsiRequestExecutor executor;
    private final RestClient marketClient;

    public EsiService(EsiRequestExecutor executor) {
        this.executor = executor;
        this.marketClient = RestClient.create();
    }

    // ==================================================================
    // Oeffentliche Stammdaten
    // ==================================================================

    public EsiResponse<EsiCharacterResponse> getCharacter(Long characterId) {
        return executor.get("/characters/{id}/", new Object[]{characterId}, null, EsiCharacterResponse.class);
    }

    public EsiResponse<EsiCorporationResponse> getCorporation(Long corporationId) {
        return executor.get("/corporations/{id}/", new Object[]{corporationId}, null, EsiCorporationResponse.class);
    }

    public EsiResponse<EsiAllianceResponse> getAlliance(Long allianceId) {
        return executor.get("/alliances/{id}/", new Object[]{allianceId}, null, EsiAllianceResponse.class);
    }

    /** Bequemer Direktzugriff fuer Aufrufer, die nur den Datensatz brauchen. */
    public EsiCorporationResponse getCorporationInfo(Long corporationId) {
        return getCorporation(corporationId).data();
    }

    // ==================================================================
    // Charakterdaten (Token noetig)
    // ==================================================================

    public EsiResponse<Double> getWalletBalance(Long characterId, String token) {
        return executor.get("/characters/{id}/wallet/", new Object[]{characterId}, token, Double.class);
    }

    public EsiResponse<SkillResponse> getSkills(Long characterId, String token) {
        return executor.get("/characters/{id}/skills/", new Object[]{characterId}, token, SkillResponse.class);
    }

    public EsiResponse<EsiLpResponse[]> getLoyaltyPoints(Long characterId, String token) {
        return executor.get("/characters/{id}/loyalty/points/", new Object[]{characterId}, token, EsiLpResponse[].class);
    }

    public EsiResponse<EsiMiningResponse[]> getMiningLedger(Long characterId, String token) {
        return executor.get("/characters/{id}/mining/", new Object[]{characterId}, token, EsiMiningResponse[].class);
    }

    public EsiResponse<EsiJournalResponse[]> getWalletJournal(Long characterId, String token) {
        return executor.get("/characters/{id}/wallet/journal/", new Object[]{characterId}, token, EsiJournalResponse[].class);
    }

    public EsiResponse<EsiTitleResponse[]> getCharacterTitles(Long characterId, String token) {
        return executor.get("/characters/{id}/titles/", new Object[]{characterId}, token, EsiTitleResponse[].class);
    }

    public EsiResponse<EsiOnlineResponse> getCharacterOnlineStatus(Long characterId, String token) {
        return executor.get("/characters/{id}/online/", new Object[]{characterId}, token, EsiOnlineResponse.class);
    }

    /** Paginierter Endpunkt: jede Seite wird einzeln per ETag geprueft. */
    public EsiResponse<List<EsiAssetResponse>> getAllAssets(Long characterId, String token) {
        return executor.getAllPages("/characters/{id}/assets/", new Object[]{characterId}, token, EsiAssetResponse[].class);
    }

    /** Maximale Anzahl item_ids, die CCP pro Namens-Request akzeptiert. */
    public static final int ASSET_NAMES_MAX_IDS = 1000;

    /**
     * Ingame vergebene Namen zusammengebauter Items (Schiffe, Container, Strukturen).
     *
     * <p>Bewusst ohne ETag-Cache: ESI liefert fuer diesen POST keine ETags. Fehler
     * werden hier nicht geschluckt, damit der Aufrufer ein 420 (Error-Limit) noch
     * an die zentrale Drosselung durchreichen kann.</p>
     *
     * <p>Die Aufteilung in Bloecke von maximal {@link #ASSET_NAMES_MAX_IDS} IDs
     * liegt beim Aufrufer.</p>
     */
    public EsiAssetNameResponse[] getAssetNames(Long characterId, String token, List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return new EsiAssetNameResponse[0];
        }
        if (itemIds.size() > ASSET_NAMES_MAX_IDS) {
            throw new IllegalArgumentException(
                    "ESI akzeptiert maximal " + ASSET_NAMES_MAX_IDS + " item_ids pro Request, erhalten: " + itemIds.size());
        }
        return executor.post("/characters/{id}/assets/names/", new Object[]{characterId},
                itemIds.toArray(new Long[0]), token, EsiAssetNameResponse[].class);
    }

    // ==================================================================
    // Corporation (Token noetig)
    // ==================================================================

    public EsiResponse<EsiCorpTitleResponse[]> getCorporationTitles(Long corporationId, String token) {
        return executor.get("/corporations/{id}/titles/", new Object[]{corporationId}, token, EsiCorpTitleResponse[].class);
    }

    public EsiResponse<Long[]> getCorporationMembers(Long corporationId, String token) {
        return executor.get("/corporations/{id}/members/", new Object[]{corporationId}, token, Long[].class);
    }

    /**
     * Alle Corp-Bestaende. Paginierter Endpunkt, jede Seite wird per ETag geprueft.
     *
     * <p>Verlangt den Scope {@code esi-assets.read_corporation_assets.v1} <em>und</em>
     * die Ingame-Rolle Director beim Token-Charakter. Ohne die Rolle antwortet ESI
     * mit 403 - deshalb probiert der Aufrufer mehrere Kandidaten durch.</p>
     */
    public EsiResponse<List<EsiAssetResponse>> getAllCorporationAssets(Long corporationId, String token) {
        return executor.getAllPages("/corporations/{id}/assets/", new Object[]{corporationId}, token,
                EsiAssetResponse[].class);
    }

    /** Custom-Namen von Corp-Bestaenden. Gleiches 1000er-Limit wie bei Charakteren. */
    public EsiAssetNameResponse[] getCorporationAssetNames(Long corporationId, String token, List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return new EsiAssetNameResponse[0];
        }
        if (itemIds.size() > ASSET_NAMES_MAX_IDS) {
            throw new IllegalArgumentException(
                    "ESI akzeptiert maximal " + ASSET_NAMES_MAX_IDS + " item_ids pro Request, erhalten: " + itemIds.size());
        }
        return executor.post("/corporations/{id}/assets/names/", new Object[]{corporationId},
                itemIds.toArray(new Long[0]), token, EsiAssetNameResponse[].class);
    }

    /** Namen der sieben Corp-Hangar-Divisionen (CorpSAG1 - CorpSAG7). */
    public EsiResponse<EsiDivisionsResponse> getCorporationDivisions(Long corporationId, String token) {
        return executor.get("/corporations/{id}/divisions/", new Object[]{corporationId}, token,
                EsiDivisionsResponse.class);
    }

    // ==================================================================
    // Fleet
    // ==================================================================

    public EsiResponse<EsiCharacterFleetResponse> getCharacterFleet(Long characterId, String token) {
        return executor.get("/characters/{id}/fleet/", new Object[]{characterId}, token, EsiCharacterFleetResponse.class);
    }

    public EsiResponse<EsiFleetMemberResponse[]> getFleetMembers(Long fleetId, String token) {
        return executor.get("/fleets/{id}/members/", new Object[]{fleetId}, token, EsiFleetMemberResponse[].class);
    }

    // ==================================================================
    // Universe
    // ==================================================================

    /**
     * Loest den Namen einer Upwell-Struktur auf.
     * Benoetigt den Scope esi-universe.read_structures.v1 UND Docking-Access
     * des Token-Charakters. Ohne Access antwortet ESI mit 403.
     */
    public EsiStructureResponse getStructureInfo(Long structureId, String token) {
        return executor.get("/universe/structures/{id}/", new Object[]{structureId}, token, EsiStructureResponse.class).data();
    }

    /**
     * Bulk-Aufloesung von IDs zu Namen.
     * ESI liefert fuer diesen POST-Endpunkt keinen ETag, deshalb ohne Cache.
     */
    public EsiIdName[] getUniverseNames(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new EsiIdName[0];
        }
        try {
            return executor.post("/universe/names/", ids.toArray(new Long[0]), EsiIdName[].class);
        } catch (Exception e) {
            log.warn("Bulk-Namensaufloesung fehlgeschlagen: {}", e.getMessage());
            return null;
        }
    }

    // ==================================================================
    // Marktpreise (Fremdanbieter, nicht ESI)
    // ==================================================================

    public Map<String, FuzzworkPrice> getFuzzworkPrices(List<Long> typeIds) {
        if (typeIds == null || typeIds.isEmpty()) {
            return Map.of();
        }
        String typesParam = typeIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            return marketClient.get()
                    .uri(FUZZWORK_AGGREGATES_URL + typesParam)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, FuzzworkPrice>>() {});
        } catch (Exception e) {
            log.warn("Fuzzwork-Preise nicht abrufbar: {}", e.getMessage());
            return Map.of();
        }
    }

    // ==================================================================
    // Antworttypen
    // ==================================================================

    public record FuzzworkPrice(FuzzworkBuy buy, FuzzworkSell sell) {}
    public record FuzzworkBuy(Double max) {}
    public record FuzzworkSell(Double min) {}
    public record EsiIdName(Long id, String name, String category) {}
    public record EsiOnlineResponse(Boolean online, String last_login, String last_logout, Integer logins) {}
    public record EsiCharacterFleetResponse(Long fleet_id, Long character_id, String role) {}
    public record EsiFleetMemberResponse(Long character_id, Instant join_time, String role, Long ship_type_id, Long solar_system_id) {}
    public record EsiCorpTitleResponse(Long title_id, String name) {}
    public record EsiTitleResponse(Long title_id, String name) {}
    public record EsiMiningResponse(String date, Long quantity, Long solar_system_id, Long type_id) {}
    public record EsiJournalResponse(Long id, String date, String ref_type, Double amount, Long second_party_id, String reason) {}
    public record EsiLpResponse(Long corporation_id, Integer loyalty_points) {}
    public record SkillResponse(Long total_sp, Integer unallocated_sp, EsiSkillEntry[] skills) {}

    public record EsiSkillEntry(Long skill_id, Integer active_skill_level,
                                Integer trained_skill_level, Long skillpoints_in_skill) {}
    public record EsiAssetResponse(Long item_id, Long type_id, Long location_id, Integer quantity,
                                   Boolean is_singleton, String location_flag, String location_type,
                                   Boolean is_blueprint_copy) {}
    public record EsiAssetNameResponse(Long item_id, String name) {}
    public record EsiDivisionsResponse(EsiDivision[] hangar, EsiDivision[] wallet) {}
    public record EsiDivision(Integer division, String name) {}
    public record EsiStructureResponse(String name, Long owner_id, Long solar_system_id, Long type_id) {}
    public record EsiCharacterResponse(String name, Long corporation_id) {}
    public record EsiCorporationResponse(String name, String ticker, Long alliance_id, Long faction_id) {}
    public record EsiAllianceResponse(String name, String ticker) {}
}
