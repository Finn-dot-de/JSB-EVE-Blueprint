package com.eve.own.auth.backend.esi;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.http.ResponseEntity;

@Service
public class EsiService {

    private final RestClient restClient;

    public record EsiResponse<T>(T data, String etag) {}

    public EsiService(RestClient esiClient) {
        this.restClient = esiClient;
    }

    // --- NEU: fetch akzeptiert jetzt 'Object... uriVariables' ---
    private <T> EsiResponse<T> fetch(String uri, Object[] uriVariables, String token, String oldEtag, Class<T> responseType) {
        // Hier werden die Variablen (z.B. characterId) in den URI-String eingesetzt
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

    public EsiResponse<EsiAssetResponse[]> getAssets(Long characterId, String token, String etag) {
        return fetch("/characters/{id}/assets/", new Object[]{characterId}, token, etag, EsiAssetResponse[].class);
    }

    // --- Records ---
    public record EsiAssetResponse(Long item_id, Integer type_id, Long location_id, Integer quantity) {}
    public record EsiCharacterResponse(String name, Long corporation_id) {}
    public record EsiCorporationResponse(String name, String ticker, Long alliance_id) {}
    public record EsiAllianceResponse(String name, String ticker) {} // Hinzugefügt!
    public record SkillResponse(Long total_sp) {}
}