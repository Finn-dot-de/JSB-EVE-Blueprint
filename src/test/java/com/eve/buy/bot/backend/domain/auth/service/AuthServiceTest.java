package com.eve.buy.bot.backend.domain.auth.service;

import com.eve.buy.bot.backend.domain.auth.security.AesEncryptionService;
import com.eve.buy.bot.backend.domain.character.repository.CharacterRepository;
import com.eve.buy.bot.backend.domain.character.repository.AllianceRepository;
import com.eve.buy.bot.backend.domain.character.repository.CorporationRepository;
import com.eve.buy.bot.backend.esi.EsiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Tests der Scope-Prüfung.
 *
 * <p>Ob ein Token einen Scope trägt, entscheidet, ob eine Funktion überhaupt versucht wird
 * oder dem Spieler stattdessen erklärt wird, dass er sich neu anmelden muss. Ein falsches
 * Urteil hier führt entweder zu unnötigen ESI-Fehlern oder zu einem Hinweis, der gar nicht
 * stimmt.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService: Scopes im Token")
class AuthServiceTest {

    @Mock private RestClient.Builder restClientBuilder;
    @Mock private RestClient restClient;
    @Mock private EsiService esiService;
    @Mock private CharacterRepository characterRepo;
    @Mock private CorporationRepository corpRepo;
    @Mock private AllianceRepository allianceRepo;
    @Mock private AesEncryptionService encryptionService;
    @Mock private RoleSyncService roleSyncService;

    private AuthService service;

    @BeforeEach
    void setUp() {
        // Ein echter Client wuerde beim Bauen eine Netzwerkverbindung anlegen, die dieser
        // Test nie benutzt.
        lenient().when(restClientBuilder.baseUrl(anyString())).thenReturn(restClientBuilder);
        lenient().when(restClientBuilder.build()).thenReturn(restClient);

        service = new AuthService(restClientBuilder, esiService, characterRepo, corpRepo,
                allianceRepo, new ObjectMapper(), encryptionService, roleSyncService);
    }

    @Test
    @DisplayName("erkennt einen enthaltenen Scope")
    void findsScopeInToken() {
        String token = tokenWithScopes("esi-mail.send_mail.v1", "esi-assets.read_assets.v1");

        assertThat(service.tokenHasScope(token, "esi-assets.read_assets.v1")).isTrue();
    }

    @Test
    @DisplayName("meldet einen fehlenden Scope als fehlend")
    void reportsMissingScope() {
        String token = tokenWithScopes("publicData");

        assertThat(service.tokenHasScope(token, "esi-assets.read_assets.v1")).isFalse();
    }

    @Test
    @DisplayName("verwechselt ähnlich benannte Scopes nicht")
    void doesNotConfuseNeighbouringScopes() {
        String token = tokenWithScopes("esi-assets.read_corporation_assets.v1");

        assertThat(service.tokenHasScope(token, "esi-assets.read_assets.v1")).isFalse();
    }

    @Test
    @DisplayName("lässt unlesbare Token durch, damit ESI den Grund nennt")
    void allowsUnreadableToken() {
        // Lieber die echte ESI-Antwort abwarten als aufgrund eines Lesefehlers absagen
        assertThat(service.tokenHasScope("kein-jwt", "esi-assets.read_assets.v1")).isTrue();
        assertThat(service.tokenHasScope("kaputt.!!!keinBase64!!!.signatur", "esi-assets.read_assets.v1")).isTrue();
    }

    /**
     * Baut ein Token, dessen Nutzdaten die angegebenen Scopes enthalten.
     *
     * @param scopes die zu hinterlegenden Scopes
     * @return ein Token im Aufbau eines EVE-JWT
     */
    private String tokenWithScopes(String... scopes) {
        String payload = "{\"scp\":[\"" + String.join("\",\"", scopes) + "\"],\"sub\":\"CHARACTER:EVE:1\"}";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return "kopf." + encoded + ".signatur";
    }
}
