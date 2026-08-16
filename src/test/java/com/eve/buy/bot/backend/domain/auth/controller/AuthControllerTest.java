package com.eve.buy.bot.backend.domain.auth.controller;

import com.eve.buy.bot.backend.domain.auth.service.AuthService;
import com.eve.buy.bot.backend.domain.auth.service.JwtService;
import com.eve.buy.bot.backend.domain.character.repository.CharacterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests der Login-Endpunkte.
 *
 * <p>Das Sitzungscookie ist der einzige Schutz des Admin-Bereichs. Im Betrieb muss es als
 * {@code Secure} gesetzt werden, beim lokalen Testlauf ueber {@code http://localhost} darf
 * es das gerade nicht - sonst verwirft der Browser es und die Anmeldung schlaegt fehl.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController")
class AuthControllerTest {

    @Mock private AuthService authService;
    @Mock private JwtService jwtService;
    @Mock private CharacterRepository characterRepo;

    /**
     * Baut den Controller mit der angegebenen Basis-Adresse.
     *
     * @param baseUrl die konfigurierte Basis-Adresse der Anwendung
     * @return der fertige Controller
     */
    private AuthController controllerFor(String baseUrl) {
        return new AuthController(authService, jwtService, characterRepo,
                "client-id", baseUrl, "publicData", baseUrl);
    }

    @Test
    @DisplayName("setzt das Cookie im Betrieb hinter HTTPS als Secure")
    void marksCookieSecureBehindHttps() {
        AuthController controller = controllerFor("https://buybot.net");

        assertThat(ReflectionTestUtils.getField(controller, "cookieSecure")).isEqualTo(true);
    }

    @Test
    @DisplayName("setzt das Cookie beim lokalen Testlauf nicht als Secure")
    void leavesCookieOpenForLocalHttp() {
        AuthController controller = controllerFor("http://localhost:8080");

        assertThat(ReflectionTestUtils.getField(controller, "cookieSecure")).isEqualTo(false);
    }

    @Test
    @DisplayName("baut die Callback-Adresse unabhaengig vom Schrägstrich am Ende")
    void buildsCallbackUrlWithAndWithoutTrailingSlash() {
        assertThat(ReflectionTestUtils.getField(controllerFor("https://buybot.net"), "callbackUrl"))
                .isEqualTo("https://buybot.net/api/auth/callback");
        assertThat(ReflectionTestUtils.getField(controllerFor("https://buybot.net/"), "callbackUrl"))
                .isEqualTo("https://buybot.net/api/auth/callback");
    }

    @Test
    @DisplayName("schickt den Browser mit allen Angaben zu EVE SSO")
    void redirectsToEveWithAllParameters() {
        AuthController controller = controllerFor("https://buybot.net");

        var response = controller.redirectToEveLogin();

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        String ziel = String.valueOf(response.getHeaders().getLocation());
        assertThat(ziel)
                .startsWith("https://login.eveonline.com/v2/oauth/authorize")
                .contains("client_id=client-id")
                .contains("redirect_uri=https://buybot.net/api/auth/callback")
                .contains("response_type=code");
    }
}
