package com.eve.buy.bot.backend.domain.auth.controller;

import com.eve.buy.bot.backend.domain.auth.dto.UserProfileDto;
import com.eve.buy.bot.backend.domain.auth.service.AuthService;
import com.eve.buy.bot.backend.domain.auth.service.JwtService;
import com.eve.buy.bot.backend.domain.character.entity.Character;
import com.eve.buy.bot.backend.domain.character.repository.CharacterRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Endpunkte fuer den EVE-SSO-Login.
 *
 * <p>Der Browser wird zu EVE geschickt, kommt mit einem Code zurueck und erhaelt danach ein
 * eigenes Sitzungscookie. Die ESI-Tokens von CCP bleiben auf dem Server.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final String clientId;
    private final String callbackUrl;
    private final String encodedScopes;
    private final String frontendUrl;
    private final CharacterRepository characterRepo;

    /**
     * Ob das Sitzungscookie nur ueber HTTPS uebertragen werden darf.
     *
     * <p>Wird aus der konfigurierten Basis-Adresse abgeleitet: im Betrieb hinter HTTPS ist
     * das Pflicht, beim lokalen Testlauf ueber {@code http://localhost} wuerde der Browser
     * ein solches Cookie dagegen verwerfen und die Anmeldung schluege fehl.
     */
    private final boolean cookieSecure;

    public record UserDto(Long characterId) {}

    public AuthController(AuthService authService,
                          JwtService jwtService,
                          CharacterRepository characterRepo,
                          @Value("${eve.sso.client-id}") String clientId,
                          @Value("${app.base.url}") String baseUrl,
                          @Value("${eve.sso.scopes:publicData}") String scopes,
                          @Value("${app.frontend.url}") String frontendUrl) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.clientId = clientId;
        this.frontendUrl = frontendUrl;
        this.characterRepo = characterRepo;

        this.callbackUrl = baseUrl.endsWith("/")
                ? baseUrl + "api/auth/callback"
                : baseUrl + "/api/auth/callback";

        this.cookieSecure = baseUrl.toLowerCase().startsWith("https://");
        if (!this.cookieSecure) {
            log.warn("Basis-Adresse ist nicht HTTPS ({}). Das Sitzungscookie wird ohne "
                    + "Secure-Kennzeichen gesetzt - fuer einen lokalen Testlauf in Ordnung, "
                    + "im Betrieb nicht.", baseUrl);
        }

        this.encodedScopes = URLEncoder.encode(scopes, StandardCharsets.UTF_8);
    }

    /**
     * Schickt den Browser zur Anmeldeseite von EVE.
     *
     * @param mainCharacterId gesetzt, wenn ein Alt mit einem bestehenden Konto
     *     verknuepft werden soll
     * @param response die Antwort, in der die Weiterleitung gesetzt wird
     * @throws IOException wenn die Weiterleitung nicht geschrieben werden kann
     */
    @GetMapping("/login")
    public ResponseEntity<Void> redirectToEveLogin() {
        String eveLoginUrl = "https://login.eveonline.com/v2/oauth/authorize" +
                "?response_type=code" +
                "&redirect_uri=" + callbackUrl +
                "&client_id=" + clientId +
                "&scope=" + encodedScopes +
                "&state=blueprint_secret_state";

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(eveLoginUrl))
                .build();
    }

    /**
     * Nimmt den Rueckruf von EVE entgegen und stellt das Sitzungscookie aus.
     *
     * @param code der Autorisierungscode von EVE SSO
     * @param state der zurueckgereichte Zustand, enthaelt ggf. den Hauptcharakter
     * @param response die Antwort, in der Cookie und Weiterleitung gesetzt werden
     * @throws IOException wenn die Weiterleitung nicht geschrieben werden kann
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> eveCallback(@RequestParam("code") String code,
                                            @RequestParam("state") String state) {
        try {
            // 1. User identifizieren
            Long loggedInMainId = null;
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                loggedInMainId = (Long) auth.getPrincipal();
            }

            // 2. Charakter verarbeiten
            var character = authService.processEveLogin(code, loggedInMainId);

            // 3. Falls der Charakter selbst der Main ist, nehmen wir seine Rollen, sonst laden wir den Main
            Character mainChar = characterRepo.findById(character.getMainCharacterId()).orElse(character);
            java.util.Set<String> rolesToUse = mainChar.getRoles();

            // 4. Token mit den Rollen des Main-Charakters generieren
            Long jwtTargetId = character.getMainCharacterId();
            // Der Name landet im Protokoll als Auslöser - hier muss der echte stehen
            String token = jwtService.generateToken(jwtTargetId, mainChar.getName(), rolesToUse);

            ResponseCookie jwtCookie = ResponseCookie.from("toky", token)
                    .httpOnly(true)
                    .secure(cookieSecure)
                    .path("/")
                    .maxAge(24 * 60 * 60)
                    .sameSite("Lax")
                    .build();

            // --- NEU: Dynamische Weiterleitung ---
            // Der Buybot hat nur eine Seite - es gibt nichts anderes anzusteuern.
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.SET_COOKIE, jwtCookie.toString())
                    .location(URI.create(frontendUrl))
                    .build();

        } catch (SecurityException e) {
            // 3. Sauber loggen als WARNUNG (Jemand aus einer fremden Corp hat es versucht)
            log.warn("Login abgelehnt: {}", e.getMessage());
            String errorUrl = frontendUrl.endsWith("/")
                    ? frontendUrl + "?error=wrong_corp"
                    : frontendUrl + "/?error=wrong_corp";

            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(errorUrl))
                    .build();
        } catch (Exception e) {
            // 4. Sauber loggen als FEHLER (inklusive Stacktrace für das Debugging)
            log.error("Unerwarteter Fehler beim EVE SSO Callback", e);
            String errorUrl = frontendUrl.endsWith("/")
                    ? frontendUrl + "?error=login_failed"
                    : frontendUrl + "/?error=login_failed";

            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(errorUrl))
                    .build();
        }
    }

    /**
     * Liefert den angemeldeten Charakter.
     *
     * <p>Ohne gueltiges Sitzungscookie ist das kein Fehler: der Buybot ist auch
     * ohne Anmeldung nutzbar, das Frontend zeigt dann den Login-Knopf.
     *
     * @return das Profil oder HTTP 401, wenn niemand angemeldet ist
     */
    @GetMapping("/me")
    public ResponseEntity<UserProfileDto> getCurrentUser(HttpServletResponse response) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Long characterId = (Long) authentication.getPrincipal();
        assert characterId != null;

        var characterOpt = characterRepo.findById(characterId);

        if (characterOpt.isEmpty()) {
            Cookie cookie = new Cookie("toky", null);
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            cookie.setMaxAge(0);
            response.addCookie(cookie);

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Character character = characterOpt.get();

        return ResponseEntity.ok(new UserProfileDto(
                characterId,
                character.getName(),
                String.format("https://images.evetech.net/characters/%d/portrait?size=64", characterId),
                character.getRoles()
        ));
    }

    /**
     * Beendet die Sitzung, indem das Cookie geloescht wird.
     *
     * @param response die Antwort, in der das Cookie entwertet wird
     * @return eine leere Antwort mit HTTP 200
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie("toky", null);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        return ResponseEntity.ok().build();
    }
}