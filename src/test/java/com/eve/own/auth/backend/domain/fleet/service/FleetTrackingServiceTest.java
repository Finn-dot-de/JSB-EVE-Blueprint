package com.eve.own.auth.backend.domain.fleet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.eve.own.auth.backend.esi.EsiAccessDeniedException;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Erfassung der Flottenteilnahme")
class FleetTrackingServiceTest {

    private static final Long FC_ID = 1000L;
    private static final Long PILOT_ID = 2000L;
    private static final Long EVENT_ID = 55L;
    private static final Long FLEET_ID = 777L;
    private static final String TOKEN = "token";
    private static final String CODE = "abc-123";

    @Mock private FleetEventRepository fleetRepo;
    @Mock private FleetAttendanceRepository attendanceRepo;
    @Mock private CharacterRepository characterRepo;
    @Mock private InvTypeRepository invTypeRepo;
    @Mock private EsiService esiService;
    @Mock private AuthService authService;

    private FleetTrackingService service;

    @BeforeEach
    void setUp() {
        service = new FleetTrackingService(fleetRepo, attendanceRepo, characterRepo,
                invTypeRepo, esiService, authService);

        when(characterRepo.findById(FC_ID)).thenReturn(Optional.of(character(FC_ID, "Flottenchef")));
        when(characterRepo.findById(PILOT_ID)).thenReturn(Optional.of(character(PILOT_ID, "Pilotin")));
        when(authService.getValidAccessToken(any())).thenReturn(TOKEN);
        when(fleetRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(attendanceRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(attendanceRepo.findByFleetEventIdAndCharacterId(anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        isOnline(true);
    }

    private static Character character(Long id, String name) {
        Character character = new Character();
        character.setId(id);
        character.setName(name);
        character.setMainCharacterId(id);
        return character;
    }

    private static FleetEvent fleet(TrackingType type) {
        FleetEvent fleet = new FleetEvent();
        fleet.setId(EVENT_ID);
        fleet.setFcCharacterId(FC_ID);
        fleet.setTrackingType(type.dbValue());
        fleet.setStartTime(Instant.now());
        fleet.setTrackingCode(CODE);
        return fleet;
    }

    private void isOnline(boolean online) {
        when(esiService.getCharacterOnlineStatus(anyLong(), anyString())).thenReturn(
                EsiResponse.changed(new EsiService.EsiOnlineResponse(online, null, null, 1), null, null));
    }

    private void isInFleet(boolean inFleet) {
        when(esiService.getCharacterFleet(anyLong(), anyString())).thenReturn(inFleet
                ? EsiResponse.changed(
                        new EsiService.EsiCharacterFleetResponse(FLEET_ID, FC_ID, "fleet_commander"), null, null)
                : EsiResponse.empty());
    }

    private static HttpClientErrorException httpError(HttpStatus status) {
        return HttpClientErrorException.create(status, status.name(), HttpHeaders.EMPTY, null, null);
    }

    @Nested
    @DisplayName("Flotte anlegen")
    class Creating {

        @Test
        @DisplayName("legt einen LIVE-FAT an, wenn der FC ingame in einer Flotte ist")
        void createsLiveFleet() {
            isInFleet(true);

            FleetEvent created = service.createFleet(FC_ID,
                    new FleetTrackingService.CreateFleetCommand("Roam", "Doktrin", null, "LIVE"));

            assertThat(created.getTrackingType()).isEqualTo("LIVE");
            assertThat(created.getFcCharacterId()).isEqualTo(FC_ID);
            assertThat(created.getFcCharacterName()).isEqualTo("Flottenchef");
            assertThat(created.getTrackingCode()).isNull();
        }

        @Test
        @DisplayName("weist einen LIVE-FAT ab, wenn der FC ingame in keiner Flotte ist")
        void rejectsLiveFleetWithoutIngameFleet() {
            isInFleet(false);

            assertThatThrownBy(() -> service.createFleet(FC_ID,
                    new FleetTrackingService.CreateFleetCommand("Roam", null, null, "LIVE")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("keiner Flotte");
        }

        @Test
        @DisplayName("erklaert eine 404-Antwort von ESI als 'keine Flotte'")
        void translatesNotFoundToPlainMessage() {
            when(esiService.getCharacterFleet(anyLong(), anyString()))
                    .thenThrow(httpError(HttpStatus.NOT_FOUND));

            assertThatThrownBy(() -> service.createFleet(FC_ID,
                    new FleetTrackingService.CreateFleetCommand("Roam", null, null, "LIVE")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("erstelle zuerst eine in EVE");
        }

        @Test
        @DisplayName("vergibt fuer einen LINK-FAT einen Code mit Ablaufzeit")
        void createsLinkFleet() {
            FleetEvent created = service.createFleet(FC_ID,
                    new FleetTrackingService.CreateFleetCommand("Roam", null, 30, "LINK"));

            assertThat(created.getTrackingCode()).isNotBlank();
            assertThat(created.getLinkExpiryTime())
                    .isCloseTo(Instant.now().plus(30, ChronoUnit.MINUTES),
                            org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));
            verify(esiService, never()).getCharacterFleet(anyLong(), anyString());
        }

        @Test
        @DisplayName("nimmt ohne Angabe eine Stunde Gueltigkeit an")
        void defaultsLinkExpiry() {
            FleetEvent created = service.createFleet(FC_ID,
                    new FleetTrackingService.CreateFleetCommand("Roam", null, null, "LINK"));

            assertThat(created.getLinkExpiryTime())
                    .isCloseTo(Instant.now().plus(60, ChronoUnit.MINUTES),
                            org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));
        }

        @Test
        @DisplayName("nimmt bei fehlender Art die Vorgabe LIVE an")
        void defaultsTrackingType() {
            isInFleet(true);

            FleetEvent created = service.createFleet(FC_ID,
                    new FleetTrackingService.CreateFleetCommand("Roam", null, null, null));

            assertThat(created.getTrackingType()).isEqualTo("LIVE");
        }
    }

    @Nested
    @DisplayName("Flotte beenden")
    class Closing {

        @Test
        @DisplayName("beendet die eigene Flotte")
        void closesOwnFleet() {
            FleetEvent fleet = fleet(TrackingType.LIVE);
            when(fleetRepo.findById(EVENT_ID)).thenReturn(Optional.of(fleet));

            service.closeFleet(FC_ID, EVENT_ID);

            assertThat(fleet.getEndTime()).isNotNull();
        }

        @Test
        @DisplayName("laesst niemanden die Flotte eines anderen FC beenden")
        void rejectsForeignFleet() {
            when(fleetRepo.findById(EVENT_ID)).thenReturn(Optional.of(fleet(TrackingType.LIVE)));

            assertThatThrownBy(() -> service.closeFleet(PILOT_ID, EVENT_ID))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("weist eine unbekannte Flotte ab")
        void rejectsUnknownFleet() {
            when(fleetRepo.findById(EVENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.closeFleet(FC_ID, EVENT_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Uebernahme aus der Ingame-Flotte")
    class EsiSync {

        @BeforeEach
        void fleetExists() {
            when(fleetRepo.findById(EVENT_ID)).thenReturn(Optional.of(fleet(TrackingType.LIVE)));
            isInFleet(true);
        }

        private void fleetHasMembers(EsiService.EsiFleetMemberResponse... members) {
            when(esiService.getFleetMembers(anyLong(), anyString()))
                    .thenReturn(EsiResponse.changed(members, null, null));
        }

        private static EsiService.EsiFleetMemberResponse member(Long characterId, Long shipTypeId) {
            return new EsiService.EsiFleetMemberResponse(
                    characterId, Instant.now(), "squad_member", shipTypeId, 30000142L);
        }

        @Test
        @DisplayName("traegt neue Teilnehmer mit Namen und Schiff ein")
        void recordsNewMembers() {
            fleetHasMembers(member(PILOT_ID, 587L));
            InvType rifter = new InvType();
            rifter.setTypeId(587L);
            rifter.setTypeName("Rifter");
            when(invTypeRepo.findById(587L)).thenReturn(Optional.of(rifter));

            int added = service.syncViaEsi(FC_ID, EVENT_ID);

            assertThat(added).isEqualTo(1);
            ArgumentCaptor<FleetAttendance> saved = ArgumentCaptor.forClass(FleetAttendance.class);
            verify(attendanceRepo).save(saved.capture());
            assertThat(saved.getValue().getCharacterName()).isEqualTo("Pilotin");
            assertThat(saved.getValue().getShipName()).isEqualTo("Rifter");
        }

        @Test
        @DisplayName("benennt unbekannte Piloten und Schiffe nachvollziehbar")
        void namesUnknownEntities() {
            fleetHasMembers(member(999L, 12345L));
            when(characterRepo.findById(999L)).thenReturn(Optional.empty());
            when(invTypeRepo.findById(12345L)).thenReturn(Optional.empty());

            service.syncViaEsi(FC_ID, EVENT_ID);

            ArgumentCaptor<FleetAttendance> saved = ArgumentCaptor.forClass(FleetAttendance.class);
            verify(attendanceRepo).save(saved.capture());
            assertThat(saved.getValue().getCharacterName()).isEqualTo("Unknown Pilot 999");
            assertThat(saved.getValue().getShipName()).isEqualTo("Unknown Ship (12345)");
        }

        @Test
        @DisplayName("zaehlt einen bereits erfassten Teilnehmer nicht doppelt")
        void doesNotCountKnownMemberTwice() {
            FleetAttendance existing = new FleetAttendance();
            existing.setId(1L);
            existing.setCharacterId(PILOT_ID);
            existing.setShipTypeId(587L);
            existing.setShipName("Rifter");
            when(attendanceRepo.findByFleetEventIdAndCharacterId(EVENT_ID, PILOT_ID))
                    .thenReturn(Optional.of(existing));
            fleetHasMembers(member(PILOT_ID, 587L));

            assertThat(service.syncViaEsi(FC_ID, EVENT_ID)).isZero();
        }

        @Test
        @DisplayName("schreibt einen Schiffswechsel nach")
        void updatesChangedShip() {
            FleetAttendance existing = new FleetAttendance();
            existing.setId(1L);
            existing.setCharacterId(PILOT_ID);
            existing.setShipTypeId(587L);
            existing.setShipName("Rifter");
            when(attendanceRepo.findByFleetEventIdAndCharacterId(EVENT_ID, PILOT_ID))
                    .thenReturn(Optional.of(existing));

            InvType nestor = new InvType();
            nestor.setTypeId(33472L);
            nestor.setTypeName("Nestor");
            when(invTypeRepo.findById(33472L)).thenReturn(Optional.of(nestor));
            fleetHasMembers(member(PILOT_ID, 33472L));

            service.syncViaEsi(FC_ID, EVENT_ID);

            assertThat(existing.getShipName()).isEqualTo("Nestor");
        }

        @Test
        @DisplayName("beendet den FAT, wenn der FC offline gegangen ist")
        void closesFleetWhenFcIsOffline() {
            isOnline(false);

            assertThatThrownBy(() -> service.syncViaEsi(FC_ID, EVENT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("offline");
            verify(fleetRepo).save(any());
        }

        @Test
        @DisplayName("meldet, wenn der FC ingame keine Flotte mehr hat")
        void reportsMissingFleet() {
            isInFleet(false);

            assertThatThrownBy(() -> service.syncViaEsi(FC_ID, EVENT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("keiner Flotte");
        }

        @Test
        @DisplayName("beendet den FAT, wenn ESI die Flotte nicht mehr kennt")
        void closesFleetOnNotFound() {
            when(esiService.getCharacterFleet(anyLong(), anyString()))
                    .thenThrow(httpError(HttpStatus.NOT_FOUND));

            assertThatThrownBy(() -> service.syncViaEsi(FC_ID, EVENT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("FAT wurde beendet");
        }

        @Test
        @DisplayName("meldet fehlende Flotten-Rechte als solche")
        void reportsMissingScopes() {
            when(esiService.getCharacterFleet(anyLong(), anyString()))
                    .thenThrow(httpError(HttpStatus.FORBIDDEN));

            assertThatThrownBy(() -> service.syncViaEsi(FC_ID, EVENT_ID))
                    .isInstanceOf(EsiAccessDeniedException.class)
                    .hasMessageContaining("Flotten-Rechte");
        }

        @Test
        @DisplayName("kommt mit einer leeren Mitgliederliste zurecht")
        void handlesEmptyMemberList() {
            when(esiService.getFleetMembers(anyLong(), anyString())).thenReturn(EsiResponse.empty());

            assertThat(service.syncViaEsi(FC_ID, EVENT_ID)).isZero();
        }
    }

    @Nested
    @DisplayName("Teilnahme ueber den Link")
    class JoiningViaLink {

        @Test
        @DisplayName("traegt einen online spielenden Piloten ein")
        void recordsOnlinePilot() {
            when(fleetRepo.findByTrackingCode(CODE)).thenReturn(Optional.of(fleet(TrackingType.LINK)));
            when(attendanceRepo.existsByFleetEventIdAndCharacterId(EVENT_ID, PILOT_ID)).thenReturn(false);

            service.joinViaLink(PILOT_ID, CODE);

            ArgumentCaptor<FleetAttendance> saved = ArgumentCaptor.forClass(FleetAttendance.class);
            verify(attendanceRepo).save(saved.capture());
            assertThat(saved.getValue().getCharacterName()).isEqualTo("Pilotin");
        }

        @Test
        @DisplayName("traegt niemanden doppelt ein")
        void doesNotRecordTwice() {
            when(fleetRepo.findByTrackingCode(CODE)).thenReturn(Optional.of(fleet(TrackingType.LINK)));
            when(attendanceRepo.existsByFleetEventIdAndCharacterId(EVENT_ID, PILOT_ID)).thenReturn(true);

            service.joinViaLink(PILOT_ID, CODE);

            verify(attendanceRepo, never()).save(any());
        }

        @Test
        @DisplayName("weist einen unbekannten Link ab")
        void rejectsUnknownCode() {
            when(fleetRepo.findByTrackingCode(CODE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.joinViaLink(PILOT_ID, CODE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("existiert nicht");
        }

        @Test
        @DisplayName("weist einen abgelaufenen Link ab")
        void rejectsExpiredLink() {
            FleetEvent expired = fleet(TrackingType.LINK);
            expired.setLinkExpiryTime(Instant.now().minus(1, ChronoUnit.HOURS));
            when(fleetRepo.findByTrackingCode(CODE)).thenReturn(Optional.of(expired));

            assertThatThrownBy(() -> service.joinViaLink(PILOT_ID, CODE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("abgelaufen");
        }

        @Test
        @DisplayName("weist einen bereits geschlossenen FAT ab")
        void rejectsClosedFleet() {
            FleetEvent closed = fleet(TrackingType.LINK);
            closed.setEndTime(Instant.now());
            when(fleetRepo.findByTrackingCode(CODE)).thenReturn(Optional.of(closed));

            assertThatThrownBy(() -> service.joinViaLink(PILOT_ID, CODE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bereits geschlossen");
        }

        @Test
        @DisplayName("weist einen Piloten ab, der gar nicht im Spiel ist")
        void rejectsOfflinePilot() {
            // Sonst liesse sich ein FAT ohne jede Teilnahme mitnehmen.
            when(fleetRepo.findByTrackingCode(CODE)).thenReturn(Optional.of(fleet(TrackingType.LINK)));
            isOnline(false);

            assertThatThrownBy(() -> service.joinViaLink(PILOT_ID, CODE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nicht im Spiel online");
        }

        @Test
        @DisplayName("bittet bei fehlenden Rechten um eine neue Anmeldung")
        void asksForReloginOnMissingScopes() {
            when(fleetRepo.findByTrackingCode(CODE)).thenReturn(Optional.of(fleet(TrackingType.LINK)));
            when(esiService.getCharacterOnlineStatus(anyLong(), anyString()))
                    .thenThrow(httpError(HttpStatus.UNAUTHORIZED));

            assertThatThrownBy(() -> service.joinViaLink(PILOT_ID, CODE))
                    .isInstanceOf(EsiAccessDeniedException.class)
                    .hasMessageContaining("neu an");
        }
    }

    @Nested
    @DisplayName("Uebersicht")
    class Listing {

        @Test
        @DisplayName("schliesst abgelaufene Link-FATs beim Lesen nach")
        void closesExpiredLinkFleets() {
            FleetEvent expired = fleet(TrackingType.LINK);
            Instant expiry = Instant.now().minus(2, ChronoUnit.HOURS);
            expired.setLinkExpiryTime(expiry);
            when(fleetRepo.findByStartTimeAfterOrderByStartTimeDesc(any())).thenReturn(List.of(expired));

            service.recentFleets();

            assertThat(expired.getEndTime()).isEqualTo(expiry);
            verify(fleetRepo).save(expired);
        }

        @Test
        @DisplayName("laesst einen laufenden LIVE-FAT offen")
        void leavesLiveFleetOpen() {
            FleetEvent live = fleet(TrackingType.LIVE);
            when(fleetRepo.findByStartTimeAfterOrderByStartTimeDesc(any())).thenReturn(List.of(live));

            service.recentFleets();

            assertThat(live.getEndTime()).isNull();
            verify(fleetRepo, never()).save(any());
        }

        @Test
        @DisplayName("reicht die Anwesenheitsliste durch")
        void returnsAttendance() {
            FleetAttendance entry = new FleetAttendance();
            when(attendanceRepo.findByFleetEventId(EVENT_ID)).thenReturn(List.of(entry));

            assertThat(service.attendance(EVENT_ID)).containsExactly(entry);
        }
    }
}
