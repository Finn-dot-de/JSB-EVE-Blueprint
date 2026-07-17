package com.eve.own.auth.backend.domain.discord.scheduler;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.discord.entity.DiscordConnection;
import com.eve.own.auth.backend.domain.discord.repository.DiscordConnectionRepository;
import com.eve.own.auth.backend.domain.discord.repository.DiscordRoleMappingRepository;
import com.eve.own.auth.backend.domain.discord.service.DiscordBotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class DiscordSyncScheduler {

    private final DiscordConnectionRepository connectionRepo;
    private final CharacterRepository characterRepo;
    private final DiscordRoleMappingRepository mappingRepo;
    private final DiscordBotService discordBotService;

    public DiscordSyncScheduler(DiscordConnectionRepository connectionRepo, CharacterRepository characterRepo,
                                DiscordRoleMappingRepository mappingRepo, DiscordBotService discordBotService) {
        this.connectionRepo = connectionRepo;
        this.characterRepo = characterRepo;
        this.mappingRepo = mappingRepo;
        this.discordBotService = discordBotService;
    }

    // Läuft z.B. alle 30 Minuten
    @Scheduled(fixedRate = 1800000)
    public void syncDiscordRoles() {
        log.info("Starte Discord Role Sync...");
        List<DiscordConnection> connections = connectionRepo.findAll();

        for (DiscordConnection conn : connections) {
            try {
                Character character = characterRepo.findById(conn.getCharacterId()).orElse(null);
                if (character == null) continue;

                // Alle Discord-Rollen für diesen User ermitteln
                List<String> expectedDiscordRoles = character.getRoles().stream()
                        .map(authRole -> mappingRepo.findById(authRole))
                        .filter(java.util.Optional::isPresent)
                        .map(mapping -> mapping.get().getDiscordRoleId())
                        .toList();

                // Update über den Bot an Discord schicken
                discordBotService.syncMemberRoles(conn.getDiscordUserId(), expectedDiscordRoles);

            } catch (Exception e) {
                log.error("Fehler beim Sync für Discord User {}: {}", conn.getDiscordUserId(), e.getMessage());
            }
        }
        log.info("Discord Role Sync abgeschlossen.");
    }
}