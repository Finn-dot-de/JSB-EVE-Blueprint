package com.eve.own.auth.backend.domain.groups.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.auth.service.RoleCatalogService;
import com.eve.own.auth.backend.domain.groups.service.TitleMappingService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Die Rechteverwaltung: welche Rollen es gibt und welcher Ingame-Titel welche vergibt.
 *
 * <p>Jeder Endpunkt hier veraendert oder offenbart das Rechtemodell und ist
 * deshalb durchgehend der Fuehrung vorbehalten.</p>
 */
@RestController
@RequestMapping("/api/groups")
public class GroupManagementController {

    private final TitleMappingService titleMappingService;
    private final RoleCatalogService roleCatalogService;

    public GroupManagementController(TitleMappingService titleMappingService,
                                     RoleCatalogService roleCatalogService) {
        this.titleMappingService = titleMappingService;
        this.roleCatalogService = roleCatalogService;
    }

    /**
     * Alle Titel der eigenen Corporation samt der jeweils vergebenen Rolle.
     *
     * <p>Die Rechtepruefung fehlte hier: die Titelstruktur der Corporation ist
     * nichts, was jedes angemeldete Mitglied auslesen koennen muss - sie zeigt,
     * welcher Titel welches Recht oeffnet.</p>
     */
    @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
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

    /** Alle Rollen, die sich einem Titel zuweisen lassen. */
    @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
    @GetMapping("/roles")
    public ResponseEntity<List<RoleCatalogService.AuthRoleDto>> getRoles() {
        return ResponseEntity.ok(roleCatalogService.catalog());
    }

    public record SaveRoleDto(String name, String description, boolean special) {}

    /**
     * Legt eine eigene Rolle an oder aendert ihre Beschreibung.
     *
     * @return die gespeicherte Rolle - ihr Name kann normalisiert worden sein
     */
    @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
    @PostMapping("/roles")
    public ResponseEntity<RoleCatalogService.AuthRoleDto> saveRole(@RequestBody SaveRoleDto dto) {
        return ResponseEntity.ok(
                roleCatalogService.save(dto.name(), dto.description(), dto.special()));
    }

    /** Loescht eine eigene Rolle, sofern kein Titel sie mehr vergibt. */
    @PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
    @DeleteMapping("/roles/{roleName}")
    public ResponseEntity<Void> deleteRole(@PathVariable String roleName) {
        roleCatalogService.delete(roleName);
        return ResponseEntity.noContent().build();
    }
}
