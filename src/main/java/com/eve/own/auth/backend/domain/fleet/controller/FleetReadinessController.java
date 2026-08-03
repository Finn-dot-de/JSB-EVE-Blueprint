package com.eve.own.auth.backend.domain.fleet.controller;

import com.eve.own.auth.backend.domain.fleet.dto.ReadinessDtos;
import com.eve.own.auth.backend.domain.fleet.service.FleetReadinessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/fleet/readiness")
@PreAuthorize("hasAnyAuthority('ROLE_69', 'ROLE_1337', 'ROLE_A38', 'ROLE_DIRECTOR', 'ROLE_CEO', 'ROLE_IT_ADMIN')")
public class FleetReadinessController {

    private final FleetReadinessService readinessService;

    public FleetReadinessController(FleetReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    @GetMapping("/doctrines")
    public ResponseEntity<List<String>> doctrines() {
        return ResponseEntity.ok(readinessService.doctrineNames());
    }

    /** Die neue kombinierte Ansicht: Hangar + Skills = Readiness Board */
    @GetMapping("/board")
    public ResponseEntity<?> board(@RequestParam(required = false) String doctrineName) {
        try {
            return ResponseEntity.ok(readinessService.checkReadiness(doctrineName));
        } catch (Exception e) {
            log.error("Readiness-Check fehlgeschlagen", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Readiness-Check fehlgeschlagen: " + e.getMessage()));
        }
    }

    @PostMapping("/sandbox")
    public ResponseEntity<?> sandbox(@RequestBody ReadinessDtos.SandboxRequest request) {
        try {
            return ResponseEntity.ok(readinessService.sandbox(request.eftString()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Sandbox-Auswertung fehlgeschlagen", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Auswertung fehlgeschlagen: " + e.getMessage()));
        }
    }
}