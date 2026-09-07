package com.eve.own.auth.backend.domain.fleet.controller;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.common.CurrentUser;
import com.eve.own.auth.backend.domain.fleet.dto.FleetStatisticsDtos;
import com.eve.own.auth.backend.domain.fleet.entity.FleetAttendance;
import com.eve.own.auth.backend.domain.fleet.entity.FleetEvent;
import com.eve.own.auth.backend.domain.fleet.service.FleetStatisticsService;
import com.eve.own.auth.backend.domain.fleet.service.FleetTrackingService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Die Endpunkte zur Erfassung der Flottenteilnahme.
 *
 * <p>Fehlerfaelle behandelt der {@link com.eve.own.auth.backend.common.ApiExceptionHandler}
 * einheitlich; die Endpunkte geben deshalb ihren fachlichen Typ zurueck.</p>
 */
@RestController
@RequestMapping("/api/fleets")
public class FleetController {

    private final FleetTrackingService fleetTrackingService;
    private final FleetStatisticsService fleetStatisticsService;

    public FleetController(FleetTrackingService fleetTrackingService,
                           FleetStatisticsService fleetStatisticsService) {
        this.fleetTrackingService = fleetTrackingService;
        this.fleetStatisticsService = fleetStatisticsService;
    }

    public record CreateFleetDto(String fleetName, String doctrine,
                                 Integer linkExpiryMinutes, String trackingType) {}

    // ==================================================================
    // Fuer alle Mitglieder
    // ==================================================================

    @GetMapping("/recent")
    public ResponseEntity<List<FleetEvent>> getRecentFleets() {
        return ResponseEntity.ok(fleetTrackingService.recentFleets());
    }

    @GetMapping("/{eventId}/attendance")
    public ResponseEntity<List<FleetAttendance>> getFleetAttendance(@PathVariable Long eventId) {
        return ResponseEntity.ok(fleetTrackingService.attendance(eventId));
    }

    /** Traegt den angemeldeten Charakter ueber einen Teilnahme-Link ein. */
    @PostMapping("/join/{trackingCode}")
    public ResponseEntity<Void> joinFleetViaLink(@PathVariable String trackingCode) {
        fleetTrackingService.joinViaLink(CurrentUser.characterId(), trackingCode);
        return ResponseEntity.ok().build();
    }

    // ==================================================================
    // Nur fuer Flottenfuehrung
    // ==================================================================

    @PreAuthorize(AccessRules.FLEET_STAFF)
    @PostMapping("/create")
    public ResponseEntity<FleetEvent> createFleet(@RequestBody CreateFleetDto dto) {
        FleetTrackingService.CreateFleetCommand command = new FleetTrackingService.CreateFleetCommand(
                dto.fleetName(), dto.doctrine(), dto.linkExpiryMinutes(), dto.trackingType());
        return ResponseEntity.ok(fleetTrackingService.createFleet(CurrentUser.characterId(), command));
    }

    @PreAuthorize(AccessRules.FLEET_STAFF)
    @PostMapping("/{eventId}/close")
    public ResponseEntity<Void> closeFleet(@PathVariable Long eventId) {
        fleetTrackingService.closeFleet(CurrentUser.characterId(), eventId);
        return ResponseEntity.ok().build();
    }

    /** @return die Anzahl neu erfasster Teilnehmer */
    @PreAuthorize(AccessRules.FLEET_STAFF)
    @PostMapping("/{eventId}/sync-esi")
    public ResponseEntity<Integer> syncFleetViaEsi(@PathVariable Long eventId) {
        return ResponseEntity.ok(fleetTrackingService.syncViaEsi(CurrentUser.characterId(), eventId));
    }

    /**
     * Die FAT-Statistik.
     *
     * <p>Ein Endpunkt fuer die ganze Seite und nicht einer je Panel: Die Panels
     * beziehen sich alle auf dieselbe Flottenzahl, und getrennt geladen stuende
     * in der Kopfzeile 40 und im Panel darunter 41, sobald zwischendurch eine
     * Flotte geschlossen wird.</p>
     *
     * <p>Die Annotation hier ist die aeussere Sperre; die eigentliche Pruefung
     * steht im {@link FleetStatisticsService} - sie greift auch dann, wenn
     * jemand den Dienst an diesem Endpunkt vorbei ruft.</p>
     *
     * @param tage 30, 90 oder 180; ohne Angabe 90
     */
    @PreAuthorize(AccessRules.FLEET_STAFF)
    @GetMapping("/statistics")
    public ResponseEntity<FleetStatisticsDtos.FatStatistik> getFleetStatistics(
            @RequestParam(required = false) Integer tage) {
        return ResponseEntity.ok(fleetStatisticsService.statistik(CurrentUser.characterId(), tage));
    }
}
