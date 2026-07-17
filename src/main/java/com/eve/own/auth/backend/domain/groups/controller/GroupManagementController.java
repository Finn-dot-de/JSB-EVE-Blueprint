package com.eve.own.auth.backend.domain.groups.controller;

import com.eve.own.auth.backend.domain.auth.entity.TitleRoleMapping;
import com.eve.own.auth.backend.domain.auth.repository.TitleRoleMappingRepository;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.esi.EsiService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/groups")
public class GroupManagementController {

    private final EsiService esiService;
    private final AuthService authService;
    private final CharacterRepository characterRepo;
    private final TitleRoleMappingRepository mappingRepo;

    public GroupManagementController(EsiService esiService, AuthService authService,
                                     CharacterRepository characterRepo, TitleRoleMappingRepository mappingRepo) {
        this.esiService = esiService;
        this.authService = authService;
        this.characterRepo = characterRepo;
        this.mappingRepo = mappingRepo;
    }

    public record CorpTitleDto(Long titleId, String name, String mappedRole) {}

    @GetMapping("/titles")
    public ResponseEntity<List<CorpTitleDto>> getCorporationTitles() {
        // 1. Wer fragt an?
        Long characterId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();
        assert characterId != null;
        Character reqChar = characterRepo.findById(characterId).orElseThrow();
        Long corpId = reqChar.getCorporation().getId();

        // 2. Token holen und ESI nach ALLEN Titeln der Corp fragen
        String token = authService.getValidAccessToken(reqChar);
        var esiTitles = esiService.getCorporationTitles(corpId, token, null).data();

        // 3. Bestehende Mappings aus der Datenbank holen
        List<TitleRoleMapping> existingMappings = mappingRepo.findByCorporationId(corpId);

        // 4. Daten für das Frontend zusammenbauen
        List<CorpTitleDto> result = new ArrayList<>();

        if (esiTitles != null) {
            for (var esiTitle : esiTitles) {

                String cleanName = esiTitle.name().replaceAll("<[^>]*>", "");

                // Prüfen, ob wir in der DB schon eine Rolle (z.B. ROLE_DIRECTOR) dafür haben
                String currentMappedRole = existingMappings.stream()
                        .filter(m -> m.getTitleId().equals(esiTitle.title_id()))
                        .map(TitleRoleMapping::getRoleName)
                        .findFirst()
                        .orElse(null);

                result.add(new CorpTitleDto(esiTitle.title_id(), cleanName, currentMappedRole));
            }
        }

        return ResponseEntity.ok(result);
    }

    public record SaveMappingDto(Long titleId, String roleName) {}

    @PostMapping("/titles/mapping")
    public ResponseEntity<Void> saveTitleMapping(@RequestBody SaveMappingDto dto) {
        Long characterId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();
        assert characterId != null;
        Character reqChar = characterRepo.findById(characterId).orElseThrow();
        Long corpId = reqChar.getCorporation().getId();

        // 1. Prüfen, ob für diesen Titel (z.B. A38) schon ein Mapping existiert
        var existingMapping = mappingRepo.findByCorporationId(corpId).stream()
                .filter(m -> m.getTitleId().equals(dto.titleId()))
                .findFirst();

        if (existingMapping.isPresent()) {
            // 2a. Update: Rolle überschreiben (oder löschen, wenn roleName null/leer ist)
            if (dto.roleName() == null || dto.roleName().isBlank()) {
                mappingRepo.delete(existingMapping.get());
            } else {
                existingMapping.get().setRoleName(dto.roleName());
                mappingRepo.save(existingMapping.get());
            }
        } else if (dto.roleName() != null && !dto.roleName().isBlank()) {
            // 2b. Neu anlegen: Wenn es noch kein Mapping gab
            TitleRoleMapping newMapping = new TitleRoleMapping();
            newMapping.setCorporationId(corpId);
            newMapping.setTitleId(dto.titleId());
            newMapping.setRoleName(dto.roleName());
            mappingRepo.save(newMapping);
        }

        return ResponseEntity.ok().build();
    }
}