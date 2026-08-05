package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.auth.service.CharacterRoleService;
import com.eve.own.auth.backend.domain.character.CorporationScope;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.Corporation;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.repository.CorporationRepository;
import com.eve.own.auth.backend.esi.EsiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Haelt die Corp-Zugehoerigkeit eines Charakters aktuell.
 *
 * <p>Wechselt jemand die Corporation, faellt das erst hier auf - das eigene
 * Rollenmodell haengt an der Corp, nicht am Login. Verlaesst ein Main-Charakter
 * den betreuten Kreis, wird er auf Gast zurueckgestuft statt geloescht: seine
 * Bestands- und Steuerhistorie bleibt damit erhalten, der Zugriff aber weg.</p>
 */
@Slf4j
@Service
public class CharacterMembershipService {

    private static final String UNKNOWN_CORPORATION_NAME = "Unknown Corp";
    private static final String UNKNOWN_CORPORATION_TICKER = "UNK";

    private final EsiService esiService;
    private final CharacterRepository characterRepo;
    private final CorporationRepository corpRepo;
    private final CorporationScope corporationScope;
    private final CharacterRoleService roleService;

    public CharacterMembershipService(EsiService esiService,
                                      CharacterRepository characterRepo,
                                      CorporationRepository corpRepo,
                                      CorporationScope corporationScope,
                                      CharacterRoleService roleService) {
        this.esiService = esiService;
        this.characterRepo = characterRepo;
        this.corpRepo = corpRepo;
        this.corporationScope = corporationScope;
        this.roleService = roleService;
    }

    /**
     * Prueft die Zugehoerigkeit und zieht die Konsequenzen.
     *
     * @return {@code false}, wenn der Charakter nicht weiter synchronisiert werden
     *     soll - er ist zum Gast zurueckgestuft
     */
    public boolean verifyMembership(Character character) {
        var publicInfo = esiService.getCharacter(character.getId()).data();
        if (publicInfo == null) {
            // Keine Auskunft von ESI ist kein Beweis fuer einen Austritt.
            return true;
        }

        Long currentCorporationId = publicInfo.corporation_id();
        if (!currentCorporationId.equals(character.getCorporation().getId())) {
            moveToCorporation(character, currentCorporationId);
        }

        if (character.isMain() && !corporationScope.isAllowed(currentCorporationId)) {
            roleService.demoteToGuest(character);
            log.info("Main-Charakter {} ist in keiner betreuten Corporation mehr (ESI: {}). "
                    + "Rechte auf Gast zurueckgesetzt.", character.getName(), currentCorporationId);
            return false;
        }
        return true;
    }

    /** Haelt die Fraktionszugehoerigkeit der Corporation nach - sie treibt die Militia-Anzeige. */
    public void refreshCorporationFaction(Character character) {
        Corporation corporation = character.getCorporation();
        if (corporation == null) {
            return;
        }
        try {
            var corporationInfo = esiService.getCorporationInfo(corporation.getId());
            if (corporationInfo != null && corporationInfo.faction_id() != null) {
                corporation.setFactionId(corporationInfo.faction_id());
                corpRepo.save(corporation);
            }
        } catch (Exception e) {
            log.warn("Fraktionsdaten fuer Corp {} nicht ladbar: {}", corporation.getName(), e.getMessage());
        }
    }

    private void moveToCorporation(Character character, Long corporationId) {
        log.warn("Charakter {} hat die Corporation gewechselt, neu: {}", character.getName(), corporationId);
        character.setCorporation(corpRepo.findById(corporationId)
                .orElseGet(() -> corpRepo.save(fetchCorporation(corporationId))));
        characterRepo.save(character);
    }

    /**
     * Legt eine bislang unbekannte Corporation an.
     *
     * <p>Scheitert der ESI-Abruf, wird trotzdem ein Platzhalter gespeichert: der
     * Charakter braucht eine gueltige Corp-Referenz, sonst laesst er sich nicht
     * speichern. Der naechste Lauf holt die echten Stammdaten nach.</p>
     */
    private Corporation fetchCorporation(Long corporationId) {
        Corporation corporation = new Corporation();
        corporation.setId(corporationId);
        try {
            var corporationInfo = esiService.getCorporationInfo(corporationId);
            if (corporationInfo != null) {
                corporation.setName(corporationInfo.name());
                corporation.setTicker(corporationInfo.ticker());
                corporation.setFactionId(corporationInfo.faction_id());
                return corporation;
            }
        } catch (Exception e) {
            log.warn("Stammdaten der Corporation {} nicht ladbar: {}", corporationId, e.getMessage());
        }
        corporation.setName(UNKNOWN_CORPORATION_NAME);
        corporation.setTicker(UNKNOWN_CORPORATION_TICKER);
        return corporation;
    }
}
