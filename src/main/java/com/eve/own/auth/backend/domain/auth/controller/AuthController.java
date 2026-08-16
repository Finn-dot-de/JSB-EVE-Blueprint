package com.eve.own.auth.backend.domain.auth.controller;

import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.common.EveImageUrls;
import com.eve.own.auth.backend.domain.auth.dto.UserProfileDto;
import com.eve.own.auth.backend.domain.auth.security.SessionCookie;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.auth.service.JwtService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anmeldung ueber EVE Single Sign-on.
 *
 * <p>Der uebliche OAuth-Dreisprung: Weiterleitung zu CCP, Ruecksprung mit einem
 * Code, Tausch des Codes gegen Token. Am Ende steht ein eigenes Token im
 * Sitzungs-Cookie - die EVE-Token selbst verlassen den Server nie.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String EVE_AUTHORIZE_URL = "https://login.eveonline.com/v2/oauth/authorize";

    /**
     * Der {@code state}-Parameter des OAuth-Ablaufs.
     *
     * <p>Ein fester Wert schuetzt nicht gegen CSRF - dafuer muesste er je Anfrage
     * neu gezogen und beim Ruecksprung geprueft werden. CCP verlangt den Parameter
     * aber, deshalb steht hier vorerst eine Konstante.</p>
     */
    private static final String OAUTH_STATE = "blueprint_secret_state";

    private static final String CALLBACK_PATH = "api/auth/callback";

    /** Ziel nach dem Verknuepfen eines weiteren Charakters. */
    private static final String CHARLINK_PATH = "charlink";

    private final AuthService authService;
    private final JwtService jwtService;
    private final CharacterRepository characterRepo;
    private final String clientId;
    private final String callbackUrl;
    private final String encodedScopes;
    private final String frontendUrl;

    public AuthController(AuthService authService,
                          JwtService jwtService,
                          CharacterRepository characterRepo,
                          @Value("${eve.sso.client-id}") String clientId,
                          @Value("${app.base.url}") String baseUrl,
                          @Value("${eve.sso.scopes:publicData}") String scopes,
                          @Value("${app.frontend.url}") String frontendUrl) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.characterRepo = characterRepo;
        this.clientId = clientId;
        this.frontendUrl = frontendUrl;
        this.callbackUrl = withoutTrailingSlash(baseUrl) + "/" + CALLBACK_PATH;
        this.encodedScopes = URLEncoder.encode(scopes, StandardCharsets.UTF_8);
    }

    @GetMapping("/login")
    public ResponseEntity<Void> redirectToEveLogin() {
        String eveLoginUrl = EVE_AUTHORIZE_URL
                + "?response_type=code"
                + "&redirect_uri=" + callbackUrl
                + "&client_id=" + clientId
                + "&scope=" + encodedScopes
                + "&state=" + OAUTH_STATE;

        return redirectTo(eveLoginUrl);
    }

    /**
     * Ruecksprung von CCP.
     *
     * <p>Besteht bereits eine Sitzung, wird der Charakter als Alt an den
     * angemeldeten Account gehaengt - daran haengt die Charakter-Verknuepfung.</p>
     *
     * <p>Antwortet immer mit einer Weiterleitung ins Frontend, im Fehlerfall mit
     * einem {@code error}-Parameter: der Nutzer landet hier im Browser und saehe
     * eine JSON-Fehlermeldung nie.</p>
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> eveCallback(@RequestParam("code") String code) {
        Long loggedInMainId = CurrentUser.find().orElse(null);
        try {
            Character character = authService.processEveLogin(code, loggedInMainId);
            ResponseCookie sessionCookie = SessionCookie.create(issueToken(character));

            // Nach dem Verknuepfen zurueck auf die Charakter-Uebersicht, beim
            // ersten Login auf die Startseite.
            String target = loggedInMainId != null
                    ? withoutTrailingSlash(frontendUrl) + "/" + CHARLINK_PATH
                    : frontendUrl;

            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.SET_COOKIE, sessionCookie.toString())
                    .location(URI.create(target))
                    .build();

        } catch (SecurityException e) {
            log.warn("Login abgelehnt: {}", e.getMessage());
            return redirectTo(errorUrl("wrong_corp"));
        } catch (Exception e) {
            log.error("Unerwarteter Fehler beim EVE-SSO-Ruecksprung", e);
            return redirectTo(errorUrl("login_failed"));
        }
    }

    /**
     * Stellt das Sitzungs-Token mit den Rollen des Main-Charakters aus.
     *
     * <p>Rechte haengen am Account, nicht am einzelnen Charakter: wer sich mit
     * einem Alt anmeldet, soll dieselbe Anwendung sehen wie mit seinem Main.</p>
     */
    private String issueToken(Character character) {
        Long accountId = character.getAccountId();
        Character main = characterRepo.findById(accountId).orElse(character);
        return jwtService.generateToken(accountId, "Main-Token", main.getRoles());
    }

    /** Das Profil des angemeldeten Nutzers, sonst 401. */
    @GetMapping("/me")
    public ResponseEntity<UserProfileDto> getCurrentUser(HttpServletResponse response) {
        Optional<Character> character = CurrentUser.find().flatMap(characterRepo::findById);

        if (character.isEmpty()) {
            // Auch der Fall "gueltiges Token, aber der Charakter existiert nicht
            // mehr" landet hier. Dann muss das Cookie weg, sonst laeuft der
            // Browser bis zu dessen Ablauf gegen dieselbe Wand.
            response.addCookie(SessionCookie.expired());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Character user = character.get();
        return ResponseEntity.ok(new UserProfileDto(
                user.getId(), user.getName(), EveImageUrls.portrait(user.getId()), user.getRoles()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        response.addCookie(SessionCookie.expired());
        return ResponseEntity.ok().build();
    }

    private String errorUrl(String reason) {
        return withoutTrailingSlash(frontendUrl) + "/?error=" + reason;
    }

    private static ResponseEntity<Void> redirectTo(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    private static String withoutTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
