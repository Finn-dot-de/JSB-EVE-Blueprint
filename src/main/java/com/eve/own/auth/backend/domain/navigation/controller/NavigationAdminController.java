package com.eve.own.auth.backend.domain.navigation.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.domain.navigation.dto.NavigationDtos;
import com.eve.own.auth.backend.domain.navigation.service.NavigationService;
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
 * Die Pflege der Seitenleiste.
 *
 * <p>Hier entscheidet sich, was jedes Mitglied im Menue sieht - und ueber die
 * hinterlegte Rolle auch, wer welchen Bereich ueberhaupt angeboten bekommt.
 * Deshalb durchgehend der Fuehrung und der technischen Administration
 * vorbehalten.</p>
 */
@RestController
@RequestMapping("/api/admin/navigation")
@PreAuthorize(AccessRules.LEADERSHIP_OR_IT)
public class NavigationAdminController {

    private final NavigationService navigationService;

    public NavigationAdminController(NavigationService navigationService) {
        this.navigationService = navigationService;
    }

    /** Der vollstaendige Stand - auch die abgeschalteten Eintraege. */
    @GetMapping
    public ResponseEntity<NavigationDtos.AdminViewDto> overview() {
        return ResponseEntity.ok(navigationService.adminView());
    }

    @PostMapping("/categories")
    public ResponseEntity<NavigationDtos.CategoryDto> saveCategory(
            @RequestBody NavigationDtos.SaveCategoryDto dto) {
        return ResponseEntity.ok(navigationService.saveCategory(dto));
    }

    @DeleteMapping("/categories/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable Long id) {
        navigationService.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/links")
    public ResponseEntity<NavigationDtos.LinkDto> saveLink(
            @RequestBody NavigationDtos.SaveLinkDto dto) {
        return ResponseEntity.ok(navigationService.saveLink(dto));
    }

    @DeleteMapping("/links/{id}")
    public ResponseEntity<Void> deleteLink(@PathVariable Long id) {
        navigationService.deleteLink(id);
        return ResponseEntity.noContent().build();
    }

    /** Verschiebt einen Eintrag um eine Position innerhalb seiner Ebene. */
    @PostMapping("/move")
    public ResponseEntity<Void> move(@RequestBody NavigationDtos.MoveDto dto) {
        navigationService.move(dto);
        return ResponseEntity.ok().build();
    }
}
