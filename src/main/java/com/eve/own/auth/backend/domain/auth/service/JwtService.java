package com.eve.own.auth.backend.domain.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Stellt die Sitzungs-Token dieser Anwendung aus und prueft sie. */
@Slf4j
@Service
public class JwtService {

    /**
     * Gueltigkeitsdauer eines Tokens.
     *
     * <p>Dieselbe Spanne wie die Lebensdauer des Cookies, in dem es steckt -
     * sonst haette der Nutzer ein Cookie ohne brauchbaren Inhalt oder umgekehrt.</p>
     */
    public static final Duration TOKEN_VALIDITY = Duration.ofDays(1);

    private static final String CLAIM_NAME = "name";
    private static final String CLAIM_ROLES = "roles";

    private final SecretKey key;

    public JwtService(@Value("${app.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(Long characterId, String characterName, Set<String> roles) {
        Date now = new Date();
        return Jwts.builder()
                .subject(characterId.toString())
                .claim(CLAIM_NAME, characterName)
                .claim(CLAIM_ROLES, roles)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + TOKEN_VALIDITY.toMillis()))
                .signWith(key)
                .compact();
    }

    @SuppressWarnings("unchecked")
    public Set<String> getRolesFromToken(String token) {
        List<String> roles = claims(token).get(CLAIM_ROLES, List.class);
        return roles != null ? new HashSet<>(roles) : new HashSet<>();
    }

    public Long getCharacterIdFromToken(String token) {
        return Long.parseLong(claims(token).getSubject());
    }

    /**
     * Prueft Signatur und Ablauf.
     *
     * <p>Ein ungueltiges Token ist der Normalfall - abgelaufene Sitzungen, alte
     * Cookies - und wird deshalb nur auf Debug-Ebene vermerkt.</p>
     */
    public boolean validateToken(String token) {
        try {
            claims(token);
            return true;
        } catch (Exception e) {
            log.debug("JWT abgelehnt: {}", e.getMessage());
            return false;
        }
    }

    private Claims claims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
