package com.eve.buy.bot.backend.domain.auth.service;

import com.eve.buy.bot.backend.domain.auth.entity.SystemRole;
import com.eve.buy.bot.backend.domain.auth.entity.TitleRoleMapping;
import com.eve.buy.bot.backend.domain.auth.repository.SystemRoleRepository;
import com.eve.buy.bot.backend.domain.auth.repository.TitleRoleMappingRepository;
import com.eve.buy.bot.backend.domain.character.entity.Character;
import com.eve.buy.bot.backend.domain.character.repository.CharacterRepository;
import com.eve.buy.bot.backend.esi.EsiService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Leitet die Anwendungsrollen eines Charakters aus seiner Corp-Mitgliedschaft, seinen
 * EVE-Corp-Titeln und der Admin-Liste aus der Konfiguration ab.
 *
 * <p>Der Buybot kennt nur zwei Zugriffsebenen: jeder darf Preise berechnen, und wer eine
 * Admin-Rolle hat, darf die Matrix pflegen. Die Rollen werden sowohl beim Login als auch
 * periodisch neu berechnet, damit ein Titelentzug in EVE zeitnah wirkt.
 *
 * <p>Damit auf einer frisch aufgesetzten Anlage überhaupt jemand an das Admin-Panel kommt,
 * werden die in {@code buybot.admin-characters} genannten Charaktere immer als Administrator
 * behandelt. Ohne diesen Eintrag hätte niemand die Rolle - die Anwendung wäre lauffähig,
 * aber nicht einrichtbar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleSyncService {

    /** Basisrolle, die jeder angemeldete Charakter erhält. */
    public static final String ROLE_USER = "ROLE_USER";

    /** Rolle für Charaktere in der konfigurierten Corporation. */
    public static final String ROLE_MEMBER = "ROLE_MEMBER";

    /** Rolle, die das Admin-Panel freischaltet. */
    public static final String ROLE_ADMIN = "ROLE_IT_ADMIN";

    private final EsiService esiService;
    private final CharacterRepository characterRepo;
    private final TitleRoleMappingRepository titleRepo;
    private final SystemRoleRepository systemRoleRepo;

    @Value("${eve.sso.allowed-corp-id}")
    private Long allowedCorpId;

    /**
     * Kommaseparierte Liste der Administratoren, je Eintrag ein Charaktername oder eine
     * Charakter-ID.
     */
    @Value("${buybot.admin-characters:}")
    private String adminCharactersRaw;

    /** Namen der Administratoren, klein geschrieben für den Vergleich. */
    private Set<String> adminNames = Set.of();

    /** IDs der Administratoren. */
    private Set<Long> adminIds = Set.of();

    /** Zerlegt die konfigurierte Admin-Liste in Namen und IDs. */
    @PostConstruct
    void parseAdminCharacters() {
        Set<String> namen = new LinkedHashSet<>();
        Set<Long> ids = new LinkedHashSet<>();

        if (adminCharactersRaw != null && !adminCharactersRaw.isBlank()) {
            for (String eintrag : adminCharactersRaw.split(",")) {
                String wert = eintrag.trim();
                if (wert.isEmpty()) {
                    continue;
                }
                if (wert.matches("\\d+")) {
                    ids.add(Long.parseLong(wert));
                } else {
                    namen.add(wert.toLowerCase(Locale.ROOT));
                }
            }
        }

        this.adminNames = Set.copyOf(namen);
        this.adminIds = Set.copyOf(ids);

        if (adminNames.isEmpty() && adminIds.isEmpty()) {
            log.warn("Es ist kein Administrator konfiguriert (buybot.admin-characters ist leer). "
                    + "Niemand kann das Admin-Panel öffnen. Trage den Namen deines EVE-Charakters "
                    + "in der .env unter ADMIN_CHARACTERS ein und starte neu.");
        } else {
            log.info("Als Administrator konfiguriert: {} Name(n), {} ID(s).", adminNames.size(), adminIds.size());
        }
    }

    /**
     * Trägt die Admin-Rolle als besondere Rolle ein, falls sie noch nicht bekannt ist.
     *
     * <p>Nur als besonders markierte Rollen überleben eine Neuberechnung. Ohne diesen
     * Eintrag würde eine von Hand in der Datenbank vergebene Admin-Rolle beim nächsten
     * Rollen-Sync wieder verschwinden.
     */
    @EventListener(ApplicationReadyEvent.class)
    void ensureAdminRoleIsKnown() {
        if (systemRoleRepo.existsById(ROLE_ADMIN)) {
            return;
        }
        SystemRole rolle = new SystemRole();
        rolle.setRoleName(ROLE_ADMIN);
        rolle.setDescription("Darf den Buybot verwalten");
        rolle.setSpecial(true);
        systemRoleRepo.save(rolle);
        log.info("Rolle {} als besondere Rolle eingetragen.", ROLE_ADMIN);
    }

    /**
     * Berechnet die Rollen eines Charakters neu und speichert ihn.
     *
     * <p>Von Hand vergebene Sonderrollen (in {@code system_roles} mit {@code is_special})
     * bleiben dabei erhalten - sonst würde ein Sync den manuell gesetzten Admin wieder
     * entrechten.
     *
     * @param character   der zu aktualisierende Charakter
     * @param accessToken gültiges ESI-Token desselben Charakters
     * @return der gespeicherte Charakter mit aktualisierten Rollen
     */
    public Character syncRoles(Character character, String accessToken) {
        Set<String> roles = new HashSet<>();
        roles.add(ROLE_USER);

        if (character.getCorporation() != null && allowedCorpId.equals(character.getCorporation().getId())) {
            roles.add(ROLE_MEMBER);
        }

        if (isConfiguredAdmin(character)) {
            roles.add(ROLE_ADMIN);
        }

        roles.addAll(retainSpecialRoles(character));
        roles.addAll(rolesFromCorpTitles(character, accessToken));

        character.setRoles(roles);
        Character saved = characterRepo.save(character);
        log.debug("Rollen für {}: {}", saved.getName(), roles);
        return saved;
    }

    /**
     * Prüft, ob der Charakter in der Konfiguration als Administrator genannt ist.
     *
     * <p>Der Name wird ohne Rücksicht auf Groß- und Kleinschreibung verglichen, damit sich
     * niemand an der Schreibweise aufhält.
     *
     * @param character der zu prüfende Charakter
     * @return {@code true}, wenn er als Administrator konfiguriert ist
     */
    public boolean isConfiguredAdmin(Character character) {
        if (character == null) {
            return false;
        }
        if (character.getId() != null && adminIds.contains(character.getId())) {
            return true;
        }
        return character.getName() != null
                && adminNames.contains(character.getName().toLowerCase(Locale.ROOT));
    }

    /**
     * Liest die manuell vergebenen Sonderrollen des Charakters aus, damit sie eine
     * Neuberechnung überleben.
     *
     * @param character der Charakter
     * @return seine aktuell gesetzten Sonderrollen, ggf. leer
     */
    private Set<String> retainSpecialRoles(Character character) {
        List<String> specialRoles = systemRoleRepo.findByIsSpecialTrue().stream()
                .map(SystemRole::getRoleName)
                .toList();

        Set<String> retained = new HashSet<>();
        for (String role : character.getRoles()) {
            if (specialRoles.contains(role)) {
                retained.add(role);
            }
        }
        return retained;
    }

    /**
     * Fragt die Corp-Titel des Charakters über ESI ab und übersetzt sie in Rollen.
     *
     * <p>Unbekannte Titel werden automatisch als Zuordnung angelegt, damit ein Admin sie
     * nachträglich umbenennen kann, statt sie erst manuell erfassen zu müssen. Schlägt die
     * ESI-Abfrage fehl, bleibt die Rollenmenge leer statt den ganzen Sync abzubrechen.
     *
     * @param character   der Charakter
     * @param accessToken gültiges ESI-Token desselben Charakters
     * @return die aus den Titeln abgeleiteten Rollennamen
     */
    private Set<String> rolesFromCorpTitles(Character character, String accessToken) {
        Set<String> roles = new HashSet<>();
        if (character.getCorporation() == null) {
            return roles;
        }

        try {
            EsiService.EsiTitleResponse[] titles =
                    esiService.getCharacterTitles(character.getId(), accessToken, null).data();
            if (titles == null || titles.length == 0) {
                return roles;
            }

            List<TitleRoleMapping> mappings = titleRepo.findByCorporationId(character.getCorporation().getId());
            for (EsiService.EsiTitleResponse title : titles) {
                String cleanName = title.name().replaceAll("<[^>]*>", "");
                Optional<TitleRoleMapping> known = mappings.stream()
                        .filter(m -> m.getTitleId().equals(title.title_id()))
                        .findFirst();

                if (known.isEmpty()) {
                    TitleRoleMapping mapping = new TitleRoleMapping();
                    mapping.setCorporationId(character.getCorporation().getId());
                    mapping.setTitleId(title.title_id());
                    mapping.setTitleName(cleanName);
                    mapping.setRoleName(toRoleName(cleanName));
                    titleRepo.save(mapping);
                    mappings.add(mapping);
                    roles.add(mapping.getRoleName());
                    continue;
                }

                TitleRoleMapping mapping = known.get();
                if (!cleanName.equals(mapping.getTitleName())) {
                    mapping.setTitleName(cleanName);
                    titleRepo.save(mapping);
                }
                if (mapping.getRoleName() != null && !mapping.getRoleName().isBlank()) {
                    roles.add(mapping.getRoleName());
                }
            }
        } catch (Exception e) {
            log.warn("Titel für {} konnten nicht geladen werden: {}", character.getName(), e.getMessage());
        }
        return roles;
    }

    /**
     * Formt einen EVE-Titel in einen Spring-Security-Rollennamen um.
     *
     * @param titleName der bereinigte Titelname aus EVE
     * @return Rollenname in der Form {@code ROLE_FLEET_COMMANDER}
     */
    private String toRoleName(String titleName) {
        return "ROLE_" + titleName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }
}
