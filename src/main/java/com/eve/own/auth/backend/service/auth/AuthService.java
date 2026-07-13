package com.eve.own.auth.backend.service.auth;

import com.eve.own.auth.backend.database.entity.Alliance;
import com.eve.own.auth.backend.database.entity.Character;
import com.eve.own.auth.backend.database.entity.Corporation;
import com.eve.own.auth.backend.database.repository.AllianceRepository;
import com.eve.own.auth.backend.database.repository.CharacterRepository;
import com.eve.own.auth.backend.database.repository.CorporationRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuthService {

    private final RestClient restClient;
    private final EsiService esiService;
    private final CharacterRepository characterRepo;
    private final CorporationRepository corpRepo;
    private final AllianceRepository allianceRepo;
    private final ObjectMapper objectMapper;

    @Value("${eve.sso.client-id}")
    private String clientId;

    @Value("${eve.sso.client-secret}")
    private String clientSecret;

    public AuthService(RestClient.Builder builder, EsiService esiService,
                       CharacterRepository characterRepo, CorporationRepository corpRepo,
                       AllianceRepository allianceRepo, ObjectMapper objectMapper) {
        this.restClient = builder.baseUrl("https://login.eveonline.com/v2/oauth/token").build();
        this.esiService = esiService;
        this.characterRepo = characterRepo;
        this.corpRepo = corpRepo;
        this.allianceRepo = allianceRepo;
        this.objectMapper = objectMapper;
    }

    public Character processEveLogin(String code) throws Exception {
        // 1. Token bei CCP abholen
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);

        TokenResponse tokenResponse = restClient.post()
                .header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(TokenResponse.class);

        // 2. JWT Payload entschlüsseln, um Charakter-ID zu bekommen
        assert tokenResponse != null;
        String[] jwtParts = tokenResponse.access_token().split("\\.");
        String payloadJson = new String(Base64.getUrlDecoder().decode(jwtParts[1]), StandardCharsets.UTF_8);
        JsonNode payload = objectMapper.readTree(payloadJson);

        // EVE packt die ID ins "sub" Feld im Format "CHARACTER:EVE:12345678"
        Long characterId = Long.parseLong(payload.get("sub").asString().split(":")[2]);
        String characterName = payload.get("name").asString();

        // 3. ESI nach Corporation und Allianz fragen
        var esiChar = esiService.getCharacter(characterId);
        var esiCorp = esiService.getCorporation(esiChar.corporation_id());

        Alliance alliance = null;
        if (esiCorp.alliance_id() != null) {
            var esiAlliance = esiService.getAlliance(esiCorp.alliance_id());
            alliance = new Alliance();
            alliance.setId(esiCorp.alliance_id());
            alliance.setName(esiAlliance.name());
            alliance.setTicker(esiAlliance.ticker());
            allianceRepo.save(alliance); // Erst Allianz speichern
        }

        // 4. Corporation speichern
        Corporation corp = new Corporation();
        corp.setId(esiChar.corporation_id());
        corp.setName(esiCorp.name());
        corp.setTicker(esiCorp.ticker());
        corp.setAlliance(alliance);
        corpRepo.save(corp);

        // 5. Charakter mitsamt Token speichern
        Character character = new Character();
        character.setId(characterId);
        character.setName(characterName);
        character.setCorporation(corp);
        character.setAccessToken(tokenResponse.access_token());
        character.setRefreshToken(tokenResponse.refresh_token());
        character.setTokenExpiry(Instant.now().plusSeconds(tokenResponse.expires_in()));

        return characterRepo.save(character);
    }

    record TokenResponse(String access_token, String refresh_token, Integer expires_in) {}
}