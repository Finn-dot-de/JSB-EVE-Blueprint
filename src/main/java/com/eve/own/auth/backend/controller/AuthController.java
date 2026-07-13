package com.eve.own.auth.backend.controller;

import com.eve.own.auth.backend.service.auth.AuthService;
import com.eve.own.auth.backend.service.auth.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService; // <-- Hier deklarieren
    private final String clientId;
    private final String callbackUrl;
    private final String encodedScopes;
    private final String frontendUrl;

    public AuthController(AuthService authService,
                          JwtService jwtService, // <-- Hier in den Konstruktor injizieren
                          @Value("${eve.sso.client-id}") String clientId,
                          @Value("${app.base.url}") String baseUrl,
                          @Value("${eve.sso.scopes:publicData}") String scopes,
                          @Value("${app.frontend.url}") String frontendUrl) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.clientId = clientId;
        this.frontendUrl = frontendUrl;

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
            var character = authService.processEveLogin(code);
            String token = jwtService.generateToken(character.getId(), character.getName());

            // HttpOnly Cookie bauen
            ResponseCookie jwtCookie = ResponseCookie.from("toky", token)
                    .httpOnly(true)
                    .secure(false)
                    .path("/")
                    .maxAge(24 * 60 * 60)
                    .sameSite("Lax")
                    .build();

            // Redirect OHNE Token in der URL, dafür mit Set-Cookie Header
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.SET_COOKIE, jwtCookie.toString())
                    .location(URI.create(frontendUrl))
                    .build();

        } catch (Exception e) {
            e.printStackTrace();
            String errorUrl = frontendUrl.endsWith("/")
                    ? frontendUrl + "?error=login_failed"
                    : frontendUrl + "/?error=login_failed";

            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(errorUrl))
                    .build();
        }
    }
}