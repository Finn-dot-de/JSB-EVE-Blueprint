package com.eve.own.auth.backend.domain.auth.scheduler;

import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CharacterStats;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterStatsRepository;
import com.eve.own.auth.backend.esi.EsiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AccountSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountSyncScheduler.class);

    private final AuthService authService;
    private final EsiService esiService;
    private final CharacterRepository characterRepo;
    private final CharacterStatsRepository statsRepo;

    public AccountSyncScheduler(AuthService authService,
                                EsiService esiService,
                                CharacterRepository characterRepo,
                                CharacterStatsRepository statsRepo) {
        this.authService = authService;
        this.esiService = esiService;
        this.characterRepo = characterRepo;
        this.statsRepo = statsRepo;
    }

    @Scheduled(fixedRate = 600000) // Alle 10 Minuten
    public void syncAllAccountData() {
        log.info("Starte Account-Sync...");
        List<Character> allChars = characterRepo.findAll();

        for (Character c : allChars) {
            try {
                String token = authService.getValidAccessToken(c);

                // 1. Statistiken laden oder neu erstellen
                CharacterStats stats = statsRepo.findById(c.getId()).orElse(new CharacterStats());
                stats.setCharacterId(c.getId());

                // 2. Wallet mit ETag abfragen
                var walletResp = esiService.getWalletBalance(c.getId(), token, stats.getWalletEtag());

                // 3. Nur speichern, wenn sich Daten geändert haben (data != null)
                if (walletResp.data() != null) {
                    stats.setWalletBalance(walletResp.data());
                    stats.setWalletEtag(walletResp.etag());
                    statsRepo.save(stats);
                    log.info("Wallet für {} aktualisiert.", c.getName());
                }

            } catch (Exception e) {
                log.error("Sync fehlgeschlagen für Charakter {}: {}", c.getId(), e.getMessage());
            }
        }
        log.info("Account-Sync abgeschlossen.");
    }
}