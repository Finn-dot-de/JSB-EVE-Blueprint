package com.eve.own.auth.backend.domain.character.controller;

import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.esi.EsiService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/characters")
public class CharacterController {

    private final CharacterRepository characterRepo;
    private final EsiService esiService;
    private final AuthService authService;

    public CharacterController(CharacterRepository characterRepo, EsiService esiService, AuthService authService) {
        this.characterRepo = characterRepo;
        this.esiService = esiService;
        this.authService = authService;
    }

    public record AltDto(Long id, String name, String portraitUrl, boolean isMain) {}
    public record CorpStatsDto(int totalEsiMembers, int registeredMains, int registeredAlts) {}

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
        Long corpId = reqChar.getCorporation().getId();

        try {
            // 1. Echte Member-Zahl aus EVE laden
            String token = authService.getValidAccessToken(reqChar);
            var esiMembers = esiService.getCorporationMembers(corpId, token).data();
            int totalEsiMembers = esiMembers != null ? esiMembers.length : 0;

            // 2. Datenbank auswerten
            List<Character> corpCharsInDb = characterRepo.findByCorporationId(corpId);

            // Wie viele einzigartige Mains haben wir in dieser Corp?
            long registeredMains = corpCharsInDb.stream()
                    .map(c -> c.getMainCharacterId() != null ? c.getMainCharacterId() : c.getId())
                    .distinct()
                    .count();

            // Der Rest sind Alts
            int registeredAlts = corpCharsInDb.size() - (int) registeredMains;

            return ResponseEntity.ok(new CorpStatsDto(totalEsiMembers, (int) registeredMains, registeredAlts));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(java.util.Map.of("message", "Fehler beim Laden der ESI Corp-Daten. Fehlt der Scope 'esi-corporations.read_corporation_membership.v1'?"));
        }
    }
}