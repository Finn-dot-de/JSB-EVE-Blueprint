package com.eve.buy.bot.backend.domain.auth.scheduler;

import com.eve.buy.bot.backend.domain.auth.service.AuthService;
import com.eve.buy.bot.backend.domain.auth.service.RoleSyncService;
import com.eve.buy.bot.backend.domain.character.entity.Character;
import com.eve.buy.bot.backend.domain.character.entity.Corporation;
import com.eve.buy.bot.backend.domain.character.repository.CharacterRepository;
import com.eve.buy.bot.backend.domain.character.repository.CorporationRepository;
import com.eve.buy.bot.backend.esi.EsiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;

/**
 * Gleicht in festem Takt ab, wer noch in der Corporation ist und welche Rollen daraus folgen.
 *
 * <p>Ohne diesen Lauf würde ein Admin, der die Corp verlässt, seine Rechte erst beim nächsten
 * Login verlieren - also unter Umständen nie.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleSyncScheduler {

    private final AuthService authService;
    private final RoleSyncService roleSyncService;
    private final EsiService esiService;
    private final CharacterRepository characterRepo;
    private final CorporationRepository corpRepo;

    @Value("${eve.sso.allowed-corp-id}")
    private Long allowedCorpId;

    /** Alle zehn Minuten; ESI-Titel ändern sich selten, häufiger wäre nur Last. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void syncAllCharacters() {
        List<Character> characters = characterRepo.findAllWithCorporation();
        int updated = 0;

        for (Character character : characters) {
            try {
                if (!refreshCorporation(character)) {
                    continue;
                }
                roleSyncService.syncRoles(character, authService.getValidAccessToken(character));
                updated++;
            } catch (Exception e) {
                log.warn("Rollen-Sync für {} fehlgeschlagen: {}", character.getId(), e.getMessage());
            }
        }
        log.debug("Rollen-Sync abgeschlossen: {}/{} Charaktere aktualisiert.", updated, characters.size());
    }

    /**
     * Zieht die aktuelle Corporation aus ESI nach und entzieht einem Main-Charakter alle
     * Rechte, sobald er die freigegebene Corporation verlassen hat.
     *
     * @param character der zu prüfende Charakter
     * @return {@code true}, wenn der Charakter weiter berechtigt ist und die Rollen berechnet
     *     werden sollen
     */
    private boolean refreshCorporation(Character character) {
        EsiService.EsiCharacterResponse publicInfo = esiService.getCharacter(character.getId(), null).data();
        if (publicInfo == null) {
            return true; // ESI antwortet nicht - lieber nichts entziehen
        }

        Long currentCorpId = publicInfo.corporation_id();
        if (character.getCorporation() == null || !currentCorpId.equals(character.getCorporation().getId())) {
            log.info("Charakter {} ist jetzt in Corporation {}.", character.getName(), currentCorpId);
            character.setCorporation(loadOrCreateCorporation(currentCorpId));
            characterRepo.save(character);
        }

        boolean isMain = character.getMainCharacterId() == null
                || character.getMainCharacterId().equals(character.getId());
        if (isMain && !currentCorpId.equals(allowedCorpId)) {
            character.setRoles(new HashSet<>());
            characterRepo.save(character);
            log.info("Rechte entzogen: {} hat die freigegebene Corporation verlassen.", character.getName());
            return false;
        }
        return true;
    }

    /**
     * Lädt eine Corporation aus der Datenbank oder legt sie mit den öffentlichen ESI-Daten an.
     *
     * @param corporationId die EVE-Corporation-ID
     * @return die gespeicherte Corporation
     */
    private Corporation loadOrCreateCorporation(Long corporationId) {
        return corpRepo.findById(corporationId).orElseGet(() -> {
            Corporation corporation = new Corporation();
            corporation.setId(corporationId);
            try {
                EsiService.EsiCorporationResponse info = esiService.getCorporationInfo(corporationId);
                corporation.setName(info != null ? info.name() : "Unbekannte Corporation");
                corporation.setTicker(info != null ? info.ticker() : "???");
            } catch (Exception e) {
                corporation.setName("Unbekannte Corporation");
                corporation.setTicker("???");
            }
            return corpRepo.save(corporation);
        });
    }
}
