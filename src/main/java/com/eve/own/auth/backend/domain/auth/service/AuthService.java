    package com.eve.own.auth.backend.domain.auth.service;

    import com.eve.own.auth.backend.domain.auth.security.AesEncryptionService;
    import com.eve.own.auth.backend.domain.character.entity.Alliance;
    import com.eve.own.auth.backend.domain.character.entity.Character;
    import com.eve.own.auth.backend.domain.character.entity.Corporation;
    import com.eve.own.auth.backend.domain.character.repository.AllianceRepository;
    import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
    import com.eve.own.auth.backend.domain.character.repository.CorporationRepository;
    import java.nio.charset.StandardCharsets;
    import java.time.Instant;
    import java.util.Base64;

    import com.eve.own.auth.backend.esi.EsiService;
    import jakarta.transaction.Transactional;
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

        private final AesEncryptionService encryptionService;

        @Value("${eve.sso.client-id}")
        private String clientId;

        @Value("${eve.sso.client-secret}")
        private String clientSecret;

        @Value("${eve.sso.allowed-corp-id}")
        private Long allowedCorpId;

        public AuthService(RestClient.Builder builder, EsiService esiService,
                           CharacterRepository characterRepo, CorporationRepository corpRepo,
                           AllianceRepository allianceRepo, ObjectMapper objectMapper,
                           AesEncryptionService encryptionService) {
            this.restClient = builder.baseUrl("https://login.eveonline.com/v2/oauth/token").build();
            this.esiService = esiService;
            this.characterRepo = characterRepo;
            this.corpRepo = corpRepo;
            this.allianceRepo = allianceRepo;
            this.objectMapper = objectMapper;
            this.encryptionService = encryptionService;
        }

        public Character processEveLogin(String code, Long loggedInMainId) throws Exception {
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
            var esiCharResp = esiService.getCharacter(characterId, null); // 2. Parameter ETag ist null
            var esiChar = esiCharResp.data(); // WICHTIG: .data() extrahieren

            if (loggedInMainId == null && !esiChar.corporation_id().equals(allowedCorpId)) {
                throw new SecurityException("Zugriff verweigert...");
            }

            var esiCorpResp = esiService.getCorporation(esiChar.corporation_id(), null);
            var esiCorp = esiCorpResp.data();

            Alliance alliance = null;
            if (esiCorp.alliance_id() != null) {
                var esiAllianceResp = esiService.getAlliance(esiCorp.alliance_id(), null);
                var esiAlliance = esiAllianceResp.data();

                alliance = new Alliance();
                alliance.setId(esiCorp.alliance_id());
                alliance.setName(esiAlliance.name());
                alliance.setTicker(esiAlliance.ticker());
                allianceRepo.save(alliance);
            }

            // 4. Corporation speichern
            Corporation corp = new Corporation();
            corp.setId(esiChar.corporation_id());
            corp.setName(esiCorp.name());
            corp.setTicker(esiCorp.ticker());
            corp.setAlliance(alliance);
            corpRepo.save(corp);

            // 5. Charakter mitsamt Token speichern
            Character character = characterRepo.findById(characterId).orElse(new Character());

            character.setId(characterId);
            character.setName(characterName);
            character.setCorporation(corp);

            // Verschlüsselung anwenden (wie wir es vorhin gebaut haben)
            character.setAccessToken(encryptionService.encrypt(tokenResponse.access_token()));
            character.setRefreshToken(encryptionService.encrypt(tokenResponse.refresh_token()));
            character.setTokenExpiry(Instant.now().plusSeconds(tokenResponse.expires_in()));

            if (loggedInMainId != null) {
                character.setMainCharacterId(loggedInMainId);
            } else {

                if (character.getMainCharacterId() == null) {
                    character.setMainCharacterId(characterId);
                }
            }

            return characterRepo.save(character);
        }

        record TokenResponse(String access_token, String refresh_token, Integer expires_in) {}

        @Transactional
        public String getValidAccessToken(Character character) {
            // 1. Prüfen: Ist das Token noch mindestens 60 Sekunden lang gültig?
            if (character.getTokenExpiry().isAfter(Instant.now().plusSeconds(60))) {
                return encryptionService.decrypt(character.getAccessToken());
            }

            // 2. Token ist abgelaufen: Neues Token bei CCP anfordern
            String plainRefreshToken = encryptionService.decrypt(character.getRefreshToken());

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "refresh_token");
            body.add("refresh_token", plainRefreshToken);

            TokenResponse tokenResponse = restClient.post()
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(TokenResponse.class);

            if (tokenResponse == null || tokenResponse.access_token() == null) {
                throw new RuntimeException("Konnte EVE Token für Charakter " + character.getId() + " nicht erneuern!");
            }

            // 3. Datenbank mit den NEUEN, verschlüsselten Tokens aktualisieren
            character.setAccessToken(encryptionService.encrypt(tokenResponse.access_token()));

            if (tokenResponse.refresh_token() != null) {
                character.setRefreshToken(encryptionService.encrypt(tokenResponse.refresh_token()));
            }

            character.setTokenExpiry(Instant.now().plusSeconds(tokenResponse.expires_in()));
            characterRepo.save(character);

            // Wir geben das Access-Token im Klartext zurück, damit der EsiService sofort damit arbeiten kann
            return tokenResponse.access_token();
        }
    }