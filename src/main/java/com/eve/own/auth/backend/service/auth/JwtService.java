package com.eve.own.auth.backend.service.auth;

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

    public String generateToken(Long characterId, String characterName) {
        // Token ist für 24 Stunden gültig (in Millisekunden)
        long expirationMs = 86400000;
        return Jwts.builder()
                .subject(characterId.toString()) // Die Character ID ist der Haupt-Identifikator
                .claim("name", characterName)    // Namen fürs Frontend
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }
}