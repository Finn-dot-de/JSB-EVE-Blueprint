package com.eve.own.auth.backend.domain.fleet.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.fleet.dto.ReadinessDtos;
import com.eve.own.auth.backend.domain.fleet.service.FleetReadinessService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Zeigt, wer eine Doktrin tatsaechlich fliegen kann - Hangar und Skills zusammen.
 *
 * <p>Fehlerfaelle behandelt der {@link com.eve.own.auth.backend.common.ApiExceptionHandler};
 * die Endpunkte geben deshalb ihren fachlichen Typ zurueck statt eines Wildcards.</p>
 */
@RestController
@RequestMapping("/api/fleet/readiness")
@PreAuthorize(AccessRules.FLEET_VIEWERS)
public class FleetReadinessController {

    private final FleetReadinessService readinessService;

    public FleetReadinessController(FleetReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    /**
     * Die Selbstauskunft: was der Anfragende selbst fliegen kann.
     *
     * <p>Das {@code @PreAuthorize} der Klasse gilt hier ausdruecklich nicht -
     * jedes Mitglied soll seinen eigenen Stand sehen. Vertretbar ist das nur,
     * weil die Auswertung ausschliesslich den eigenen Account umfasst; die
     * Charakter-ID kommt aus der Sitzung und nicht aus der Anfrage.</p>
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/mine")
    public ResponseEntity<List<ReadinessDtos.MyFitDto>> myReadiness() {
        return ResponseEntity.ok(readinessService.myReadiness(CurrentUser.characterId()));
    }

    @GetMapping("/doctrines")
    public ResponseEntity<List<String>> doctrines() {
        return ResponseEntity.ok(readinessService.doctrineNames());
    }

    /** Das kombinierte Board: Hangar und Skills je Account. */
    @GetMapping("/board")
    public ResponseEntity<ReadinessDtos.DoctrineReadinessDto> board(
            @RequestParam(required = false) String doctrineName) {
        return ResponseEntity.ok(readinessService.checkReadiness(doctrineName));
    }

    /** Dasselbe Board fuer ein frei eingefuegtes EFT-Fitting statt einer Doktrin. */
    @PostMapping("/sandbox")
    public ResponseEntity<ReadinessDtos.SandboxResultDto> sandbox(
            @RequestBody ReadinessDtos.SandboxRequest request) {
        return ResponseEntity.ok(readinessService.sandbox(request.eftString()));
    }
}
