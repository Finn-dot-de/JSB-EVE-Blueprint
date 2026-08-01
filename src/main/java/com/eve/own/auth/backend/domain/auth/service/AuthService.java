package com.eve.own.auth.backend.domain.auth.service;

import com.eve.own.auth.backend.domain.auth.entity.SystemRole;
import com.eve.own.auth.backend.domain.auth.entity.TitleRoleMapping;
import com.eve.own.auth.backend.domain.auth.repository.SystemRoleRepository;
import com.eve.own.auth.backend.domain.auth.repository.TitleRoleMappingRepository;
import com.eve.own.auth.backend.domain.auth.security.AesEncryptionService;
import com.eve.own.auth.backend.domain.character.entity.Alliance;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.Corporation;
import com.eve.own.auth.backend.domain.character.repository.AllianceRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.repository.CorporationRepository;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private final RestClient restClient;
    private final EsiService esiService;
    private final CharacterRepository characterRepo;
    private final CorporationRepository corpRepo;
    private final AllianceRepository allianceRepo;
    private final ObjectMapper objectMapper;
    private final AesEncryptionService encryptionService;
    private final TitleRoleMappingRepository titleRepo;
    private final SystemRoleRepository systemRoleRepo;

    @Value("${eve.sso.client-id}")
    private String clientId;

    @Value("${eve.sso.client-secret}")
    private String clientSecret;

    @Value("${eve.sso.allowed-corp-id}")
    private Long allowedCorpId;

    @Value("${eve.alt-corp-ids:}")
    private String altCorpIdsStr;

    public AuthService(RestClient.Builder builder, EsiService esiService,
                       CharacterRepository characterRepo, CorporationRepository corpRepo,
                       AllianceRepository allianceRepo, ObjectMapper objectMapper,
                       AesEncryptionService encryptionService,
                       TitleRoleMappingRepository titleRepo,
                       SystemRoleRepository systemRoleRepo) {
        this.restClient = builder.baseUrl("https://login.eveonline.com/v2/oauth/token").build();
        this.esiService = esiService;
        this.characterRepo = characterRepo;
        this.corpRepo = corpRepo;
        this.allianceRepo = allianceRepo;
        this.objectMapper = objectMapper;
        this.encryptionService = encryptionService;
        this.titleRepo = titleRepo;
        this.systemRoleRepo = systemRoleRepo;
    }

    // =================================================================================
    // HAUPT-ABLAUF FÜR DEN LOGIN
    // =================================================================================
    public Character processEveLogin(String code, Long loggedInMainId) throws Exception {
        TokenResponse tokenResponse = exchangeCodeForToken(code);
        EveJwtPayload payload = decodeEveJwt(tokenResponse.access_token());
        Corporation corp = syncCorporationAndAlliance(payload.characterId());
        Character character = saveOrUpdateCharacter(payload, corp, tokenResponse, loggedInMainId);
        return syncCharacterRoles(character, tokenResponse.access_token());
    }

    // =================================================================================
    // HELFER-METHODEN FÜR SAUBERE STRUKTUR
    // =================================================================================

    private TokenResponse exchangeCodeForToken(String code) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);

        return restClient.post()
                .header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(TokenResponse.class);
    }

    private EveJwtPayload decodeEveJwt(String accessToken) throws Exception {
        String[] jwtParts = accessToken.split("\\.");
        String payloadJson = new String(Base64.getUrlDecoder().decode(jwtParts[1]), StandardCharsets.UTF_8);
        JsonNode payloadNode = objectMapper.readTree(payloadJson);

        Long characterId = Long.parseLong(payloadNode.get("sub").asString().split(":")[2]);
        String characterName = payloadNode.get("name").asString();

        return new EveJwtPayload(characterId, characterName);
    }

    private java.util.List<Long> getAllowedCorps() {
        java.util.List<Long> allowedCorps = new java.util.ArrayList<>();
        allowedCorps.add(allowedCorpId);
        if (altCorpIdsStr != null && !altCorpIdsStr.isBlank()) {
            Arrays.stream(altCorpIdsStr.split(","))
                    .map(String::trim)
                    .map(Long::valueOf)
                    .forEach(allowedCorps::add);
        }
        return allowedCorps;
    }

    private Corporation syncCorporationAndAlliance(Long characterId) {
        var esiChar = esiService.getCharacter(characterId, null).data();
        var esiCorp = esiService.getCorporation(esiChar.corporation_id(), null).data();

        Alliance alliance = null;
        if (esiCorp.alliance_id() != null) {
            var esiAlliance = esiService.getAlliance(esiCorp.alliance_id(), null).data();
            alliance = new Alliance();
            alliance.setId(esiCorp.alliance_id());
            alliance.setName(esiAlliance.name());
            alliance.setTicker(esiAlliance.ticker());
            allianceRepo.save(alliance);
        }

        Corporation corp = new Corporation();
        corp.setId(esiChar.corporation_id());
        corp.setName(esiCorp.name());
        corp.setTicker(esiCorp.ticker());
        corp.setAlliance(alliance);
        return corpRepo.save(corp);
    }

    private Character saveOrUpdateCharacter(EveJwtPayload payload, Corporation corp, TokenResponse tokenResponse, Long loggedInMainId) {
        Character character = characterRepo.findById(payload.characterId()).orElse(new Character());
        character.setId(payload.characterId());
        character.setName(payload.characterName());
        character.setCorporation(corp);

        character.setAccessToken(encryptionService.encrypt(tokenResponse.access_token()));
        character.setRefreshToken(encryptionService.encrypt(tokenResponse.refresh_token()));
        character.setTokenExpiry(Instant.now().plusSeconds(tokenResponse.expires_in()));

        if (loggedInMainId != null) {
            character.setMainCharacterId(loggedInMainId);
        } else if (character.getMainCharacterId() == null) {
            character.setMainCharacterId(payload.characterId());
        }

        return characterRepo.save(character);
    }

    private Character syncCharacterRoles(Character character, String accessToken) {
        java.util.Set<String> calculatedRoles = new java.util.HashSet<>();

        // Prüfen, ob der Charakter in einer der erlaubten Corps (Main oder Alt) ist
        if (getAllowedCorps().contains(character.getCorporation().getId())) {
            calculatedRoles.add("ROLE_USER");
            calculatedRoles.add("ROLE_MEMBER");
            // Marauders Rolle gibt es nur für die Main-Corp
            if (character.getCorporation().getId().equals(allowedCorpId)) {
                calculatedRoles.add("ROLE_MARAUDERS");
            }
        } else {
            // Wenn der Charakter (noch) in gar keiner unserer Corps ist -> Gast
            calculatedRoles.add("ROLE_GUEST");
        }

        // --- SPEZIAL-ROLLEN RETTEN ---
        List<String> specialRolesInDb = systemRoleRepo.findByIsSpecialTrue().stream()
                .map(SystemRole::getRoleName)
                .toList();

        java.util.Set<String> retainedSpecialRoles = character.getRoles().stream()
                .filter(specialRolesInDb::contains)
                .collect(Collectors.toSet());

        // --- TITEL AUTO-DISCOVERY ÜBER ESI ---
        try {
            var titlesResp = esiService.getCharacterTitles(character.getId(), accessToken, null);
            if (titlesResp.data() != null && titlesResp.data().length > 0) {
                List<TitleRoleMapping> existingMappings = titleRepo.findByCorporationId(character.getCorporation().getId());

                for (var esiTitle : titlesResp.data()) {
                    String cleanName = esiTitle.name().replaceAll("<[^>]*>", "");
                    var existingOpt = existingMappings.stream()
                            .filter(m -> m.getTitleId().equals(esiTitle.title_id()))
                            .findFirst();

                    if (existingOpt.isEmpty()) {
                        String autoRole = "ROLE_" + cleanName.toUpperCase().replaceAll("[^A-Z0-9]+", "_");
                        TitleRoleMapping newMapping = new TitleRoleMapping();
                        newMapping.setCorporationId(character.getCorporation().getId());
                        newMapping.setTitleId(esiTitle.title_id());
                        newMapping.setTitleName(cleanName);
                        newMapping.setRoleName(autoRole);

                        titleRepo.save(newMapping);
                        existingMappings.add(newMapping);
                        calculatedRoles.add(autoRole);
                    } else {
                        TitleRoleMapping existing = existingOpt.get();
                        if (!cleanName.equals(existing.getTitleName())) {
                            existing.setTitleName(cleanName);
                            titleRepo.save(existing);
                        }
                        if (existing.getRoleName() != null && !existing.getRoleName().isBlank()) {
                            calculatedRoles.add(existing.getRoleName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Konnte Titel beim Login für " + character.getName() + " nicht laden: " + e.getMessage());
        }

        calculatedRoles.addAll(retainedSpecialRoles);
        character.setRoles(calculatedRoles);

        return characterRepo.save(character);
    }

    record TokenResponse(String access_token, String refresh_token, Integer expires_in) {}
    record EveJwtPayload(Long characterId, String characterName) {}

    @Transactional
    public String getValidAccessToken(Character character) {
        if (character.getTokenExpiry().isAfter(Instant.now().plusSeconds(60))) {
            return encryptionService.decrypt(character.getAccessToken());
        }

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

        character.setAccessToken(encryptionService.encrypt(tokenResponse.access_token()));
        if (tokenResponse.refresh_token() != null) {
            character.setRefreshToken(encryptionService.encrypt(tokenResponse.refresh_token()));
        }
        character.setTokenExpiry(Instant.now().plusSeconds(tokenResponse.expires_in()));
        characterRepo.save(character);

        return tokenResponse.access_token();
    }
}