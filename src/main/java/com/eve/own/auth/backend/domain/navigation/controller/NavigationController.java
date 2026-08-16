package com.eve.own.auth.backend.domain.navigation.controller;

import com.eve.own.auth.backend.domain.navigation.dto.NavigationDtos;
import com.eve.own.auth.backend.domain.navigation.service.NavigationService;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liefert das Menue, das der angemeldete Nutzer sehen darf - fertig sortiert. */
@RestController
@RequestMapping("/api/navigation")
public class NavigationController {

    private final NavigationService navigationService;
    private final RoleHierarchy roleHierarchy;

    public NavigationController(NavigationService navigationService, RoleHierarchy roleHierarchy) {
        this.navigationService = navigationService;
        this.roleHierarchy = roleHierarchy;
    }

    @GetMapping
    public ResponseEntity<List<NavigationDtos.MenuEntryDto>> menu() {
        return ResponseEntity.ok(navigationService.menuFor(reachableRoles()));
    }

    /**
     * Die Rollen des Nutzers, aufgeloest ueber die Rollenhierarchie.
     *
     * <p>Ein Director traegt damit auch die Rechte eines Mitglieds, ohne dass die
     * Rolle einzeln an ihm haengt - so wie es die Hierarchie in der
     * Sicherheitskonfiguration beschreibt.</p>
     */
    private Set<String> reachableRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Set.of();
        }
        return roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities()).stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
