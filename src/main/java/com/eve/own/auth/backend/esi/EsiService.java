package com.eve.own.auth.backend.esi;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class EsiService {
    private final RestClient restClient;

    public record EsiResponse<T>(T data, String etag) {}

    public EsiService(RestClient esiClient) {
        this.restClient = esiClient;
    }

    private <T> EsiResponse<T> fetch(String uri, Object[] uriVariables, String token, String oldEtag, Class<T> responseType) {
        var request = restClient.get().uri(uri, uriVariables);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (oldEtag != null) request.header("If-None-Match", oldEtag);

        ResponseEntity<T> response = request.retrieve().toEntity(responseType);
        if (response.getStatusCode().value() == 304) {
            return new EsiResponse<>(null, oldEtag);
        }
        String newEtag = response.getHeaders().getFirst("ETag");
        return new EsiResponse<>(response.getBody(), newEtag);
    }

    public EsiResponse<EsiCharacterResponse> getCharacter(Long characterId, String etag) {
        return fetch("/characters/{id}/", new Object[]{characterId}, null, etag, EsiCharacterResponse.class);
    }

    public EsiResponse<EsiCorporationResponse> getCorporation(Long corpId, String etag) {
        return fetch("/corporations/{id}/", new Object[]{corpId}, null, etag, EsiCorporationResponse.class);
    }

    public EsiResponse<EsiAllianceResponse> getAlliance(Long allianceId, String etag) {
        return fetch("/alliances/{id}/", new Object[]{allianceId}, null, etag, EsiAllianceResponse.class);
    }

    public EsiResponse<Double> getWalletBalance(Long characterId, String token, String etag) {
        return fetch("/characters/{id}/wallet/", new Object[]{characterId}, token, etag, Double.class);
    }

    public EsiResponse<SkillResponse> getSkills(Long characterId, String token, String etag) {
        return fetch("/characters/{id}/skills/", new Object[]{characterId}, token, etag, SkillResponse.class);
    }

    public List<EsiAssetResponse> getAllAssets(Long characterId, String token) {
        List<EsiAssetResponse> allAssets = new ArrayList<>();
        int page = 1;
        int maxPages = 1;
        do {
            var response = restClient.get()
                    .uri("/characters/{id}/assets/?page={page}", characterId, page)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toEntity(EsiAssetResponse[].class);
            if (response.getBody() != null) {
                allAssets.addAll(List.of(response.getBody()));
            }
            String xPages = response.getHeaders().getFirst("X-Pages");
            if (xPages != null) {
                maxPages = Integer.parseInt(xPages);
            }
            page++;
        } while (page <= maxPages);
        return allAssets;
    }

    public EsiResponse<EsiLpResponse[]> getLoyaltyPoints(Long characterId, String token, String etag) {
        return fetch("/characters/{id}/loyalty/points/", new Object[]{characterId}, token, etag, EsiLpResponse[].class);
    }

    public EsiResponse<EsiMiningResponse[]> getMiningLedger(Long characterId, String token, String etag) {
        return fetch("/characters/{id}/mining/", new Object[]{characterId}, token, etag, EsiMiningResponse[].class);
    }

    public EsiResponse<EsiJournalResponse[]> getWalletJournal(Long characterId, String token, String etag) {
        return fetch("/characters/{id}/wallet/journal/", new Object[]{characterId}, token, etag, EsiJournalResponse[].class);
    }

    public EsiIdName[] getUniverseNames(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return new EsiIdName[0];
        Long[] requestBody = ids.toArray(new Long[0]);
        try {
            return restClient.post()
                    .uri("/universe/names/")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(EsiIdName[].class);
        } catch (Exception e) {
            System.err.println("Fehler bei Universe Names ESI Bulk-Abfrage: " + e.getMessage());
            return null;
        }
    }

    public EsiCorporationResponse getCorporationInfo(Long corporationId) {
        return fetch("/corporations/{id}/", new Object[]{corporationId}, null, null, EsiCorporationResponse.class).data();
    }

    public EsiResponse<EsiTitleResponse[]> getCharacterTitles(Long characterId, String token, String etag) {
        return fetch("/characters/{id}/titles/", new Object[]{characterId}, token, etag, EsiTitleResponse[].class);
    }

    public EsiResponse<EsiCorpTitleResponse[]> getCorporationTitles(Long corpId, String token, String etag) {
        return fetch("/corporations/{id}/titles/", new Object[]{corpId}, token, etag, EsiCorpTitleResponse[].class);
    }

    public EsiResponse<EsiCharacterFleetResponse> getCharacterFleet(Long characterId, String token) {
        return fetch("/characters/{id}/fleet/", new Object[]{characterId}, token, null, EsiCharacterFleetResponse.class);
    }

    public EsiResponse<EsiFleetMemberResponse[]> getFleetMembers(Long fleetId, String token) {
        return fetch("/fleets/{id}/members/", new Object[]{fleetId}, token, null, EsiFleetMemberResponse[].class);
    }

    public EsiResponse<EsiOnlineResponse> getCharacterOnlineStatus(Long characterId, String token) {
        return fetch("/characters/{id}/online/", new Object[]{characterId}, token, null, EsiOnlineResponse.class);
    }

    public EsiResponse<Long[]> getCorporationMembers(Long corpId, String token) {
        return fetch("/corporations/{id}/members/", new Object[]{corpId}, token, null, Long[].class);
    }

    /**
     * Loest den Namen einer Upwell-Struktur auf.
     * Benoetigt den Scope esi-universe.read_structures.v1 UND Docking-Access
     * des Token-Charakters. Ohne Access antwortet ESI mit 403.
     */
    public EsiStructureResponse getStructureInfo(Long structureId, String token) {
        return fetch("/universe/structures/{id}/", new Object[]{structureId}, token, null, EsiStructureResponse.class).data();
    }

    public java.util.Map<String, FuzzworkPrice> getFuzzworkPrices(List<Long> typeIds) {
        if (typeIds == null || typeIds.isEmpty()) return java.util.Map.of();
        String typesParam = typeIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            return RestClient.create().get()
                    .uri("https://market.fuzzwork.co.uk/aggregates/?station=60003760&types=" + typesParam)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<java.util.Map<String, FuzzworkPrice>>() {});
        } catch (Exception e) {
            System.err.println("Fehler bei Fuzzwork Jita Preisen: " + e.getMessage());
            return java.util.Map.of();
        }
    }

    // --- Records ---
    public record FuzzworkPrice(FuzzworkBuy buy, FuzzworkSell sell) {}
    public record FuzzworkBuy(Double max) {}
    public record FuzzworkSell(Double min) {}
    public record EsiIdName(Long id, String name, String category) {}
    public record EsiOnlineResponse(Boolean online, String last_login, String last_logout, Integer logins) {}
    public record EsiCharacterFleetResponse(Long fleet_id, Long character_id, String role) {}
    public record EsiFleetMemberResponse(Long character_id, java.time.Instant join_time, String role, Long ship_type_id, Long solar_system_id) {}
    public record EsiCorpTitleResponse(Long title_id, String name) {}
    public record EsiTitleResponse(Long title_id, String name) {}
    public record EsiMiningResponse(String date, Long quantity, Long solar_system_id, Long type_id) {}
    public record EsiJournalResponse(Long id, String date, String ref_type, Double amount, Long second_party_id, String reason) {}
    public record EsiLpResponse(Long corporation_id, Integer loyalty_points) {}
    public record SkillResponse(Long total_sp, Integer unallocated_sp) {}
    public record EsiAssetResponse(Long item_id, Long type_id, Long location_id, Integer quantity,
                                   Boolean is_singleton, String location_flag, String location_type) {}
    public record EsiStructureResponse(String name, Long owner_id, Long solar_system_id, Long type_id) {}
    public record EsiCharacterResponse(String name, Long corporation_id) {}
    public record EsiCorporationResponse(String name, String ticker, Long alliance_id, Long faction_id) {}
    public record EsiAllianceResponse(String name, String ticker) {}
}