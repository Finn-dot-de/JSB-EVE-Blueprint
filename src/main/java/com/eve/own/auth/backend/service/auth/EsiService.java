package com.eve.own.auth.backend.service.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class EsiService {

    private final RestClient restClient;

    public EsiService(RestClient.Builder builder,
                      @Value("${eve.esi.base-url}") String baseUrl) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .build();
    }

    // --- 1. Charakter-Daten abfragen (um die Corp-ID zu bekommen) ---
    public EsiCharacterResponse getCharacter(Long characterId) {
        return restClient.get()
                .uri("/characters/{character_id}/", characterId)
                .retrieve()
                .body(EsiCharacterResponse.class);
    }

    // --- 2. Corporation-Daten abfragen ---
    public EsiCorporationResponse getCorporation(Long corporationId) {
        return restClient.get()
                .uri("/corporations/{corporation_id}/", corporationId)
                .retrieve()
                .body(EsiCorporationResponse.class);
    }

    // --- 3. Allianz-Daten abfragen ---
    public EsiAllianceResponse getAlliance(Long allianceId) {
        return restClient.get()
                .uri("/alliances/{alliance_id}/", allianceId)
                .retrieve()
                .body(EsiAllianceResponse.class);
    }


    // --- Hilfs-Klassen (Records) zum Parsen des JSONs ---

    public record EsiCharacterResponse(
            String name,
            Long corporation_id
    ) {}

    public record EsiCorporationResponse(
            String name,
            String ticker,
            Long alliance_id
    ) {}

    public record EsiAllianceResponse(
            String name,
            String ticker
    ) {}
}