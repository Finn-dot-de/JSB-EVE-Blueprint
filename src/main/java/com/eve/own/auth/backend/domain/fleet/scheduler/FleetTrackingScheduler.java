package com.eve.own.auth.backend.domain.fleet.scheduler;

import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.domain.fleet.entity.FleetAttendance;
import com.eve.own.auth.backend.domain.fleet.entity.FleetEvent;
import com.eve.own.auth.backend.domain.fleet.repository.FleetAttendanceRepository;
import com.eve.own.auth.backend.domain.fleet.repository.FleetEventRepository;
import com.eve.own.auth.backend.esi.EsiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
public class FleetTrackingScheduler {

    private final FleetEventRepository fleetRepo;
    private final FleetAttendanceRepository attendanceRepo;
    private final CharacterRepository characterRepo;
    private final EsiService esiService;
    private final AuthService authService;
    private final InvTypeRepository invTypeRepo; // NEU

    public FleetTrackingScheduler(FleetEventRepository fleetRepo, FleetAttendanceRepository attendanceRepo,
                                  CharacterRepository characterRepo, EsiService esiService,
                                  AuthService authService, InvTypeRepository invTypeRepo) {
        this.fleetRepo = fleetRepo;
        this.attendanceRepo = attendanceRepo;
        this.characterRepo = characterRepo;
        this.esiService = esiService;
        this.authService = authService;
        this.invTypeRepo = invTypeRepo;
    }

    @Scheduled(fixedRate = 60000)
    public void trackActiveFleets() {
        List<FleetEvent> activeFleets = fleetRepo.findByEndTimeIsNull();

        if (!activeFleets.isEmpty()) {
            log.info("Auto-Tracker prüft {} aktive Flotten...", activeFleets.size());
        }

        for (FleetEvent event : activeFleets) {
            try {
                Character fc = characterRepo.findById(event.getFcCharacterId()).orElse(null);
                if (fc == null) continue;

                String token = authService.getValidAccessToken(fc);

                var fleetInfoResp = esiService.getCharacterFleet(fc.getId(), token);
                if (fleetInfoResp.data() == null) continue;

                Long fleetId = fleetInfoResp.data().fleet_id();
                var membersResp = esiService.getFleetMembers(fleetId, token);

                if (membersResp.data() != null) {
                    for (var m : membersResp.data()) {
                        FleetAttendance att = attendanceRepo.findByFleetEventIdAndCharacterId(event.getId(), m.character_id())
                                .orElse(new FleetAttendance());

                        if (att.getId() == null) {
                            att.setFleetEventId(event.getId());
                            att.setCharacterId(m.character_id());
                            att.setJoinTime(m.join_time());

                            characterRepo.findById(m.character_id()).ifPresentOrElse(
                                    knownChar -> att.setCharacterName(knownChar.getName()),
                                    () -> att.setCharacterName("Unknown Pilot " + m.character_id())
                            );
                            log.info("Auto-Tracker: Neuer Pilot {} zur Flotte {} hinzugefügt.", att.getCharacterName(), event.getFleetName());
                        }

                        // Schiff aktualisieren, falls Reshipping stattfand oder Name noch fehlt
                        if (m.ship_type_id() != null && (!m.ship_type_id().equals(att.getShipTypeId()) || att.getShipName() == null)) {
                            att.setShipTypeId(m.ship_type_id());
                            invTypeRepo.findById(m.ship_type_id()).ifPresent(type -> att.setShipName(type.getTypeName()));
                        }

                        attendanceRepo.save(att);
                    }
                }

            } catch (HttpClientErrorException.NotFound e) {
                if (Instant.now().isAfter(event.getStartTime().plus(5, ChronoUnit.MINUTES))) {
                    event.setEndTime(Instant.now());
                    fleetRepo.save(event);
                    log.info("Auto-Tracker: FAT {} beendet, da der FC Ingame keine Flotte mehr hat.", event.getFleetName());
                }
            } catch (Exception e) {
                log.warn("Auto-Tracker Fehler für FAT {}: {}", event.getId(), e.getMessage());
            }
        }
    }
}