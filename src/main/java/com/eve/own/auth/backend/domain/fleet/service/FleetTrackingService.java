package com.eve.own.auth.backend.domain.fleet.service;

import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.domain.fleet.TrackingType;
import com.eve.own.auth.backend.domain.fleet.entity.FleetAttendance;
import com.eve.own.auth.backend.domain.fleet.entity.FleetEvent;
import com.eve.own.auth.backend.domain.fleet.repository.FleetAttendanceRepository;
import com.eve.own.auth.backend.domain.fleet.repository.FleetEventRepository;
import com.eve.own.auth.backend.esi.EsiAccessDeniedException;
import com.eve.own.auth.backend.esi.EsiService;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Erfassung der Flottenteilnahme ("FATs").
 *
 * <p>Zwei Wege fuehren zu einem Eintrag: bei {@link TrackingType#LIVE} liest die
 * Anwendung die Ingame-Flotte des FC aus, bei {@link TrackingType#LINK} tragen
 * sich die Teilnehmer ueber einen zeitlich begrenzten Link selbst ein.</p>
 *
 * <p>Beide Wege pruefen den Online-Status ueber ESI. Ohne diese Pruefung liesse
 * sich ein FAT von aussen mitnehmen, ohne ueberhaupt eingeloggt zu sein.</p>
 */
@Slf4j
@Service
public class FleetTrackingService {

    /** So viele der juengsten Flotten zeigt die Uebersicht. */
    private static final int RECENT_LIMIT = 10;

    /** Gueltigkeit eines Teilnahme-Links, wenn der FC nichts angibt. */
    private static final int DEFAULT_LINK_EXPIRY_MINUTES = 60;

    private final FleetEventRepository fleetRepo;
    private final FleetAttendanceRepository attendanceRepo;
    private final CharacterRepository characterRepo;
    private final InvTypeRepository invTypeRepo;
    private final EsiService esiService;
    private final AuthService authService;

    public FleetTrackingService(FleetEventRepository fleetRepo,
                                FleetAttendanceRepository attendanceRepo,
                                CharacterRepository characterRepo,
                                InvTypeRepository invTypeRepo,
                                EsiService esiService,
                                AuthService authService) {
        this.fleetRepo = fleetRepo;
        this.attendanceRepo = attendanceRepo;
        this.characterRepo = characterRepo;
        this.invTypeRepo = invTypeRepo;
        this.esiService = esiService;
        this.authService = authService;
    }

    /** Angaben des FC beim Anlegen einer Flotte. */
    public record CreateFleetCommand(String fleetName, String doctrine,
                                     Integer linkExpiryMinutes, String trackingType) {}

    // ==================================================================
    // Lesen
    // ==================================================================

    /**
     * Die juengsten Flotten des laufenden Monats.
     *
     * <p>Abgelaufene Link-FATs werden dabei nachtraeglich geschlossen. Das
     * geschieht bewusst beim Lesen und nicht ueber einen eigenen Zeitgeber: der
     * Zeitpunkt des Schliessens steht ohnehin fest, es fehlt nur der Eintrag.</p>
     */
    @Transactional
    public List<FleetEvent> recentFleets() {
        Instant startOfMonth = YearMonth.now(ZoneOffset.UTC)
                .atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<FleetEvent> recent = fleetRepo.findByStartTimeAfterOrderByStartTimeDesc(startOfMonth).stream()
                .limit(RECENT_LIMIT)
                .toList();

        recent.forEach(this::closeIfLinkExpired);
        return recent;
    }

    @Transactional(readOnly = true)
    public List<FleetAttendance> attendance(Long eventId) {
        return attendanceRepo.findByFleetEventId(eventId);
    }

    // ==================================================================
    // Verwalten
    // ==================================================================

    /**
     * Legt eine Flotte an.
     *
     * @throws IllegalArgumentException wenn der FC bei einem LIVE-FAT ingame in keiner Flotte ist
     */
    @Transactional
    public FleetEvent createFleet(Long fcCharacterId, CreateFleetCommand command) {
        Character fc = requireCharacter(fcCharacterId);
        TrackingType trackingType = TrackingType.of(command.trackingType());

        if (trackingType == TrackingType.LIVE) {
            requireIngameFleet(fc);
        }

        FleetEvent fleet = new FleetEvent();
        fleet.setFcCharacterId(fc.getId());
        fleet.setFcCharacterName(fc.getName());
        fleet.setFleetName(command.fleetName());
        fleet.setDoctrine(command.doctrine());
        fleet.setStartTime(Instant.now());
        fleet.setTrackingType(trackingType.dbValue());

        if (trackingType == TrackingType.LINK) {
            fleet.setTrackingCode(UUID.randomUUID().toString());
            fleet.setLinkExpiryTime(Instant.now().plus(expiryMinutes(command), ChronoUnit.MINUTES));
        }
        return fleetRepo.save(fleet);
    }

    /** Beendet eine Flotte. Nur der FC, der sie eroeffnet hat, darf das. */
    @Transactional
    public void closeFleet(Long fcCharacterId, Long eventId) {
        FleetEvent fleet = requireFleet(eventId);
        if (!fleet.getFcCharacterId().equals(fcCharacterId)) {
            throw new AccessDeniedException("Nur der FC kann diesen FAT beenden.");
        }
        fleet.setEndTime(Instant.now());
        fleetRepo.save(fleet);
    }

    /**
     * Uebernimmt die aktuelle Ingame-Flotte des FC in die Anwesenheitsliste.
     *
     * <p>Ist der FC nicht mehr online oder in keiner Flotte mehr, wird der FAT
     * geschlossen - er koennte sonst unbegrenzt offen bleiben.</p>
     *
     * @return wie viele Teilnehmer neu hinzugekommen sind
     */
    @Transactional
    public int syncViaEsi(Long fcCharacterId, Long eventId) {
        Character fc = requireCharacter(fcCharacterId);
        FleetEvent fleet = requireFleet(eventId);
        String token = authService.getValidAccessToken(fc);

        try {
            if (!isOnline(fc, token)) {
                closeNow(fleet);
                throw new IllegalArgumentException("Du bist offline. Der LIVE-FAT wurde beendet.");
            }

            var fleetInfo = esiService.getCharacterFleet(fc.getId(), token);
            if (fleetInfo.data() == null) {
                throw new IllegalArgumentException("Du bist laut ESI in keiner Flotte.");
            }

            var members = esiService.getFleetMembers(fleetInfo.data().fleet_id(), token);
            if (members.data() == null) {
                return 0;
            }

            int newlyAdded = 0;
            for (EsiService.EsiFleetMemberResponse member : members.data()) {
                if (recordAttendance(fleet, member)) {
                    newlyAdded++;
                }
            }
            return newlyAdded;

        } catch (HttpClientErrorException.NotFound e) {
            closeNow(fleet);
            throw new IllegalArgumentException("Du bist in keiner Ingame-Flotte mehr. Der FAT wurde beendet.");
        } catch (HttpClientErrorException.Forbidden | HttpClientErrorException.Unauthorized e) {
            throw new EsiAccessDeniedException("Dir fehlen die Flotten-Rechte.");
        }
    }

    /**
     * Traegt einen Teilnehmer ueber seinen Link ein.
     *
     * @throws IllegalArgumentException wenn der Link unbekannt, abgelaufen oder der FAT geschlossen ist
     */
    @Transactional
    public void joinViaLink(Long characterId, String trackingCode) {
        Character character = requireCharacter(characterId);
        FleetEvent fleet = fleetRepo.findByTrackingCode(trackingCode).orElseThrow(
                () -> new IllegalArgumentException("Dieser FAT-Link existiert nicht."));

        if (fleet.getLinkExpiryTime() != null && Instant.now().isAfter(fleet.getLinkExpiryTime())) {
            throw new IllegalArgumentException("Dieser FAT-Link ist abgelaufen.");
        }
        if (fleet.getEndTime() != null) {
            throw new IllegalArgumentException("Zu spaet - der FC hat diesen FAT bereits geschlossen.");
        }
        requireOnline(character);

        if (attendanceRepo.existsByFleetEventIdAndCharacterId(fleet.getId(), characterId)) {
            return;
        }
        FleetAttendance attendance = new FleetAttendance();
        attendance.setFleetEventId(fleet.getId());
        attendance.setCharacterId(characterId);
        attendance.setCharacterName(character.getName());
        attendance.setJoinTime(Instant.now());
        attendanceRepo.save(attendance);
    }

    // ==================================================================
    // Interna
    // ==================================================================

    /** @return true, wenn der Teilnehmer neu ist */
    private boolean recordAttendance(FleetEvent fleet, EsiService.EsiFleetMemberResponse member) {
        FleetAttendance attendance = attendanceRepo
                .findByFleetEventIdAndCharacterId(fleet.getId(), member.character_id())
                .orElseGet(FleetAttendance::new);
        boolean isNew = attendance.getId() == null;

        if (isNew) {
            attendance.setFleetEventId(fleet.getId());
            attendance.setCharacterId(member.character_id());
            attendance.setJoinTime(member.join_time());
            attendance.setCharacterName(characterRepo.findById(member.character_id())
                    .map(Character::getName)
                    .orElse("Unknown Pilot " + member.character_id()));
        }

        // Auch bei bekannten Teilnehmern nachfuehren: wer das Schiff wechselt,
        // soll mit dem zuletzt geflogenen in der Liste stehen.
        boolean shipChanged = member.ship_type_id() != null
                && (!member.ship_type_id().equals(attendance.getShipTypeId())
                    || attendance.getShipName() == null);
        if (shipChanged) {
            attendance.setShipTypeId(member.ship_type_id());
            attendance.setShipName(invTypeRepo.findById(member.ship_type_id())
                    .map(type -> type.getTypeName())
                    .orElse("Unknown Ship (" + member.ship_type_id() + ")"));
        }

        attendanceRepo.save(attendance);
        return isNew;
    }

    private void closeIfLinkExpired(FleetEvent fleet) {
        boolean expired = fleet.getEndTime() == null
                && TrackingType.LINK.matches(fleet.getTrackingType())
                && fleet.getLinkExpiryTime() != null
                && Instant.now().isAfter(fleet.getLinkExpiryTime());
        if (expired) {
            fleet.setEndTime(fleet.getLinkExpiryTime());
            fleetRepo.save(fleet);
        }
    }

    private void closeNow(FleetEvent fleet) {
        fleet.setEndTime(Instant.now());
        fleetRepo.save(fleet);
    }

    private void requireIngameFleet(Character fc) {
        try {
            String token = authService.getValidAccessToken(fc);
            if (esiService.getCharacterFleet(fc.getId(), token).data() == null) {
                throw new IllegalArgumentException("Du bist ingame in keiner Flotte.");
            }
        } catch (HttpClientErrorException.NotFound e) {
            throw new IllegalArgumentException(
                    "Du bist ingame in keiner Flotte. Bitte erstelle zuerst eine in EVE.");
        }
    }

    private void requireOnline(Character character) {
        try {
            if (!isOnline(character, authService.getValidAccessToken(character))) {
                throw new IllegalArgumentException(
                        "Du bist aktuell nicht im Spiel online. Bitte logge dich erst in EVE ein.");
            }
        } catch (HttpClientErrorException.Forbidden | HttpClientErrorException.Unauthorized e) {
            throw new EsiAccessDeniedException(
                    "Es fehlen ESI-Rechte. Melde dich einmal neu an, um den Online-Status freizugeben.");
        }
    }

    private boolean isOnline(Character character, String token) {
        var online = esiService.getCharacterOnlineStatus(character.getId(), token);
        return online.data() != null && Boolean.TRUE.equals(online.data().online());
    }

    private static int expiryMinutes(CreateFleetCommand command) {
        Integer minutes = command.linkExpiryMinutes();
        return minutes != null && minutes > 0 ? minutes : DEFAULT_LINK_EXPIRY_MINUTES;
    }

    private Character requireCharacter(Long characterId) {
        return characterRepo.findById(characterId).orElseThrow(
                () -> new IllegalArgumentException("Charakter " + characterId + " ist unbekannt."));
    }

    private FleetEvent requireFleet(Long eventId) {
        return fleetRepo.findById(eventId).orElseThrow(
                () -> new IllegalArgumentException("Flotte " + eventId + " ist unbekannt."));
    }
}
