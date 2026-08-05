package com.eve.own.auth.backend.domain.groups.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.groups.service.TitleMappingService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Die Endpunkte zur Zuordnung von Ingame-Titeln zu Rollen. */
@RestController
@RequestMapping("/api/groups")
public class GroupManagementController {

    private final TitleMappingService titleMappingService;

    public GroupManagementController(TitleMappingService titleMappingService) {
        this.titleMappingService = titleMappingService;
    }

    /** Alle Titel der eigenen Corporation samt der jeweils vergebenen Rolle. */
    @GetMapping("/titles")
    public ResponseEntity<List<TitleMappingService.CorpTitleDto>> getCorporationTitles() {
        return ResponseEntity.ok(titleMappingService.corporationTitles(CurrentUser.characterId()));
    }

    public record SaveMappingDto(Long titleId, String roleName) {}

    /**
     * Legt fest, welche Rolle ein Titel vergibt.
     *
     * <p>Der Endpunkt verteilt Rechte und ist deshalb der Fuehrung vorbehalten.
     * Ohne diese Pruefung koennte jedes Mitglied einen selbst getragenen Titel
     * auf eine Fuehrungsrolle abbilden und sich beim naechsten Sync selbst
     * befoerdern.</p>
     */
    @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
    @PostMapping("/titles/mapping")
    public ResponseEntity<Void> saveTitleMapping(@RequestBody SaveMappingDto dto) {
        titleMappingService.saveMapping(CurrentUser.characterId(), dto.titleId(), dto.roleName());
        return ResponseEntity.ok().build();
    }
}
