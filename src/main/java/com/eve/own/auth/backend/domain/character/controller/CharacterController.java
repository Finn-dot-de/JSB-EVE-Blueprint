package com.eve.own.auth.backend.domain.character.controller;

import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.esi.EsiService;
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

    public record CorpStatsDto(Long corpId, String corpName, int totalEsiMembers, int registeredMains, int registeredAlts, int totalRegisteredChars) {}

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

        // 1. Liste aller zu prüfenden Corps aufbauen
        List<Long> corpIdsToTrack = new ArrayList<>();
        corpIdsToTrack.add(mainCorpId);
        if (altCorpIdsStr != null && !altCorpIdsStr.isBlank()) {
            Arrays.stream(altCorpIdsStr.split(","))
                    .map(String::trim)
                    .map(Long::valueOf)
                    .forEach(corpIdsToTrack::add);
        }

        try {
            String token = authService.getValidAccessToken(reqChar);
            List<CorpStatsDto> resultList = new ArrayList<>();

            for (Long cId : corpIdsToTrack) {
                int totalEsiMembers = 0;
                String corpName = "Unknown Corp (" + cId + ")";

                // 2a. ZUERST die Charaktere aus der DB laden
                List<Character> corpCharsInDb = characterRepo.findByCorporationId(cId);

                try {
                    // Den öffentlichen Corp-Namen kann jeder abfragen
                    var corpInfo = esiService.getCorporationInfo(cId);
                    if (corpInfo != null && corpInfo.name() != null) {
                        corpName = corpInfo.name();
                    }

                    // 2b. Den richtigen ESI-Türöffner (Token) für diese Corp finden!
                    // ESI verlangt für die Memberliste meistens einen Director. Wir suchen bevorzugt danach.
                    Character tokenProvider = corpCharsInDb.stream()
                            .filter(c -> c.getRoles().contains("ROLE_DIRECTOR") || c.getRoles().contains("ROLE_CEO"))
                            .findFirst()
                            .orElse(corpCharsInDb.stream().findFirst().orElse(null));

                    // Wenn wir jemanden aus dieser Corp haben, fragen wir mit SEINEM Token bei ESI an
                    if (tokenProvider != null) {
                        String specificCorpToken = authService.getValidAccessToken(tokenProvider);
                        var esiMembers = esiService.getCorporationMembers(cId, specificCorpToken).data();
                        if (esiMembers != null) {
                            totalEsiMembers = esiMembers.length;
                        }
                    } else {
                        System.err.println("Wir haben noch niemanden aus Corp " + cId + " im Auth, der die Memberliste lesen darf.");
                    }

                } catch (Exception e) {
                    System.err.println("ESI Daten für Corp " + cId + " konnten nicht geladen werden: " + e.getMessage());
                }

                // 2c. Mathematik für die Statistik: Mains gibt es NUR in der Hauptcorp!
                long registeredMains = 0;
                int totalRegisteredChars = corpCharsInDb.size();
                int registeredAlts = 0;

                if (cId.equals(mainCorpId)) {
                    // Hauptcorp: Wir zählen die einzigartigen Mains wie gewohnt
                    registeredMains = corpCharsInDb.stream()
                            .map(c -> c.getMainCharacterId() != null ? c.getMainCharacterId() : c.getId())
                            .distinct()
                            .count();
                    registeredAlts = totalRegisteredChars - (int) registeredMains;
                } else {
                    // Alt-Corps: Hier gibt es keine Mains, jeder erfasste Charakter ist ein Alt!
                    registeredMains = 0;
                    registeredAlts = totalRegisteredChars;
                }

                // 2d. Fertige Statistik für diese Corp zur Liste hinzufügen
                resultList.add(new CorpStatsDto(cId, corpName, totalEsiMembers, (int) registeredMains, registeredAlts, totalRegisteredChars));
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