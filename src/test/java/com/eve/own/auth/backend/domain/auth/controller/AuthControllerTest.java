package com.eve.own.auth.backend.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.security.SessionCookie;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.auth.service.JwtService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Anmelde-Endpunkte")
class AuthControllerTest {

    private static final Long CHARACTER_ID = 95465499L;
    private static final String FRONTEND_URL = "https://auth.example.org";
    private static final String BASE_URL = "https://api.example.org/";

    @Mock private AuthService authService;
    @Mock private JwtService jwtService;
    @Mock private CharacterRepository characterRepo;

    private AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(authService, jwtService, characterRepo,
                "client-id", BASE_URL, "esi-assets.read_assets.v1", FRONTEND_URL);

        when(jwtService.generateToken(anyLong(), anyString(), any())).thenReturn("jwt-token");
        when(characterRepo.findById(anyLong())).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static Character character(Long id, Long mainId) {
        Character character = new Character();
        character.setId(id);
        character.setName("Pilot Eins");
        character.setMainCharacterId(mainId);
        character.setRoles(Set.of("ROLE_USER"));
        return character;
    }

    private static void authenticateAs(Long characterId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(characterId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Nested
    @DisplayName("Weiterleitung zu CCP")
    class LoginRedirect {

        @Test
        @DisplayName("baut die Anmelde-URL mit Rueckleitung und Scopes")
        void buildsLoginUrl() {
            var response = controller.redirectToEveLogin();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
            String location = response.getHeaders().getLocation().toString();
            assertThat(location).startsWith("https://login.eveonline.com/v2/oauth/authorize");
            assertThat(location).contains("client_id=client-id");
            // Die Basis-URL endet auf einen Schraegstrich - er darf sich nicht verdoppeln.
            assertThat(location).contains("redirect_uri=https://api.example.org/api/auth/callback");
            assertThat(location).contains("scope=esi-assets.read_assets.v1");
        }
    }

    @Nested
    @DisplayName("Ruecksprung von CCP")
    class Callback {

        @Test
        @DisplayName("setzt das Sitzungs-Cookie und leitet auf die Startseite")
        void firstLoginGoesHome() {
            when(authService.processEveLogin(anyString(), any()))
                    .thenReturn(character(CHARACTER_ID, CHARACTER_ID));

            var response = controller.eveCallback("code");

            assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                    .contains(SessionCookie.NAME).contains("HttpOnly");
            assertThat(response.getHeaders().getLocation()).hasToString(FRONTEND_URL);
        }

        @Test
        @DisplayName("leitet nach dem Verknuepfen auf die Charakter-Uebersicht")
        void linkingGoesToCharlink() {
            authenticateAs(CHARACTER_ID);
            when(authService.processEveLogin(anyString(), any()))
                    .thenReturn(character(2000L, CHARACTER_ID));

            var response = controller.eveCallback("code");

            assertThat(response.getHeaders().getLocation())
                    .hasToString(FRONTEND_URL + "/charlink");
        }

        @Test
        @DisplayName("stellt das Token mit den Rollen des Mains aus")
        void issuesTokenWithMainRoles() {
            Character main = character(CHARACTER_ID, CHARACTER_ID);
            main.setRoles(Set.of("ROLE_DIRECTOR"));
            when(characterRepo.findById(CHARACTER_ID)).thenReturn(Optional.of(main));
            when(authService.processEveLogin(anyString(), any()))
                    .thenReturn(character(2000L, CHARACTER_ID));

            controller.eveCallback("code");

            org.mockito.Mockito.verify(jwtService)
                    .generateToken(org.mockito.ArgumentMatchers.eq(CHARACTER_ID), anyString(),
                            org.mockito.ArgumentMatchers.eq(Set.of("ROLE_DIRECTOR")));
        }

        @Test
        @DisplayName("leitet bei abgelehntem Login mit einem Hinweis zurueck")
        void redirectsOnRejectedLogin() {
            when(authService.processEveLogin(anyString(), any()))
                    .thenThrow(new SecurityException("fremde Corp"));

            var response = controller.eveCallback("code");

            assertThat(response.getHeaders().getLocation())
                    .hasToString(FRONTEND_URL + "/?error=wrong_corp");
        }

        @Test
        @DisplayName("leitet bei einem unerwarteten Fehler mit einem Hinweis zurueck")
        void redirectsOnUnexpectedError() {
            // Der Nutzer landet hier im Browser - eine JSON-Fehlermeldung saehe er nie.
            when(authService.processEveLogin(anyString(), any()))
                    .thenThrow(new RuntimeException("kaputt"));

            var response = controller.eveCallback("code");

            assertThat(response.getHeaders().getLocation())
                    .hasToString(FRONTEND_URL + "/?error=login_failed");
        }
    }

    @Nested
    @DisplayName("Profil und Abmeldung")
    class ProfileAndLogout {

        @Test
        @DisplayName("liefert das Profil des angemeldeten Nutzers")
        void returnsProfile() {
            authenticateAs(CHARACTER_ID);
            when(characterRepo.findById(CHARACTER_ID))
                    .thenReturn(Optional.of(character(CHARACTER_ID, CHARACTER_ID)));

            var response = controller.getCurrentUser(new MockHttpServletResponse());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().characterName()).isEqualTo("Pilot Eins");
            assertThat(response.getBody().portraitUrl()).contains("/characters/95465499/portrait");
        }

        @Test
        @DisplayName("antwortet ohne Anmeldung mit 401")
        void rejectsAnonymous() {
            var response = controller.getCurrentUser(new MockHttpServletResponse());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("raeumt das Cookie ab, wenn der Charakter nicht mehr existiert")
        void clearsCookieForDeletedCharacter() {
            // Sonst liefe der Browser bis zum Ablauf des Tokens gegen dieselbe Wand.
            authenticateAs(CHARACTER_ID);
            MockHttpServletResponse response = new MockHttpServletResponse();

            controller.getCurrentUser(response);

            Cookie cookie = response.getCookie(SessionCookie.NAME);
            assertThat(cookie).isNotNull();
            assertThat(cookie.getMaxAge()).isZero();
        }

        @Test
        @DisplayName("raeumt das Cookie beim Abmelden ab")
        void clearsCookieOnLogout() {
            MockHttpServletResponse response = new MockHttpServletResponse();

            controller.logout(response);

            assertThat(response.getCookie(SessionCookie.NAME).getMaxAge()).isZero();
        }
    }
}
