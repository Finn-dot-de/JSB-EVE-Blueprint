package com.eve.own.auth.backend.domain.navigation.controller;

import com.eve.own.auth.backend.domain.navigation.entity.NavigationLink;
import com.eve.own.auth.backend.domain.navigation.repository.NavigationLinkRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;

@RestController
@RequestMapping("/api/navigation")
public class NavigationController {

    private final NavigationLinkRepository navRepo;
    private final RoleHierarchy roleHierarchy; // NEU

    // NEU: Die RoleHierarchy in den Konstruktor einfügen
    public NavigationController(NavigationLinkRepository navRepo, RoleHierarchy roleHierarchy) {
        this.navRepo = navRepo;
        this.roleHierarchy = roleHierarchy;
    }

    @GetMapping
    public ResponseEntity<List<NavigationLink>> getAllowedLinks() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assert auth != null;

        // NEU: Wir lassen Spring Security die Hierarchie auflösen!
        // Ein ROLE_DIRECTOR wird hier automatisch auch zu ROLE_MEMBER und ROLE_USER erweitert.
        Collection<? extends GrantedAuthority> reachableRoles = roleHierarchy.getReachableGrantedAuthorities(auth.getAuthorities());

        List<String> userRoles = reachableRoles.stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        List<NavigationLink> allLinks = navRepo.findAll();

        List<NavigationLink> allowedLinks = allLinks.stream()
                // 1. Filter: Ist der Link überhaupt aktiv?
                .filter(link -> Boolean.TRUE.equals(link.getActive()))
                // 2. Filter: Hat der User die nötigen Rechte dafür?
                .filter(link -> link.getRequiredRole() == null || userRoles.contains(link.getRequiredRole()))
                .toList();

        return ResponseEntity.ok(allowedLinks);
    }
}