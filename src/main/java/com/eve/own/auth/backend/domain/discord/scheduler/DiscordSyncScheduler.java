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
import org.springframework.web.client.HttpClientErrorException; // <-- Wichtig für die spezifischen Exceptions

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

    @Scheduled(fixedRate = 600_000)
    public void syncDiscordRoles() {
        log.info("Starte Discord Role Sync...");
        List<DiscordConnection> connections = connectionRepo.findAll();

        for (DiscordConnection conn : connections) {
            try {
                Character character = characterRepo.findById(conn.getCharacterId()).orElse(null);
                if (character == null) continue;

                Character mainChar = character.getMainCharacterId() != null
                        ? characterRepo.findById(character.getMainCharacterId()).orElse(character)
                        : character;
                String expectedNickname = mainChar.getName();

                List<String> expectedDiscordRoles = character.getRoles().stream()
                        .map(mappingRepo::findById)
                        .filter(java.util.Optional::isPresent)
                        .map(mapping -> mapping.get().getDiscordRoleId())
                        .toList();

                discordBotService.syncMemberData(conn.getDiscordUserId(), expectedDiscordRoles, expectedNickname);

                Thread.sleep(200);

            } catch (HttpClientErrorException.TooManyRequests e) {
                log.warn("Rate Limit erreicht bei User {}. Pausiere für 5 Sekunden...", conn.getDiscordUserId());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } catch (HttpClientErrorException.Forbidden e) {
                log.info("403 Forbidden bei User {}: Server-Owner oder Bot-Rolle zu niedrig.", conn.getDiscordUserId());
            } catch (HttpClientErrorException.NotFound e) {
                log.info("404 Not Found: User {} hat den Discord-Server verlassen.", conn.getDiscordUserId());
            } catch (Exception e) {
                log.error("Unerwarteter Fehler beim Sync für Discord User {}: {}", conn.getDiscordUserId(), e.getMessage());
            }
        }
        log.info("Discord Role Sync abgeschlossen.");
    }
}