package com.eve.own.auth.backend.domain.discord.scheduler;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.discord.entity.DiscordConnection;
import com.eve.own.auth.backend.domain.discord.entity.DiscordRoleMapping;
import com.eve.own.auth.backend.domain.discord.repository.DiscordConnectionRepository;
import com.eve.own.auth.backend.domain.discord.repository.DiscordRoleMappingRepository;
import com.eve.own.auth.backend.domain.discord.service.DiscordBotService;
import com.eve.own.auth.backend.domain.discord.service.DiscordSyncStand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class DiscordSyncScheduler {

    private final DiscordConnectionRepository connectionRepo;
    private final CharacterRepository characterRepo;
    private final DiscordRoleMappingRepository mappingRepo;
    private final DiscordBotService discordBotService;
    private final DiscordSyncStand syncStand;

    public DiscordSyncScheduler(DiscordConnectionRepository connectionRepo, CharacterRepository characterRepo,
                                DiscordRoleMappingRepository mappingRepo, DiscordBotService discordBotService,
                                DiscordSyncStand syncStand) {
        this.connectionRepo = connectionRepo;
        this.characterRepo = characterRepo;
        this.mappingRepo = mappingRepo;
        this.discordBotService = discordBotService;
        this.syncStand = syncStand;
    }

    @Scheduled(fixedRate = 1_800_00)
    public void syncDiscordRoles() {
        log.info("Starte Discord Role Sync...");
        List<DiscordConnection> connections = connectionRepo.findAll();

        List<String> verwalteteRollen = mappingRepo.findAll().stream()
                .map(DiscordRoleMapping::getDiscordRoleId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();

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
                        .filter(Optional::isPresent)
                        .map(mapping -> mapping.get().getDiscordRoleId())
                        .toList();

                // Nur die verwalteten Rollen anfassen. Frueher ging hier die
                // Soll-Liste als vollstaendiges "roles"-Feld raus - bei Discord
                // ein Vollersatz, also zugleich der Befehl, jede handvergebene
                // Rolle zu entfernen.
                // Der Rueckgabewert ist neu, hier aber ohne Aufgabe: Der Zeitplan
                // hat niemanden, dem er berichten koennte, und protokolliert
                // seine Fehlschlaege wie bisher je Rolle. Ihn auszuwerten hiesse,
                // alle dreissig Minuten dieselbe Liste ins Log zu schreiben.
                discordBotService.syncManagedRoles(conn.getDiscordUserId(),
                        verwalteteRollen, expectedDiscordRoles, expectedNickname);

                // Erst jetzt vermerken: Vorher ist es keine Wahrheit, sondern
                // eine Absicht. Ohne diesen Vermerk kann die Pruefung eine
                // fehlende Rolle nicht von einer blossen Wartezeit trennen und
                // meldet "unbekannt", wo nur noch nichts geschehen ist.
                syncStand.notiere(conn.getDiscordUserId());

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