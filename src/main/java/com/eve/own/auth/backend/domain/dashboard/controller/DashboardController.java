package com.eve.own.auth.backend.domain.dashboard.controller;

import com.eve.own.auth.backend.domain.dashboard.dto.DashboardDto;
import com.eve.own.auth.backend.domain.dashboard.service.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public ResponseEntity<DashboardDto> getDashboard() {
        Long characterId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();

        DashboardDto dashboardData = dashboardService.getDashboardData(characterId);

        return ResponseEntity.ok(dashboardData);
    }
}