package com.eve.buy.bot.backend.domain.auth.scheduler;

import com.eve.buy.bot.backend.domain.auth.service.AuthService;
import com.eve.buy.bot.backend.domain.character.repository.CharacterRepository;
import com.eve.buy.bot.backend.domain.character.entity.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Erneuert die ESI-Tokens, bevor sie ablaufen.
 *
 * <p>Ohne diesen Lauf wuerde die Vertragspruefung erst beim Zugriff merken, dass ein Token
 * abgelaufen ist - und im unguenstigsten Fall einen Pruefdurchlauf verlieren.
 */
@Component
public class TokenRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(TokenRefreshScheduler.class);

    private final CharacterRepository characterRepo;
    private final AuthService authService;

    public TokenRefreshScheduler(CharacterRepository characterRepo, AuthService authService) {
        this.characterRepo = characterRepo;
        this.authService = authService;
    }

    // Läuft alle 5 Minuten (300.000 Millisekunden)
    /** Erneuert alle Tokens, die demnaechst ablaufen. */
    @Scheduled(fixedRate = 300000)
    public void refreshExpiringTokens() {
        log.info("Starte automatischen Token-Refresh...");

        // Threshold auf "jetzt + 5 Minuten" setzen
        Instant threshold = Instant.now().plusSeconds(300);

        List<Character> expiringCharacters = characterRepo.findCharactersWithExpiringTokens(threshold);

        if (expiringCharacters.isEmpty()) {
            log.info("Keine Tokens müssen aktualisiert werden.");
            return;
        }

        int successCount = 0;
        for (Character character : expiringCharacters) {
            try {
                authService.getValidAccessToken(character);
                successCount++;
            } catch (Exception e) {
                log.error("Fehler beim Token-Refresh für Charakter {}: {}", character.getId(), e.getMessage());
            }
        }

        log.info("Token-Refresh abgeschlossen. {}/{} aktualisiert.", successCount, expiringCharacters.size());
    }
}