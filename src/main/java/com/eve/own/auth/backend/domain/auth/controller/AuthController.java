package com.eve.own.auth.backend.domain.auth.controller;

import com.eve.own.auth.backend.domain.auth.dto.UserProfileDto;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.auth.service.JwtService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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

        this.encodedScopes = URLEncoder.encode(scopes, StandardCharsets.UTF_8);
    }

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
            String token = jwtService.generateToken(jwtTargetId, "Main-Token", rolesToUse);

            ResponseCookie jwtCookie = ResponseCookie.from("toky", token)
                    .httpOnly(true)
                    .secure(false)
                    .path("/")
                    .maxAge(24 * 60 * 60)
                    .sameSite("Lax")
                    .build();

            // --- NEU: Dynamische Weiterleitung ---
            String targetUrl;
            if (loggedInMainId != null) {
                // User war schon eingeloggt -> Er hat gerade einen Alt hinzugefügt
                targetUrl = frontendUrl.endsWith("/") ? frontendUrl + "charlink" : frontendUrl + "/charlink";
            } else {
                // User war nicht eingeloggt -> Frischer Haupt-Login
                targetUrl = frontendUrl; // (Oder frontendUrl + "/dashboard", je nachdem, wo er starten soll)
            }

            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.SET_COOKIE, jwtCookie.toString())
                    .location(URI.create(targetUrl)) // Hier nutzen wir jetzt die Ziel-URL
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