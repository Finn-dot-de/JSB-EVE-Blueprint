package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.common.EveImageUrls;
import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.CorporationScope;
import com.eve.own.auth.backend.domain.character.dto.CharacterDtos;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.esi.EsiService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stellt je betreuter Corporation gegenueber, wer registriert ist und wer nicht.
 *
 * <p>Die eine Haelfte kommt aus der eigenen Datenbank, die andere aus der
 * Mitgliederliste von ESI. Die Differenz ist die eigentliche Aussage: welche
 * Corp-Mitglieder sich hier noch nie angemeldet haben.</p>
 */
@Slf4j
@Service
public class CorporationStatsService {

    /** Maximale Anzahl IDs, die der Namens-Endpunkt von ESI pro Aufruf annimmt. */
    private static final int UNIVERSE_NAMES_MAX_IDS = 500;

    /** Kennzeichnet einen Account, dessen Main in einer anderen Corporation sitzt. */
    private static final String EXTERNAL_MAIN_SUFFIX = " [Main extern]";

    private final CharacterRepository characterRepo;
    private final EsiService esiService;
    private final AuthService authService;
    private final CorporationScope corporationScope;

    public CorporationStatsService(CharacterRepository characterRepo,
                                   EsiService esiService,
                                   AuthService authService,
                                   CorporationScope corporationScope) {
        this.characterRepo = characterRepo;
        this.esiService = esiService;
        this.authService = authService;
        this.corporationScope = corporationScope;
    }

    @Transactional(readOnly = true)
    public List<CharacterDtos.CorpStatsDto> statsForAllCorporations() {
        return corporationScope.allowedCorporationIds().stream()
                .map(this::statsFor)
                .toList();
    }

    private CharacterDtos.CorpStatsDto statsFor(Long corporationId) {
        List<Character> registered = characterRepo.findByCorporationId(corporationId);
        String corporationName = corporationName(corporationId);
        Long[] esiMemberIds = fetchMemberIds(corporationId, registered);

        List<CharacterDtos.AuthedMainDto> authed = groupByAccount(registered);
        List<CharacterDtos.UnauthedCharDto> unauthed = resolveUnauthed(esiMemberIds, registered);

        int registeredAlts = (int) registered.stream().filter(character -> !character.isMain()).count();

        return new CharacterDtos.CorpStatsDto(
                corporationId, corporationName,
                esiMemberIds != null ? esiMemberIds.length : 0,
                authed.size(), registeredAlts, registered.size(),
                authed, unauthed);
    }

    private String corporationName(Long corporationId) {
        try {
            var info = esiService.getCorporationInfo(corporationId);
            if (info != null && info.name() != null) {
                return info.name();
            }
        } catch (Exception e) {
            log.warn("Name der Corporation {} nicht abrufbar: {}", corporationId, e.getMessage());
        }
        return "Unknown Corp (" + corporationId + ")";
    }

    /**
     * Die Mitgliederliste laut ESI.
     *
     * <p>Der Endpunkt verlangt ein Token aus der Corporation selbst; bevorzugt
     * wird eines aus der Fuehrungsebene, weil nur dort die noetigen Rechte
     * verlaesslich vorliegen.</p>
     *
     * @return {@code null}, wenn kein geeignetes Token existiert oder ESI schweigt
     */
    private Long[] fetchMemberIds(Long corporationId, List<Character> registered) {
        Character tokenProvider = registered.stream()
                .filter(character -> character.hasRole(SystemRoles.DIRECTOR)
                        || character.hasRole(SystemRoles.CEO))
                .findFirst()
                .orElse(registered.stream().findFirst().orElse(null));

        if (tokenProvider == null) {
            return null;
        }
        try {
            String token = authService.getValidAccessToken(tokenProvider);
            return esiService.getCorporationMembers(corporationId, token).data();
        } catch (Exception e) {
            log.warn("Mitgliederliste der Corporation {} nicht abrufbar: {}", corporationId, e.getMessage());
            return null;
        }
    }

    /** Die registrierten Charaktere, nach Account gruppiert und alphabetisch sortiert. */
    private List<CharacterDtos.AuthedMainDto> groupByAccount(List<Character> registered) {
        Map<Long, List<Character>> byAccount = registered.stream()
                .collect(Collectors.groupingBy(Character::getAccountId));

        return byAccount.entrySet().stream()
                .map(entry -> toAuthedMain(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(CharacterDtos.AuthedMainDto::mainName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private CharacterDtos.AuthedMainDto toAuthedMain(Long accountId, List<Character> charactersInCorp) {
        Character main = characterRepo.findById(accountId).orElse(charactersInCorp.getFirst());

        List<CharacterDtos.CharacterRefDto> alts = charactersInCorp.stream()
                .filter(character -> !character.getId().equals(main.getId()))
                .map(character -> new CharacterDtos.CharacterRefDto(
                        character.getId(), character.getName(),
                        EveImageUrls.portrait(character.getId()), false))
                .toList();

        // Sitzt der Main selbst in einer anderen Corporation, wird das am Namen
        // sichtbar - sonst waere unklar, warum ein Account ohne Main erscheint.
        boolean mainIsInThisCorp = charactersInCorp.stream()
                .anyMatch(character -> character.getId().equals(main.getId()));
        String displayName = main.getName() + (mainIsInThisCorp ? "" : EXTERNAL_MAIN_SUFFIX);

        return new CharacterDtos.AuthedMainDto(
                main.getId(), displayName, EveImageUrls.portrait(main.getId()), alts);
    }

    /** Die Corp-Mitglieder, zu denen es hier keinen Charakter gibt - mit aufgeloesten Namen. */
    private List<CharacterDtos.UnauthedCharDto> resolveUnauthed(Long[] esiMemberIds,
                                                                List<Character> registered) {
        if (esiMemberIds == null) {
            return List.of();
        }
        Set<Long> knownIds = registered.stream().map(Character::getId).collect(Collectors.toSet());
        List<Long> missingIds = Arrays.stream(esiMemberIds)
                .filter(Objects::nonNull)
                .filter(id -> !knownIds.contains(id))
                .distinct()
                .toList();

        List<CharacterDtos.UnauthedCharDto> unauthed = new ArrayList<>(missingIds.size());
        for (int start = 0; start < missingIds.size(); start += UNIVERSE_NAMES_MAX_IDS) {
            int end = Math.min(start + UNIVERSE_NAMES_MAX_IDS, missingIds.size());
            unauthed.addAll(resolveNames(missingIds.subList(start, end)));
        }

        return unauthed.stream()
                .sorted(Comparator.comparing(CharacterDtos.UnauthedCharDto::name,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private List<CharacterDtos.UnauthedCharDto> resolveNames(List<Long> characterIds) {
        EsiService.EsiIdName[] names = esiService.getUniverseNames(characterIds);
        if (names != null && names.length > 0) {
            return Arrays.stream(names)
                    .map(name -> new CharacterDtos.UnauthedCharDto(
                            name.id(), name.name(), EveImageUrls.portrait(name.id())))
                    .toList();
        }
        return resolveNamesIndividually(characterIds);
    }

    /**
     * Rueckfallebene, wenn die Bulk-Aufloesung scheitert: jede ID einzeln.
     *
     * <p>Bewusst parallel - es koennen mehrere hundert unabhaengige Abrufe sein,
     * die nacheinander spuerbar lange dauern wuerden.</p>
     */
    private List<CharacterDtos.UnauthedCharDto> resolveNamesIndividually(List<Long> characterIds) {
        return characterIds.parallelStream()
                .map(this::resolveSingleName)
                .toList();
    }

    private CharacterDtos.UnauthedCharDto resolveSingleName(Long characterId) {
        String portraitUrl = EveImageUrls.portrait(characterId);
        try {
            var character = esiService.getCharacter(characterId).data();
            if (character != null && character.name() != null) {
                return new CharacterDtos.UnauthedCharDto(characterId, character.name(), portraitUrl);
            }
        } catch (Exception e) {
            log.debug("Name des Charakters {} nicht aufloesbar: {}", characterId, e.getMessage());
        }
        return new CharacterDtos.UnauthedCharDto(
                characterId, "Unbekannter Pilot (" + characterId + ")", portraitUrl);
    }
}
