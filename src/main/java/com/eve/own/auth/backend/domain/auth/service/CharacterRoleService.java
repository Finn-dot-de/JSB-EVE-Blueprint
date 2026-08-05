package com.eve.own.auth.backend.domain.auth.service;

import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.auth.entity.SystemRole;
import com.eve.own.auth.backend.domain.auth.entity.TitleRoleMapping;
import com.eve.own.auth.backend.domain.auth.repository.SystemRoleRepository;
import com.eve.own.auth.backend.domain.auth.repository.TitleRoleMappingRepository;
import com.eve.own.auth.backend.domain.character.CorporationScope;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.esi.EsiService;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Bestimmt die Rollen eines Charakters und schreibt sie fort.
 *
 * <p>Rollen entstehen aus drei Quellen, die hier zusammenlaufen:</p>
 * <ol>
 *   <li>der Corp-Zugehoerigkeit - Mitglied einer zugelassenen Corporation oder Gast,</li>
 *   <li>den Ingame-Titeln, die ueber {@code title_role_mappings} auf Rollen zeigen,</li>
 *   <li>den als "speziell" markierten Rollen, die ein Admin von Hand vergeben hat.</li>
 * </ol>
 *
 * <p>Die Berechnung lief bis hierher doppelt: einmal beim Login, einmal im
 * Hintergrund-Sync. Die beiden Kopien waren bereits auseinandergelaufen und
 * vergaben fuer dieselbe Corporation unterschiedliche Rollennamen - siehe
 * {@link SystemRoles#MARAUDERS}. Genau dafuer gibt es jetzt diese Klasse.</p>
 */
@Slf4j
@Service
public class CharacterRoleService {

    /** Entfernt die HTML-Auszeichnung, mit der Spieler ihre Titel ingame faerben. */
    private static final String HTML_TAG_PATTERN = "<[^>]*>";

    private final EsiService esiService;
    private final CharacterRepository characterRepo;
    private final TitleRoleMappingRepository titleRepo;
    private final SystemRoleRepository systemRoleRepo;
    private final CorporationScope corporationScope;

    public CharacterRoleService(EsiService esiService,
                                CharacterRepository characterRepo,
                                TitleRoleMappingRepository titleRepo,
                                SystemRoleRepository systemRoleRepo,
                                CorporationScope corporationScope) {
        this.esiService = esiService;
        this.characterRepo = characterRepo;
        this.titleRepo = titleRepo;
        this.systemRoleRepo = systemRoleRepo;
        this.corporationScope = corporationScope;
    }

    /**
     * Berechnet die Rollen neu und speichert den Charakter.
     *
     * <p>Scheitert der Titel-Abruf, bleiben die Rollen aus Corp-Zugehoerigkeit und
     * Spezialvergabe erhalten. Ein Aussetzer bei ESI darf niemanden aussperren.</p>
     *
     * @param accessToken gueltiges Token des Charakters; ohne Token entfaellt die Titelpruefung
     * @return der gespeicherte Charakter
     */
    public Character applyRoles(Character character, String accessToken) {
        Set<String> roles = new HashSet<>(membershipRoles(character));
        roles.addAll(retainedSpecialRoles(character));
        if (accessToken != null) {
            roles.addAll(titleRoles(character, accessToken));
        }

        character.setRoles(roles);
        Character saved = characterRepo.save(character);
        log.info("Rollen fuer {}: {}", saved.getName(), roles);
        return saved;
    }

    /** Stuft einen Charakter auf Gast zurueck - er ist in keiner betreuten Corporation mehr. */
    public Character demoteToGuest(Character character) {
        character.setRoles(new HashSet<>(Set.of(SystemRoles.GUEST)));
        return characterRepo.save(character);
    }

    private Set<String> membershipRoles(Character character) {
        Long corporationId = character.getCorporation().getId();
        if (!corporationScope.isAllowed(corporationId)) {
            return Set.of(SystemRoles.GUEST);
        }
        if (corporationScope.isMain(corporationId)) {
            return Set.of(SystemRoles.USER, SystemRoles.MEMBER, SystemRoles.MARAUDERS);
        }
        return Set.of(SystemRoles.USER, SystemRoles.MEMBER);
    }

    /**
     * Die von Hand vergebenen Rollen, die eine Neuberechnung ueberleben muessen.
     *
     * <p>Ohne diesen Schritt wuerde der naechste Sync jede administrativ
     * zugewiesene Sonderrolle wieder abraeumen.</p>
     */
    private Set<String> retainedSpecialRoles(Character character) {
        List<String> specialRoles = systemRoleRepo.findByIsSpecialTrue().stream()
                .map(SystemRole::getRoleName)
                .toList();
        return character.getRoles().stream()
                .filter(specialRoles::contains)
                .collect(Collectors.toSet());
    }

    /**
     * Rollen aus den Ingame-Titeln.
     *
     * <p>Unbekannte Titel werden automatisch angelegt und auf einen abgeleiteten
     * Rollennamen gemappt. Ein Admin kann dieses Mapping spaeter umbiegen; ein
     * leer gesetzter Rollenname bedeutet bewusst "dieser Titel vergibt nichts".</p>
     */
    private Set<String> titleRoles(Character character, String accessToken) {
        Set<String> roles = new HashSet<>();
        try {
            var titlesResponse = esiService.getCharacterTitles(character.getId(), accessToken);
            if (titlesResponse.data() == null || titlesResponse.data().length == 0) {
                return roles;
            }

            Long corporationId = character.getCorporation().getId();
            List<TitleRoleMapping> mappings = titleRepo.findByCorporationId(corporationId);

            for (EsiService.EsiTitleResponse title : titlesResponse.data()) {
                String titleName = stripHtml(title.name());
                Optional<TitleRoleMapping> existing = mappings.stream()
                        .filter(mapping -> mapping.getTitleId().equals(title.title_id()))
                        .findFirst();

                if (existing.isPresent()) {
                    roleOf(existing.get(), titleName).ifPresent(roles::add);
                } else {
                    roles.add(createMapping(corporationId, title.title_id(), titleName, mappings));
                }
            }
        } catch (Exception e) {
            log.warn("Titel fuer {} nicht abrufbar, Rollen bleiben ohne Titelanteil: {}",
                    character.getName(), e.getMessage());
        }
        return roles;
    }

    private String createMapping(Long corporationId, Long titleId, String titleName,
                                 List<TitleRoleMapping> mappings) {
        String roleName = SystemRoles.fromTitle(titleName);
        TitleRoleMapping mapping = new TitleRoleMapping();
        mapping.setCorporationId(corporationId);
        mapping.setTitleId(titleId);
        mapping.setTitleName(titleName);
        mapping.setRoleName(roleName);

        titleRepo.save(mapping);
        mappings.add(mapping);
        return roleName;
    }

    /** Haelt den gespeicherten Titelnamen aktuell und liefert die zugeordnete Rolle. */
    private Optional<String> roleOf(TitleRoleMapping mapping, String currentTitleName) {
        if (!currentTitleName.equals(mapping.getTitleName())) {
            mapping.setTitleName(currentTitleName);
            titleRepo.save(mapping);
        }
        return Optional.ofNullable(mapping.getRoleName())
                .filter(roleName -> !roleName.isBlank());
    }

    private static String stripHtml(String titleName) {
        return titleName.replaceAll(HTML_TAG_PATTERN, "");
    }
}
