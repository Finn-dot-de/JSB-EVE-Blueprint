package com.eve.own.auth.backend.domain.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;

    public JwtService(@Value("${app.jwt.secret}") String secret) {
        // Generiert einen kryptografisch sicheren Schlüssel aus dem Secret
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(Long characterId, String characterName, java.util.Set<String> roles) {
        long expirationMs = 86400000;
        return Jwts.builder()
                .subject(characterId.toString())
                .claim("name", characterName)
                .claim("roles", roles)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }

    // NEU: Methode zum Auslesen der Rollen
    @SuppressWarnings("unchecked")
    public java.util.Set<String> getRolesFromToken(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        java.util.List<String> roles = claims.get("roles", java.util.List.class);
        return roles != null ? new java.util.HashSet<>(roles) : new java.util.HashSet<>();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            System.err.println("JWT Validation gescheitert: " + e.getMessage());
            return false;
        }
    }

    public Long getCharacterIdFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return Long.parseLong(claims.getSubject());
    }
}