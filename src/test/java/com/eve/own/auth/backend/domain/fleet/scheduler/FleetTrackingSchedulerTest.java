package com.eve.own.auth.backend.domain.fleet.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.eve.entity.InvType;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.domain.fleet.TrackingType;
import com.eve.own.auth.backend.domain.fleet.entity.FleetAttendance;
import com.eve.own.auth.backend.domain.fleet.entity.FleetEvent;
import com.eve.own.auth.backend.domain.fleet.repository.FleetAttendanceRepository;
import com.eve.own.auth.backend.domain.fleet.repository.FleetEventRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Der Auto-Tracker haelt laufende FATs aktuell, ohne dass ein FC etwas druecken
 * muss - und beendet sie, sobald die Grundlage wegfaellt.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Automatische Flottenverfolgung")
class FleetTrackingSchedulerTest {

    private static final Long FC_ID = 1000L;
    private static final Long PILOT_ID = 2000L;
    private static final Long EVENT_ID = 55L;

    @Mock private FleetEventRepository fleetRepo;
    @Mock private FleetAttendanceRepository attendanceRepo;
    @Mock private CharacterRepository characterRepo;
    @Mock private EsiService esiService;
    @Mock private AuthService authService;
    @Mock private InvTypeRepository invTypeRepo;

    private FleetTrackingScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new FleetTrackingScheduler(fleetRepo, attendanceRepo, characterRepo,
                esiService, authService, invTypeRepo);

        Character fc = new Character();
        fc.setId(FC_ID);
        fc.setName("Flottenchef");
        when(characterRepo.findById(FC_ID)).thenReturn(Optional.of(fc));
        when(characterRepo.findById(PILOT_ID)).thenReturn(Optional.empty());
        when(authService.getValidAccessToken(any())).thenReturn("token");
        when(fleetRepo.findByEndTimeIsNull()).thenReturn(List.of());
        when(attendanceRepo.findByFleetEventIdAndCharacterId(anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        when(attendanceRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        onlineStatus(true);
        inFleet(true);
        when(esiService.getFleetMembers(anyLong(), anyString())).thenReturn(EsiResponse.empty());
    }

    private static FleetEvent fleet(TrackingType type) {
        FleetEvent fleet = new FleetEvent();
        fleet.setId(EVENT_ID);
        fleet.setFleetName("Roam");
        fleet.setFcCharacterId(FC_ID);
        fleet.setTrackingType(type.dbValue());
        return fleet;
    }

    private void onlineStatus(boolean online) {
        when(esiService.getCharacterOnlineStatus(anyLong(), anyString())).thenReturn(
                EsiResponse.changed(new EsiService.EsiOnlineResponse(online, null, null, 1), null, null));
    }

    private void inFleet(boolean inFleet) {
        when(esiService.getCharacterFleet(anyLong(), anyString())).thenReturn(inFleet
                ? EsiResponse.changed(
                        new EsiService.EsiCharacterFleetResponse(777L, FC_ID, "fleet_commander"), null, null)
                : EsiResponse.empty());
    }

    @Test
    @DisplayName("beendet einen abgelaufenen Link-FAT zum Ablaufzeitpunkt")
    void closesExpiredLinkFleet() {
        FleetEvent expired = fleet(TrackingType.LINK);
        Instant expiry = Instant.now().minus(1, ChronoUnit.HOURS);
        expired.setLinkExpiryTime(expiry);
        when(fleetRepo.findByEndTimeIsNull()).thenReturn(List.of(expired));

        scheduler.trackActiveFleets();

        assertThat(expired.getEndTime()).isEqualTo(expiry);
        verify(fleetRepo).save(expired);
    }

    @Test
    @DisplayName("laesst einen noch laufenden Link-FAT offen")
    void keepsRunningLinkFleetOpen() {
        FleetEvent running = fleet(TrackingType.LINK);
        running.setLinkExpiryTime(Instant.now().plus(1, ChronoUnit.HOURS));
        when(fleetRepo.findByEndTimeIsNull()).thenReturn(List.of(running));

        scheduler.trackActiveFleets();

        assertThat(running.getEndTime()).isNull();
        verify(esiService, never()).getCharacterOnlineStatus(anyLong(), anyString());
    }

    @Test
    @DisplayName("beendet einen LIVE-FAT, sobald der FC offline geht")
    void closesLiveFleetWhenFcGoesOffline() {
        FleetEvent live = fleet(TrackingType.LIVE);
        when(fleetRepo.findByEndTimeIsNull()).thenReturn(List.of(live));
        onlineStatus(false);

        scheduler.trackActiveFleets();

        assertThat(live.getEndTime()).isNotNull();
        verify(fleetRepo).save(live);
    }

    @Test
    @DisplayName("traegt neue Flottenmitglieder samt Schiff ein")
    void recordsNewMembers() {
        when(fleetRepo.findByEndTimeIsNull()).thenReturn(List.of(fleet(TrackingType.LIVE)));
        when(esiService.getFleetMembers(anyLong(), anyString())).thenReturn(EsiResponse.changed(
                new EsiService.EsiFleetMemberResponse[]{new EsiService.EsiFleetMemberResponse(
                        PILOT_ID, Instant.now(), "squad_member", 587L, 30000142L)}, null, null));
        InvType rifter = new InvType();
        rifter.setTypeName("Rifter");
        when(invTypeRepo.findById(587L)).thenReturn(Optional.of(rifter));

        scheduler.trackActiveFleets();

        ArgumentCaptor<FleetAttendance> saved = ArgumentCaptor.forClass(FleetAttendance.class);
        verify(attendanceRepo).save(saved.capture());
        assertThat(saved.getValue().getCharacterName()).isEqualTo("Unknown Pilot 2000");
        assertThat(saved.getValue().getShipName()).isEqualTo("Rifter");
    }

    @Test
    @DisplayName("beendet den FAT, wenn ESI die Flotte nicht mehr kennt")
    void closesFleetOnNotFound() {
        FleetEvent live = fleet(TrackingType.LIVE);
        when(fleetRepo.findByEndTimeIsNull()).thenReturn(List.of(live));
        when(esiService.getCharacterOnlineStatus(anyLong(), anyString())).thenThrow(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                        HttpHeaders.EMPTY, null, null));

        scheduler.trackActiveFleets();

        assertThat(live.getEndTime()).isNotNull();
    }

    @Test
    @DisplayName("laesst einen Fehler bei einer Flotte die uebrigen nicht aufhalten")
    void oneFailureDoesNotStopTheRest() {
        FleetEvent broken = fleet(TrackingType.LIVE);
        FleetEvent healthy = fleet(TrackingType.LINK);
        healthy.setLinkExpiryTime(Instant.now().minus(1, ChronoUnit.HOURS));
        when(fleetRepo.findByEndTimeIsNull()).thenReturn(List.of(broken, healthy));
        when(esiService.getCharacterOnlineStatus(anyLong(), anyString()))
                .thenThrow(new RuntimeException("kaputt"));

        scheduler.trackActiveFleets();

        assertThat(healthy.getEndTime()).isNotNull();
    }

    @Test
    @DisplayName("ueberspringt eine Flotte, deren FC nicht mehr existiert")
    void skipsFleetWithoutFc() {
        FleetEvent orphaned = fleet(TrackingType.LIVE);
        orphaned.setFcCharacterId(99999L);
        when(fleetRepo.findByEndTimeIsNull()).thenReturn(List.of(orphaned));
        when(characterRepo.findById(99999L)).thenReturn(Optional.empty());

        scheduler.trackActiveFleets();

        verify(esiService, never()).getCharacterOnlineStatus(anyLong(), anyString());
    }

    @Test
    @DisplayName("tut nichts, wenn keine Flotte laeuft")
    void doesNothingWithoutActiveFleets() {
        scheduler.trackActiveFleets();

        verify(esiService, never()).getCharacterOnlineStatus(anyLong(), anyString());
    }

    @Test
    @DisplayName("laesst den FAT offen, wenn der FC gerade keine Ingame-Flotte hat")
    void keepsFleetOpenWithoutIngameFleet() {
        FleetEvent live = fleet(TrackingType.LIVE);
        when(fleetRepo.findByEndTimeIsNull()).thenReturn(List.of(live));
        inFleet(false);

        scheduler.trackActiveFleets();

        assertThat(live.getEndTime()).isNull();
        verify(attendanceRepo, never()).save(any());
    }
}
