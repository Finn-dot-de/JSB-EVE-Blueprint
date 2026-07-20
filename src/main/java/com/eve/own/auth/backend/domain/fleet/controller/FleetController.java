package com.eve.own.auth.backend.domain.fleet.controller;

import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.domain.fleet.entity.FleetAttendance;
import com.eve.own.auth.backend.domain.fleet.entity.FleetEvent;
import com.eve.own.auth.backend.domain.fleet.repository.FleetAttendanceRepository;
import com.eve.own.auth.backend.domain.fleet.repository.FleetEventRepository;
import com.eve.own.auth.backend.esi.EsiService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/fleets")
public class FleetController {

    private final FleetEventRepository fleetRepo;
    private final FleetAttendanceRepository attendanceRepo;
    private final CharacterRepository characterRepo;
    private final EsiService esiService;
    private final AuthService authService;
    private final InvTypeRepository invTypeRepo;

    public FleetController(FleetEventRepository fleetRepo, FleetAttendanceRepository attendanceRepo,
                           CharacterRepository characterRepo, EsiService esiService,
                           AuthService authService, InvTypeRepository invTypeRepo) {
        this.fleetRepo = fleetRepo;
        this.attendanceRepo = attendanceRepo;
        this.characterRepo = characterRepo;
        this.esiService = esiService;
        this.authService = authService;
        this.invTypeRepo = invTypeRepo;
    }

    public record CreateFleetDto(String fleetName, String doctrine, Integer linkExpiryMinutes, String trackingType) {} // NEU: trackingType

    // ==========================================
    // LESE-ENDPUNKTE FÜR ALLE MEMBER
    // ==========================================
    @GetMapping("/recent")
    public ResponseEntity<List<FleetEvent>> getRecentFleets() {
        Instant startOfMonth = java.time.YearMonth.now().atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        List<FleetEvent> recent = fleetRepo.findByStartTimeAfterOrderByStartTimeDesc(startOfMonth).stream()
                .limit(10)
                .toList();

        // Lazy-Close: Wenn das Frontend anfragt, checken wir live, ob LINK-FATS abgelaufen sind
        for (FleetEvent event : recent) {
            if (event.getEndTime() == null && "LINK".equals(event.getTrackingType()) && event.getLinkExpiryTime() != null) {
                if (Instant.now().isAfter(event.getLinkExpiryTime())) {
                    event.setEndTime(event.getLinkExpiryTime());
                    fleetRepo.save(event);
                }
            }
        }
        return ResponseEntity.ok(recent);
    }

    @GetMapping("/{eventId}/attendance")
    public ResponseEntity<List<FleetAttendance>> getFleetAttendance(@PathVariable Long eventId) {
        return ResponseEntity.ok(attendanceRepo.findByFleetEventId(eventId));
    }

    // ==========================================
    // FC-ONLY: ERSTELLEN & VERWALTEN
    // ==========================================
    @PreAuthorize("hasAnyRole('ROLE_DIRECTOR', 'ROLE_FC', 'ROLE_A38')")
    @PostMapping("/create")
    public ResponseEntity<?> createFleet(@RequestBody CreateFleetDto dto) {
        Long fcId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();
        Character fc = characterRepo.findById(fcId).orElseThrow();

        String type = dto.trackingType() != null ? dto.trackingType() : "LIVE";

        // =========================================================
        // NEU: ESI-Check! Ist der FC wirklich in einer Ingame-Flotte?
        // =========================================================
        if ("LIVE".equals(type)) {
            try {
                String token = authService.getValidAccessToken(fc);
                var fleetInfoResp = esiService.getCharacterFleet(fc.getId(), token);

                if (fleetInfoResp.data() == null) {
                    return ResponseEntity.badRequest().body(java.util.Map.of("message", "Du bist ingame in keiner Flotte!"));
                }
            } catch (HttpClientErrorException.NotFound e) {
                return ResponseEntity.badRequest().body(java.util.Map.of("message", "Du bist ingame in keiner Flotte! Bitte erstelle zuerst eine in EVE."));
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(java.util.Map.of("message", "Fehler bei der ESI-Prüfung: " + e.getMessage()));
            }
        }
        // =========================================================

        FleetEvent newFleet = new FleetEvent();
        newFleet.setFcCharacterId(fc.getId());
        newFleet.setFcCharacterName(fc.getName());
        newFleet.setFleetName(dto.fleetName());
        newFleet.setDoctrine(dto.doctrine());
        newFleet.setStartTime(Instant.now());
        newFleet.setTrackingType(type);

        if ("LINK".equals(type)) {
            newFleet.setTrackingCode(java.util.UUID.randomUUID().toString());
            int expiryMins = (dto.linkExpiryMinutes() != null && dto.linkExpiryMinutes() > 0) ? dto.linkExpiryMinutes() : 60;
            newFleet.setLinkExpiryTime(Instant.now().plus(expiryMins, ChronoUnit.MINUTES));
        }

        return ResponseEntity.ok(fleetRepo.save(newFleet));
    }

    @PreAuthorize("hasAnyRole('ROLE_DIRECTOR', 'ROLE_FC', 'ROLE_A38')")
    @PostMapping("/{eventId}/close")
    public ResponseEntity<?> closeFleet(@PathVariable Long eventId) {
        Long fcId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();
        FleetEvent event = fleetRepo.findById(eventId).orElseThrow(() -> new RuntimeException("Flotte nicht gefunden"));

        if (!event.getFcCharacterId().equals(fcId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(java.util.Map.of("message", "Nur der FC kann diesen FAT beenden!"));
        }

        event.setEndTime(Instant.now());
        fleetRepo.save(event);
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("hasAnyRole('ROLE_DIRECTOR', 'ROLE_FC', 'ROLE_A38')")
    @PostMapping("/{eventId}/sync-esi")
    public ResponseEntity<?> syncFleetViaEsi(@PathVariable Long eventId) {
        Long fcId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();
        assert fcId != null;
        Character fc = characterRepo.findById(fcId).orElseThrow();
        FleetEvent event = fleetRepo.findById(eventId).orElseThrow();
        String token = authService.getValidAccessToken(fc);

        try {
            // 1. Anti-Cheat & Auto-Close Check: Ist der FC online?
            var onlineResp = esiService.getCharacterOnlineStatus(fc.getId(), token);
            if (onlineResp.data() == null || !Boolean.TRUE.equals(onlineResp.data().online())) {
                event.setEndTime(Instant.now());
                fleetRepo.save(event);
                return ResponseEntity.badRequest().body(java.util.Map.of("message", "Du bist offline! Der LIVE FAT wurde automatisch beendet."));
            }

            var fleetInfoResp = esiService.getCharacterFleet(fc.getId(), token);
            if (fleetInfoResp.data() == null) {
                return ResponseEntity.badRequest().body(java.util.Map.of("message", "Du bist laut ESI in keiner Flotte!"));
            }

            // ... (Hier bleibt dein bestehender Code-Teil zum Speichern der Member exakt gleich[cite: 1]) ...
            Long fleetId = fleetInfoResp.data().fleet_id();
            var membersResp = esiService.getFleetMembers(fleetId, token);
            if (membersResp.data() == null) return ResponseEntity.ok(0);
            int newlyAdded = 0;
            for (var m : membersResp.data()) {
                FleetAttendance att = attendanceRepo.findByFleetEventIdAndCharacterId(event.getId(), m.character_id()).orElse(new FleetAttendance());
                if (att.getId() == null) {
                    att.setFleetEventId(event.getId());
                    att.setCharacterId(m.character_id());
                    att.setJoinTime(m.join_time());
                    characterRepo.findById(m.character_id()).ifPresentOrElse(knownChar -> att.setCharacterName(knownChar.getName()), () -> att.setCharacterName("Unknown Pilot " + m.character_id()));
                    newlyAdded++;
                }
                if (m.ship_type_id() != null && (!m.ship_type_id().equals(att.getShipTypeId()) || att.getShipName() == null)) {
                    att.setShipTypeId(m.ship_type_id());
                    invTypeRepo.findById(m.ship_type_id()).ifPresentOrElse(type -> att.setShipName(type.getTypeName()), () -> att.setShipName("Unknown Ship (" + m.ship_type_id() + ")"));
                }
                attendanceRepo.save(att);
            }
            return ResponseEntity.ok(newlyAdded);

        } catch (HttpClientErrorException.NotFound e) {

            event.setEndTime(Instant.now());
            fleetRepo.save(event);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(java.util.Map.of("message", "Du bist in keiner Ingame-Flotte mehr! FAT wurde beendet."));
        } catch (HttpClientErrorException.Forbidden | HttpClientErrorException.Unauthorized e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(java.util.Map.of("message", "Dir fehlen die Flotten-Rechte."));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(java.util.Map.of("message", "Fehler beim ESI Sync: " + e.getMessage()));
        }
    }

    @PostMapping("/join/{trackingCode}")
    public ResponseEntity<?> joinFleetViaLink(@PathVariable String trackingCode) {
        Long charId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();
        assert charId != null;
        Character c = characterRepo.findById(charId).orElseThrow();

        FleetEvent event = fleetRepo.findByTrackingCode(trackingCode).orElse(null);
        if (event == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "FAT Link existiert nicht."));
        }

        if (event.getLinkExpiryTime() != null && Instant.now().isAfter(event.getLinkExpiryTime())) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "Dieser FAT-Link ist abgelaufen und nicht mehr gültig!"));
        }

        if (event.getEndTime() != null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "Zu spät! Der FC hat diesen FAT bereits geschlossen."));
        }

        // =======================================================
        // Anti-Cheat - Ist der Pilot im Spiel online?
        // =======================================================
        try {
            String token = authService.getValidAccessToken(c);
            var onlineResp = esiService.getCharacterOnlineStatus(c.getId(), token);

            if (onlineResp.data() == null || !Boolean.TRUE.equals(onlineResp.data().online())) {

                return ResponseEntity.badRequest().body(java.util.Map.of("message", "Anti-Cheat: Du bist aktuell nicht im Spiel online! Bitte logge dich erst in EVE ein."));
            }
        } catch (HttpClientErrorException.Forbidden | HttpClientErrorException.Unauthorized e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(java.util.Map.of("message", "Fehlende ESI-Rechte! Logge dich einmal im Tool neu ein, um die Online-Rechte zu gewähren."));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(java.util.Map.of("message", "Konnte Online-Status nicht prüfen: " + e.getMessage()));
        }
        // =======================================================

        if (!attendanceRepo.existsByFleetEventIdAndCharacterId(event.getId(), charId)) {
            FleetAttendance att = new FleetAttendance();
            att.setFleetEventId(event.getId());
            att.setCharacterId(charId);
            att.setCharacterName(c.getName());
            att.setJoinTime(Instant.now());
            attendanceRepo.save(att);
        }

        return ResponseEntity.ok().build();
    }
}