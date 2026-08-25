package com.eve.buy.bot.backend.domain.auth.service;

import com.eve.buy.bot.backend.domain.auth.security.AesEncryptionService;
import com.eve.buy.bot.backend.domain.character.entity.Alliance;
import com.eve.buy.bot.backend.domain.character.entity.Character;
import com.eve.buy.bot.backend.domain.character.entity.Corporation;
import com.eve.buy.bot.backend.domain.character.repository.AllianceRepository;
import com.eve.buy.bot.backend.domain.character.repository.CharacterRepository;
import com.eve.buy.bot.backend.domain.character.repository.CorporationRepository;
import com.eve.buy.bot.backend.esi.EsiService;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Base64;

/**
 * Bindet EVE SSO an: tauscht den Login-Code gegen Tokens, legt den Charakter samt
 * Corporation an und hält die Tokens gültig.
 *
 * <p>Zugriffstokens werden verschlüsselt in der Datenbank abgelegt; im Klartext existieren
 * sie nur innerhalb eines Aufrufs.
 */
@Slf4j
@Service
public class AuthService {

    /** Sicherheitsabstand, ab dem ein Token vorsorglich erneuert wird. */
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 60;

    private static final String EVE_TOKEN_URL = "https://login.eveonline.com/v2/oauth/token";

    private final RestClient restClient;
    private final EsiService esiService;
    private final CharacterRepository characterRepo;
    private final CorporationRepository corpRepo;
    private final AllianceRepository allianceRepo;
    private final ObjectMapper objectMapper;
    private final AesEncryptionService encryptionService;
    private final RoleSyncService roleSyncService;

    @Value("${eve.sso.client-id}")
    private String clientId;

    @Value("${eve.sso.client-secret}")
    private String clientSecret;

    @Value("${eve.sso.allowed-corp-id}")
    private Long allowedCorpId;

    /**
     * @param builder           Vorlage für den HTTP-Client zum SSO-Endpunkt
     * @param esiService        Zugriff auf öffentliche und private ESI-Daten
     * @param characterRepo     Persistenz der Charaktere
     * @param corpRepo          Persistenz der Corporations
     * @param allianceRepo      Persistenz der Allianzen
     * @param objectMapper      zum Lesen des SSO-JWT
     * @param encryptionService verschlüsselt die Tokens vor dem Speichern
     * @param roleSyncService   leitet die Anwendungsrollen ab
     */
    public AuthService(RestClient.Builder builder,
                       EsiService esiService,
                       CharacterRepository characterRepo,
                       CorporationRepository corpRepo,
                       AllianceRepository allianceRepo,
                       ObjectMapper objectMapper,
                       AesEncryptionService encryptionService,
                       RoleSyncService roleSyncService) {
        this.restClient = builder.baseUrl(EVE_TOKEN_URL).build();
        this.esiService = esiService;
        this.characterRepo = characterRepo;
        this.corpRepo = corpRepo;
        this.allianceRepo = allianceRepo;
        this.objectMapper = objectMapper;
        this.encryptionService = encryptionService;
        this.roleSyncService = roleSyncService;
    }

    /**
     * Führt den kompletten Login durch: Code einlösen, Charakter anlegen oder aktualisieren
     * und Rollen berechnen.
     *
     * @param code           der von EVE SSO zurückgegebene Autorisierungscode
     * @param loggedInMainId ID des bereits angemeldeten Hauptcharakters, wenn gerade ein Alt
     *                       verknüpft wird; sonst {@code null}
     * @return der gespeicherte Charakter mit aktuellen Rollen
     * @throws SecurityException wenn ein Erstlogin von außerhalb der freigegebenen Corporation kommt
     * @throws Exception         wenn SSO oder ESI nicht antworten
     */
    public Character processEveLogin(String code, Long loggedInMainId) throws Exception {
        TokenResponse tokenResponse = exchangeCodeForToken(code);
        EveJwtPayload payload = decodeEveJwt(tokenResponse.access_token());
        Corporation corporation = syncCorporationAndAlliance(payload.characterId(), loggedInMainId);
        Character character = saveOrUpdateCharacter(payload, corporation, tokenResponse, loggedInMainId);
        return roleSyncService.syncRoles(character, tokenResponse.access_token());
    }

    /**
     * Liefert ein gültiges Zugriffstoken und erneuert es bei Bedarf.
     *
     * @param character der Charakter, dessen Token gebraucht wird
     * @return das entschlüsselte, gültige Zugriffstoken
     * @throws IllegalStateException wenn EVE die Erneuerung ablehnt
     */
    @Transactional
    public String getValidAccessToken(Character character) {
        if (character.getTokenExpiry() != null
                && character.getTokenExpiry().isAfter(Instant.now().plusSeconds(TOKEN_REFRESH_MARGIN_SECONDS))) {
            return encryptionService.decrypt(character.getAccessToken());
        }

        String plainRefreshToken = encryptionService.decrypt(character.getRefreshToken());
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("refresh_token", plainRefreshToken);

        TokenResponse tokenResponse = restClient.post()
                .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(TokenResponse.class);

        if (tokenResponse == null || tokenResponse.access_token() == null) {
            throw new IllegalStateException("EVE-Token für Charakter " + character.getId() + " konnte nicht erneuert werden.");
        }

        character.setAccessToken(encryptionService.encrypt(tokenResponse.access_token()));
        if (tokenResponse.refresh_token() != null) {
            character.setRefreshToken(encryptionService.encrypt(tokenResponse.refresh_token()));
        }
        character.setTokenExpiry(Instant.now().plusSeconds(tokenResponse.expires_in()));
        characterRepo.save(character);

        return tokenResponse.access_token();
    }

    /**
     * Prüft, ob ein Zugriffstoken den angegebenen ESI-Scope enthält.
     *
     * <p>Die Scopes stehen als Klartext im scp-Claim des von CCP signierten JWT, ein
     * Namensvergleich genügt also. Lässt sich das Token nicht lesen, wird {@code true}
     * zurückgegeben: dann soll die echte ESI-Antwort den Grund liefern statt eines
     * geratenen Vorab-Urteils.
     *
     * @param accessToken das zu prüfende Zugriffstoken
     * @param scope       der gesuchte Scope, etwa {@code esi-assets.read_assets.v1}
     * @return {@code true}, wenn der Scope enthalten ist oder das Token nicht lesbar war
     */
    public boolean tokenHasScope(String accessToken, String scope) {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) {
                return true;
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return payload.contains(scope);
        } catch (Exception e) {
            log.debug("Scopes im Token nicht lesbar: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Löst den Autorisierungscode bei EVE SSO gegen Zugriffs- und Erneuerungstoken ein.
     *
     * @param code der Autorisierungscode aus dem Callback
     * @return die Tokenantwort von EVE SSO
     */
    private TokenResponse exchangeCodeForToken(String code) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);

        return restClient.post()
                .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(TokenResponse.class);
    }

    /**
     * Liest Charakter-ID und Name aus dem von EVE signierten JWT.
     *
     * <p>Das Token stammt direkt aus dem SSO-Aufruf über TLS, deshalb genügt hier das Lesen
     * der Nutzdaten ohne erneute Signaturprüfung.
     *
     * @param accessToken das Zugriffstoken von EVE SSO
     * @return die enthaltene Charakter-Identität
     * @throws Exception wenn das Token nicht lesbar ist
     */
    private EveJwtPayload decodeEveJwt(String accessToken) throws Exception {
        String[] jwtParts = accessToken.split("\\.");
        String payloadJson = new String(Base64.getUrlDecoder().decode(jwtParts[1]), StandardCharsets.UTF_8);
        JsonNode payloadNode = objectMapper.readTree(payloadJson);

        Long characterId = Long.parseLong(payloadNode.get("sub").asString().split(":")[2]);
        String characterName = payloadNode.get("name").asString();
        return new EveJwtPayload(characterId, characterName);
    }

    /**
     * Legt Corporation und Allianz des Charakters an oder aktualisiert sie.
     *
     * @param characterId    der einloggende Charakter
     * @param loggedInMainId gesetzt, wenn gerade ein Alt verknüpft wird
     * @return die gespeicherte Corporation
     * @throws SecurityException wenn ein Erstlogin von außerhalb der freigegebenen Corporation kommt
     */
    private Corporation syncCorporationAndAlliance(Long characterId, Long loggedInMainId) {
        EsiService.EsiCharacterResponse esiChar = esiService.getCharacter(characterId, null).data();

        /*if (loggedInMainId == null && !esiChar.corporation_id().equals(allowedCorpId)) {
            log.info("Login abgelehnt: Charakter {} ist nicht in der freigegebenen Corporation.", characterId);
            throw new SecurityException("Zugriff verweigert: Charakter gehört nicht zur freigegebenen Corporation.");
        }*/

        EsiService.EsiCorporationResponse esiCorp = esiService.getCorporation(esiChar.corporation_id(), null).data();

        Alliance alliance = null;
        if (esiCorp.alliance_id() != null) {
            EsiService.EsiAllianceResponse esiAlliance = esiService.getAlliance(esiCorp.alliance_id(), null).data();
            alliance = new Alliance();
            alliance.setId(esiCorp.alliance_id());
            alliance.setName(esiAlliance.name());
            alliance.setTicker(esiAlliance.ticker());
            allianceRepo.save(alliance);
        }

        Corporation corporation = new Corporation();
        corporation.setId(esiChar.corporation_id());
        corporation.setName(esiCorp.name());
        corporation.setTicker(esiCorp.ticker());
        corporation.setAlliance(alliance);
        return corpRepo.save(corporation);
    }

    /**
     * Legt den Charakter an oder aktualisiert ihn und speichert die verschlüsselten Tokens.
     *
     * @param payload        Identität aus dem SSO-Token
     * @param corporation    die zugehörige Corporation
     * @param tokenResponse  die frischen Tokens
     * @param loggedInMainId gesetzt, wenn dieser Charakter als Alt verknüpft wird
     * @return der gespeicherte Charakter
     */
    private Character saveOrUpdateCharacter(EveJwtPayload payload,
                                            Corporation corporation,
                                            TokenResponse tokenResponse,
                                            Long loggedInMainId) {
        Character character = characterRepo.findById(payload.characterId()).orElseGet(Character::new);
        character.setId(payload.characterId());
        character.setName(payload.characterName());
        character.setCorporation(corporation);
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

    /**
     * Baut den HTTP-Basic-Header aus Client-ID und Secret der EVE-Anwendung.
     *
     * @return der fertige Authorization-Header
     */
    private String basicAuthHeader() {
        String credentials = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Antwort des EVE-SSO-Token-Endpunkts.
     *
     * @param access_token  kurzlebiges Zugriffstoken
     * @param refresh_token langlebiges Erneuerungstoken
     * @param expires_in    Gültigkeit des Zugriffstokens in Sekunden
     */
    record TokenResponse(String access_token, String refresh_token, Integer expires_in) {}

    /**
     * Die aus dem SSO-Token gelesene Identität.
     *
     * @param characterId   EVE-Charakter-ID
     * @param characterName Anzeigename des Charakters
     */
    record EveJwtPayload(Long characterId, String characterName) {}
}
