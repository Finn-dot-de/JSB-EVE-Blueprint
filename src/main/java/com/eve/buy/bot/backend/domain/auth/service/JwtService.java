package com.eve.buy.bot.backend.domain.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stellt die Sitzungstokens der Anwendung aus und liest sie wieder.
 *
 * <p>Nach dem EVE-Login bekommt der Browser ein eigenes, signiertes Token als Cookie. Die
 * ESI-Tokens von CCP verlassen den Server nie.
 */
@Slf4j
@Service
public class JwtService {

    /** Gültigkeitsdauer eines Sitzungstokens. */
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(24);

    private static final String CLAIM_NAME = "name";
    private static final String CLAIM_ROLES = "roles";

    private final SecretKey key;

    /**
     * @param secret Signaturgeheimnis aus der Konfiguration, mindestens 32 Zeichen
     */
    public JwtService(@Value("${app.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Stellt ein Sitzungstoken für einen Charakter aus.
     *
     * @param characterId   EVE-Charakter-ID
     * @param characterName Anzeigename des Charakters
     * @param roles         die zugewiesenen Anwendungsrollen
     * @return das signierte Token
     */
    public String generateToken(Long characterId, String characterName, Set<String> roles) {
        return Jwts.builder()
                .subject(characterId.toString())
                .claim(CLAIM_NAME, characterName)
                .claim(CLAIM_ROLES, roles)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + TOKEN_LIFETIME.toMillis()))
                .signWith(key)
                .compact();
    }

    /**
     * Prüft Signatur und Gültigkeitsdauer eines Tokens.
     *
     * @param token das zu prüfende Token
     * @return {@code true}, wenn das Token gültig ist
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            log.debug("Sitzungstoken abgelehnt: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Liest die Charakter-ID aus einem gültigen Token.
     *
     * @param token das Sitzungstoken
     * @return die EVE-Charakter-ID
     */
    public Long getCharacterIdFromToken(String token) {
        return Long.parseLong(claims(token).getSubject());
    }

    /**
     * Liest den Charakternamen aus einem gültigen Token.
     *
     * @param token das Sitzungstoken
     * @return der Anzeigename oder {@code null}, wenn das Token keinen enthält
     */
    public String getCharacterNameFromToken(String token) {
        return claims(token).get(CLAIM_NAME, String.class);
    }

    /**
     * Liest die Rollen aus einem gültigen Token.
     *
     * @param token das Sitzungstoken
     * @return die enthaltenen Rollen, ggf. leer
     */
    @SuppressWarnings("unchecked")
    public Set<String> getRolesFromToken(String token) {
        List<String> roles = claims(token).get(CLAIM_ROLES, List.class);
        return roles != null ? new HashSet<>(roles) : new HashSet<>();
    }

    /**
     * Entpackt die Nutzdaten eines Tokens nach Signaturprüfung.
     *
     * @param token das Sitzungstoken
     * @return die enthaltenen Claims
     */
    private Claims claims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
