package com.eve.own.auth.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Sitzungs-Token")
class JwtServiceTest {

    private static final String SECRET = "ein-hinreichend-langes-testgeheimnis-fuer-hmac-sha";
    private static final Long CHARACTER_ID = 95465499L;

    private final JwtService service = new JwtService(SECRET);

    @Test
    @DisplayName("gibt Charakter und Rollen unveraendert zurueck")
    void roundTripsClaims() {
        String token = service.generateToken(CHARACTER_ID, "Main-Token",
                Set.of("ROLE_USER", "ROLE_DIRECTOR"));

        assertThat(service.validateToken(token)).isTrue();
        assertThat(service.getCharacterIdFromToken(token)).isEqualTo(CHARACTER_ID);
        assertThat(service.getRolesFromToken(token))
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_DIRECTOR");
    }

    @Test
    @DisplayName("kommt mit einem Token ganz ohne Rollen zurecht")
    void handlesTokenWithoutRoles() {
        String token = service.generateToken(CHARACTER_ID, "Main-Token", Set.of());

        assertThat(service.getRolesFromToken(token)).isEmpty();
    }

    @Test
    @DisplayName("weist ein Token mit fremder Unterschrift ab")
    void rejectsForeignSignature() {
        JwtService attacker = new JwtService("ein-voellig-anderes-geheimnis-mit-genug-laenge");
        String forged = attacker.generateToken(CHARACTER_ID, "Main-Token", Set.of("ROLE_CEO"));

        assertThat(service.validateToken(forged)).isFalse();
    }

    @Test
    @DisplayName("weist ein abgelaufenes Token ab")
    void rejectsExpiredToken() {
        String expired = Jwts.builder()
                .subject(CHARACTER_ID.toString())
                .issuedAt(new Date(0))
                .expiration(new Date(1000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(service.validateToken(expired)).isFalse();
    }

    @Test
    @DisplayName("weist voellig unlesbare Zeichenketten ab")
    void rejectsGarbage() {
        assertThat(service.validateToken("kein.token.hier")).isFalse();
        assertThat(service.validateToken("")).isFalse();
    }

    @Test
    @DisplayName("stellt Token mit der vereinbarten Gueltigkeit aus")
    void usesConfiguredValidity() {
        assertThat(JwtService.TOKEN_VALIDITY.toHours()).isEqualTo(24);
    }
}
