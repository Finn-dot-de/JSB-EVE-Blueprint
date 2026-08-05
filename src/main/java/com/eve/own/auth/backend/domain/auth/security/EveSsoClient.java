package com.eve.own.auth.backend.domain.auth.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Der Draht zum EVE Single Sign-on.
 *
 * <p>Buendelt alles, was mit dem OAuth-Endpunkt von CCP zu tun hat: Code gegen
 * Token tauschen, Token erneuern, das ausgestellte JWT lesen. Die Fachlogik
 * darueber muss dadurch weder Basic-Auth-Header bauen noch wissen, wie CCP seine
 * Subject-Kennung schreibt.</p>
 */
@Component
public class EveSsoClient {

    private static final String TOKEN_URL = "https://login.eveonline.com/v2/oauth/token";
    private static final String GRANT_AUTHORIZATION_CODE = "authorization_code";
    private static final String GRANT_REFRESH_TOKEN = "refresh_token";

    /** CCP schreibt das Subject als {@code CHARACTER:EVE:<id>}. */
    private static final String SUBJECT_SEPARATOR = ":";
    private static final int SUBJECT_ID_INDEX = 2;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String basicAuthHeader;

    public EveSsoClient(RestClient.Builder builder,
                        ObjectMapper objectMapper,
                        @Value("${eve.sso.client-id}") String clientId,
                        @Value("${eve.sso.client-secret}") String clientSecret) {
        this.restClient = builder.baseUrl(TOKEN_URL).build();
        this.objectMapper = objectMapper;
        this.basicAuthHeader = "Basic " + Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    }

    /** Antwort des Token-Endpunkts; die Feldnamen gibt CCP vor. */
    public record TokenResponse(String access_token, String refresh_token, Integer expires_in) {}

    /** Die Angaben, die im ausgestellten Access-Token stecken. */
    public record EveIdentity(Long characterId, String characterName) {}

    /** Tauscht den Autorisierungscode aus der Weiterleitung gegen ein Token-Paar. */
    public TokenResponse exchangeCode(String code) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", GRANT_AUTHORIZATION_CODE);
        body.add("code", code);
        return requestToken(body);
    }

    /** Erneuert ein abgelaufenes Access-Token. */
    public TokenResponse refresh(String refreshToken) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", GRANT_REFRESH_TOKEN);
        body.add(GRANT_REFRESH_TOKEN, refreshToken);
        return requestToken(body);
    }

    /**
     * Liest Charakter-ID und -Namen aus dem Access-Token.
     *
     * <p>Die Signatur wird bewusst nicht geprueft: das Token kommt gerade eben
     * ueber eine TLS-Verbindung direkt von CCP, ein Angreifer haette es also gar
     * nicht erst unterschieben koennen.</p>
     */
    public EveIdentity readIdentity(String accessToken) {
        String[] parts = accessToken.split("\\.");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Access-Token ist kein JWT.");
        }
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        JsonNode payload = objectMapper.readTree(payloadJson);

        String subject = payload.get("sub").asString();
        Long characterId = Long.parseLong(subject.split(SUBJECT_SEPARATOR)[SUBJECT_ID_INDEX]);
        return new EveIdentity(characterId, payload.get("name").asString());
    }

    private TokenResponse requestToken(MultiValueMap<String, String> body) {
        return restClient.post()
                .header(HttpHeaders.AUTHORIZATION, basicAuthHeader)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(TokenResponse.class);
    }
}
