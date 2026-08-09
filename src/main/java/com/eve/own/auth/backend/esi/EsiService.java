package com.eve.own.auth.backend.esi;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

@Service
public class EsiService {

    private final RestClient restClient;

    public record EsiResponse<T>(T data, String etag) {}

    public EsiService(RestClient esiClient) {
        this.restClient = esiClient;
    }

    // --- Die generische "Magic" Methode für alle ESI-Calls ---
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

    // --- Aktualisierte Methoden ---
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

    // --- NEU: Paginierte Asset-Abfrage ---
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

    // NEU: Wallet Journal (für Kopfgeld/Ratting)
    public EsiResponse<EsiJournalResponse[]> getWalletJournal(Long characterId, String token, String etag) {
        return fetch("/characters/{id}/wallet/journal/", new Object[]{characterId}, token, etag, EsiJournalResponse[].class);
    }

    public EsiResponse<EsiSearchResponse> searchStructureOrStation(Long characterId, String token, String searchString) {
        return fetch("/characters/{id}/search/?categories=structure,station&search={search}&strict=false",
                new Object[]{characterId, searchString}, token, null, EsiSearchResponse.class);
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

    // --- Records anpassen/erweitern ---
    public record EsiSearchResponse(List<Long> structure, List<Long> station) {}
    public record EsiOnlineResponse(Boolean online, String last_login, String last_logout, Integer logins) {}
    public record EsiCharacterFleetResponse(Long fleet_id, Long character_id, String role) {}
    public record EsiFleetMemberResponse(Long character_id, java.time.Instant join_time, String role, Long ship_type_id, Long solar_system_id) {}
    public record EsiCorpTitleResponse(Long title_id, String name) {}
    public record EsiTitleResponse(Long title_id, String name) {}
    public record EsiMiningResponse(String date, Long quantity, Long solar_system_id, Long type_id) {}
    public record EsiJournalResponse(Long id, String date, String ref_type, Double amount) {}
    public record EsiLpResponse(Long corporation_id, Integer loyalty_points) {}
    public record SkillResponse(Long total_sp, Integer unallocated_sp) {}
    public record EsiAssetResponse(Long item_id, Long type_id, Long location_id, Integer quantity, Boolean is_singleton) {}
    public record EsiCharacterResponse(String name, Long corporation_id) {}
    public record EsiCorporationResponse(String name, String ticker, Long alliance_id, Long faction_id) {}
    public record EsiAllianceResponse(String name, String ticker) {}
}