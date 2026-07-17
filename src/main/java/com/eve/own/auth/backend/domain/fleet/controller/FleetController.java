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

    public record CreateFleetDto(String fleetName, String doctrine, Integer linkExpiryMinutes) {}

    // ==========================================
    // LESE-ENDPUNKTE FÜR ALLE MEMBER
    // ==========================================
    @GetMapping("/recent") // <--- Name geändert
    public ResponseEntity<List<FleetEvent>> getRecentFleets() {
        Instant yesterday = Instant.now().minus(24, ChronoUnit.HOURS);
        return ResponseEntity.ok(fleetRepo.findByStartTimeAfterOrderByStartTimeDesc(yesterday));
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
    public ResponseEntity<FleetEvent> createFleet(@RequestBody CreateFleetDto dto) {
            Long fcId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();
            assert fcId != null;
            Character fc = characterRepo.findById(fcId).orElseThrow();

            FleetEvent newFleet = new FleetEvent();
            newFleet.setFcCharacterId(fc.getId());
            newFleet.setFcCharacterName(fc.getName());
            newFleet.setFleetName(dto.fleetName());
            newFleet.setDoctrine(dto.doctrine());
            newFleet.setStartTime(Instant.now());

            int expiryMins = (dto.linkExpiryMinutes() != null && dto.linkExpiryMinutes() > 0) ? dto.linkExpiryMinutes() : 60;
            newFleet.setLinkExpiryTime(Instant.now().plus(expiryMins, ChronoUnit.MINUTES));

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
        Long fcId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Character fc = characterRepo.findById(fcId).orElseThrow();
        FleetEvent event = fleetRepo.findById(eventId).orElseThrow();
        String token = authService.getValidAccessToken(fc);

        try {
            var fleetInfoResp = esiService.getCharacterFleet(fc.getId(), token);
            if (fleetInfoResp.data() == null) {
                return ResponseEntity.badRequest().body(java.util.Map.of("message", "Du bist laut ESI in keiner Flotte!"));
            }
            Long fleetId = fleetInfoResp.data().fleet_id();
            var membersResp = esiService.getFleetMembers(fleetId, token);
            if (membersResp.data() == null) return ResponseEntity.ok(0);

            int newlyAdded = 0;
            for (var m : membersResp.data()) {
                // Hole den Piloten ODER erstelle ein neues leeres Objekt, falls er noch nicht existiert
                FleetAttendance att = attendanceRepo.findByFleetEventIdAndCharacterId(event.getId(), m.character_id())
                        .orElse(new FleetAttendance());

                if (att.getId() == null) {
                    // Es ist ein komplett neuer Pilot
                    att.setFleetEventId(event.getId());
                    att.setCharacterId(m.character_id());
                    att.setJoinTime(m.join_time());

                    characterRepo.findById(m.character_id()).ifPresentOrElse(
                            knownChar -> att.setCharacterName(knownChar.getName()),
                            () -> att.setCharacterName("Unknown Pilot " + m.character_id())
                    );
                    newlyAdded++;
                }

                // SCHIFF-UPDATE: Reshipping erkennen oder Namen nachtragen, falls er null ist
                if (m.ship_type_id() != null && (!m.ship_type_id().equals(att.getShipTypeId()) || att.getShipName() == null)) {
                    att.setShipTypeId(m.ship_type_id());
                    invTypeRepo.findById(m.ship_type_id()).ifPresentOrElse(
                            type -> att.setShipName(type.getTypeName()),
                            () -> att.setShipName("Unknown Ship (" + m.ship_type_id() + ")")
                    );
                }

                attendanceRepo.save(att);
            }
            return ResponseEntity.ok(newlyAdded);
        } catch (HttpClientErrorException.NotFound e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(java.util.Map.of("message", "Du bist aktuell in keiner Ingame-Flotte!"));
        } catch (HttpClientErrorException.Forbidden | HttpClientErrorException.Unauthorized e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(java.util.Map.of("message", "Dir fehlen die Flotten-Rechte."));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(java.util.Map.of("message", "Fehler beim ESI Sync: " + e.getMessage()));
        }
    }

    // ==========================================
    // ALLGEMEIN: FAT-Link beitreten
    // ==========================================
    @PostMapping("/join/{trackingCode}")
    public ResponseEntity<Void> joinFleetViaLink(@PathVariable String trackingCode) {
        Long charId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();
        assert charId != null;
        Character c = characterRepo.findById(charId).orElseThrow();

        FleetEvent event = fleetRepo.findByTrackingCode(trackingCode)
                .orElseThrow(() -> new RuntimeException("FAT Link existiert nicht."));

        if (event.getLinkExpiryTime() != null && Instant.now().isAfter(event.getLinkExpiryTime())) {
            throw new RuntimeException("Dieser FAT-Link ist abgelaufen und nicht mehr gültig!");
        }

        if (attendanceRepo.existsByFleetEventIdAndCharacterId(event.getId(), charId)) {
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