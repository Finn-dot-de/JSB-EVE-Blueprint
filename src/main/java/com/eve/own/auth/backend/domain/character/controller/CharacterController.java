package com.eve.own.auth.backend.domain.character.controller;

import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.esi.EsiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/characters")
@Slf4j
public class CharacterController {

    private final CharacterRepository characterRepo;
    private final EsiService esiService;
    private final AuthService authService;
    private final Long mainCorpId;
    private final String altCorpIdsStr;

    // Variablen  ber den Konstruktor injizieren
    public CharacterController(CharacterRepository characterRepo,
                               EsiService esiService,
                               AuthService authService,
                               @Value("${eve.sso.allowed-corp-id}") Long mainCorpId,
                               @Value("${eve.alt-corp-ids:}") String altCorpIdsStr) {
        this.characterRepo = characterRepo;
        this.esiService = esiService;
        this.authService = authService;
        this.mainCorpId = mainCorpId;
        this.altCorpIdsStr = altCorpIdsStr;
    }

    public record AltDto(Long id, String name, String portraitUrl, boolean isMain) {}

    public record AuthedAltDto(Long id, String name, String portraitUrl, boolean isMain) {}
    public record AuthedMainDto(Long mainId, String mainName, String portraitUrl, List<AuthedAltDto> alts) {}
    public record UnauthedCharDto(Long id, String name, String portraitUrl) {}

    public record CorpStatsDto(
            Long corpId, String corpName, int totalEsiMembers,
            int registeredMains, int registeredAlts, int totalRegisteredChars,
            List<AuthedMainDto> authedMembers,
            List<UnauthedCharDto> unauthedMembers
    ) {}

    @GetMapping("/alts")
    public ResponseEntity<List<AltDto>> getMyCharacters() {
        Long charId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();
        assert charId != null;
        Character reqChar = characterRepo.findById(charId).orElseThrow();
        Long mainId = reqChar.getMainCharacterId() != null ? reqChar.getMainCharacterId() : reqChar.getId();

        List<AltDto> alts = characterRepo.findByMainCharacterId(mainId).stream()
                .map(c -> new AltDto(
                        c.getId(),
                        c.getName(),
                        String.format("https://images.evetech.net/characters/%d/portrait?size=64", c.getId()),
                        c.getId().equals(mainId)
                )).toList();

        return ResponseEntity.ok(alts);
    }

    @PreAuthorize("hasAnyRole('ROLE_DIRECTOR', 'ROLE_CEO', 'ROLE_IT_ADMIN')")
    @GetMapping("/corp-stats")
    public ResponseEntity<?> getCorporationStats() {
        Long charId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();
        assert charId != null;
        Character reqChar = characterRepo.findById(charId).orElseThrow();

        List<Long> corpIdsToTrack = new ArrayList<>();
        corpIdsToTrack.add(mainCorpId);
        if (altCorpIdsStr != null && !altCorpIdsStr.isBlank()) {
            Arrays.stream(altCorpIdsStr.split(",")).map(String::trim).map(Long::valueOf).forEach(corpIdsToTrack::add);
        }

        try {
            String token = authService.getValidAccessToken(reqChar);
            List<CorpStatsDto> resultList = new ArrayList<>();

            for (Long cId : corpIdsToTrack) {
                int totalEsiMembers = 0;
                String corpName = "Unknown Corp (" + cId + ")";
                List<Character> corpCharsInDb = characterRepo.findByCorporationId(cId);
                Long[] esiMembers = null;

                try {
                    var corpInfo = esiService.getCorporationInfo(cId);
                    if (corpInfo != null && corpInfo.name() != null) corpName = corpInfo.name();

                    Character tokenProvider = corpCharsInDb.stream()
                            .filter(c -> c.getRoles().contains("ROLE_DIRECTOR") || c.getRoles().contains("ROLE_CEO"))
                            .findFirst().orElse(corpCharsInDb.stream().findFirst().orElse(null));

                    if (tokenProvider != null) {
                        String specificCorpToken = authService.getValidAccessToken(tokenProvider);
                        var membersResp = esiService.getCorporationMembers(cId, specificCorpToken).data();
                        if (membersResp != null) {
                            esiMembers = membersResp;
                            totalEsiMembers = esiMembers.length;
                        }
                    }
                } catch (Exception e) {
                    log.warn("ESI-Daten fuer Corp {} konnten nicht geladen werden: {}", cId, e.getMessage());
                }

                // ==========================================
                // 1. UNAUTHED MEMBERS FINDEN UND NAMEN LADEN
                // ==========================================
                List<UnauthedCharDto> unauthedMembers = new ArrayList<>();
                if (esiMembers != null) {
                    java.util.Set<Long> dbIds = corpCharsInDb.stream().map(Character::getId).collect(java.util.stream.Collectors.toSet());

                    List<Long> missingIds = Arrays.stream(esiMembers)
                            .filter(Objects::nonNull)
                            .filter(id -> !dbIds.contains(id))
                            .distinct()
                            .toList();

                    if (!missingIds.isEmpty()) {
                        for (int i = 0; i < missingIds.size(); i += 500) {
                            List<Long> batch = missingIds.subList(i, Math.min(i + 500, missingIds.size()));
                            var names = esiService.getUniverseNames(batch);

                            if (names != null && names.length > 0) {
                                for (var n : names) {
                                    unauthedMembers.add(new UnauthedCharDto(n.id(), n.name(), "https://images.evetech.net/characters/" + n.id() + "/portrait?size=64"));
                                }
                            } else {
                                List<UnauthedCharDto> fallbackResolved = batch.parallelStream().map(id -> {
                                    String portraitUrl = "https://images.evetech.net/characters/" + id + "/portrait?size=64";
                                    try {
                                        var charData = esiService.getCharacter(id).data();
                                        if (charData != null && charData.name() != null) {
                                            return new UnauthedCharDto(id, charData.name(), portraitUrl);
                                        }
                                    } catch (Exception ex) {
                                        log.debug("Name fuer Charakter {} nicht aufloesbar: {}", id, ex.getMessage());
                                    }
                                    return new UnauthedCharDto(id, "Unbekannter Pilot (" + id + ")", portraitUrl);
                                }).toList();
                                unauthedMembers.addAll(fallbackResolved);
                            }
                        }
                    }
                }

                // ==========================================
                // 2. AUTHED MEMBERS NACH MAIN GRUPPIEREN
                // ==========================================
                List<AuthedMainDto> authedMembers = new ArrayList<>();
                java.util.Map<Long, List<Character>> byMain = corpCharsInDb.stream()
                        .collect(java.util.stream.Collectors.groupingBy(c -> c.getMainCharacterId() != null ? c.getMainCharacterId() : c.getId()));

                for (var entry : byMain.entrySet()) {
                    Long mainId = entry.getKey();
                    List<Character> charsInThisCorp = entry.getValue();

                    Character mainChar = characterRepo.findById(mainId).orElse(charsInThisCorp.get(0));

                    List<AuthedAltDto> alts = charsInThisCorp.stream()
                            .filter(c -> !c.getId().equals(mainChar.getId()))
                            .map(c -> new AuthedAltDto(c.getId(), c.getName(), "https://images.evetech.net/characters/" + c.getId() + "/portrait?size=64", false))
                            .toList();

                    boolean isMainInThisCorp = charsInThisCorp.stream().anyMatch(c -> c.getId().equals(mainChar.getId()));
                    String displayName = mainChar.getName() + (isMainInThisCorp ? "" : " [Main extern]");

                    authedMembers.add(new AuthedMainDto(
                            mainChar.getId(),
                            displayName,
                            "https://images.evetech.net/characters/" + mainChar.getId() + "/portrait?size=64",
                            alts
                    ));
                }

                authedMembers.sort(java.util.Comparator.comparing(AuthedMainDto::mainName, String.CASE_INSENSITIVE_ORDER));
                unauthedMembers.sort(java.util.Comparator.comparing(UnauthedCharDto::name, String.CASE_INSENSITIVE_ORDER));

                // ==========================================
                // 3. STATISTIKEN KORREKT ZÄHLEN
                // ==========================================
                int registeredMains = byMain.size(); // Zeigt jetzt korrekterweise IMMER die Mains an!
                int registeredAlts = (int) corpCharsInDb.stream()
                        .filter(c -> c.getMainCharacterId() != null && !c.getMainCharacterId().equals(c.getId()))
                        .count();
                int totalRegisteredChars = corpCharsInDb.size();

                resultList.add(new CorpStatsDto(cId, corpName, totalEsiMembers, registeredMains, registeredAlts, totalRegisteredChars, authedMembers, unauthedMembers));
            }
            return ResponseEntity.ok(resultList);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(java.util.Map.of("message", "Fehler beim Laden der ESI Corp-Daten."));
        }
    }

    @PostMapping("/set-main/{newMainId}")
    public ResponseEntity<?> setMainCharacter(@PathVariable Long newMainId) {
        Long charId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();
        assert charId != null;
        Character reqChar = characterRepo.findById(charId).orElseThrow();
        Long currentMainId = reqChar.getMainCharacterId() != null ? reqChar.getMainCharacterId() : reqChar.getId();

        List<Character> allAccountChars = characterRepo.findByMainCharacterId(currentMainId);

        boolean isOwnAlt = allAccountChars.stream().anyMatch(c -> c.getId().equals(newMainId));
        if (!isOwnAlt) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "Dieser Charakter gehört nicht zu deinem Account!"));
        }

        // Main ID für alle updaten
        for (Character c : allAccountChars) {
            c.setMainCharacterId(newMainId);
        }
        characterRepo.saveAll(allAccountChars);

        return ResponseEntity.ok().build();
    }
}