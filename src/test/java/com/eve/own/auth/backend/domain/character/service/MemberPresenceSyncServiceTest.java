package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CorporationMemberPresence;
import com.eve.own.auth.backend.domain.character.repository.CorporationMemberPresenceRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Aufzeichnung von Standort und Online-Zeiten")
class MemberPresenceSyncServiceTest {

    private static final Long CORP = 98_000_001L;
    private static final Long MITGLIED = 2_112_000_001L;
    private static final Long ZWEITES_MITGLIED = 2_112_000_002L;
    private static final Instant LOGON = Instant.parse("2026-08-30T18:00:00Z");
    private static final Instant LOGOFF = Instant.parse("2026-08-30T21:00:00Z");
    private static final Long STATION = 60_003_760L;

    @Mock private EsiService esiService;
    @Mock private DirectorTokenProvider directorTokenProvider;
    @Mock private AltSourceStore store;
    @Mock private CorporationMemberPresenceRepository presenceRepo;

    private AltSourceProperties properties;
    private MemberPresenceSyncService service;

    @BeforeEach
    void setUp() {
        properties = new AltSourceProperties();
        service = new MemberPresenceSyncService(esiService, directorTokenProvider, properties,
                store, presenceRepo);
        when(presenceRepo.findLatestPerCharacter(anyLong())).thenReturn(List.of());
    }

    private static EsiService.EsiMemberTrackingResponse mitglied(Long id, Long locationId,
                                                                 Instant logon, Instant logoff) {
        return new EsiService.EsiMemberTrackingResponse(id, Instant.parse("2020-01-01T00:00:00Z"),
                logon, logoff, locationId, 670L, null);
    }

    private void esiLiefert(EsiService.EsiMemberTrackingResponse... entries) {
        Character director = new Character();
        director.setId(1L);
        director.setName("Der Chef");
        when(directorTokenProvider.attempt(anyLong(), anyString(), any())).thenReturn(
                new DirectorTokenProvider.DirectorAttempt<>(
                        EsiResponse.changed(entries, null, null), director, List.of()));
    }

    private void esiFaelltAus() {
        when(directorTokenProvider.attempt(anyLong(), anyString(), any())).thenReturn(
                new DirectorTokenProvider.DirectorAttempt<>(null, null, List.of()));
    }

    private List<CorporationMemberPresence> captured() {
        ArgumentCaptor<List<CorporationMemberPresence>> captor = ArgumentCaptor.captor();
        verify(store).appendPresence(captor.capture());
        return captor.getValue();
    }

    private static CorporationMemberPresence zeile(Long characterId, Long locationId,
                                                   Instant logon, Instant logoff) {
        CorporationMemberPresence presence = new CorporationMemberPresence();
        presence.setCorporationId(CORP);
        presence.setCharacterId(characterId);
        presence.setLocationId(locationId);
        presence.setLogonDate(logon);
        presence.setLogoffDate(logoff);
        presence.setMeasuredAt(Instant.parse("2026-08-30T12:00:00Z"));
        return presence;
    }

    @Test
    @DisplayName("haelt Standort, Logon und Logoff samt Messzeitpunkt fest")
    void recordsLocationAndSessionTimes() {
        esiLiefert(mitglied(MITGLIED, STATION, LOGON, LOGOFF));

        assertThat(service.sync(CORP)).isEqualTo(1);

        assertThat(captured()).singleElement().satisfies(row -> {
            assertThat(row.getCorporationId()).isEqualTo(CORP);
            assertThat(row.getCharacterId()).isEqualTo(MITGLIED);
            assertThat(row.getLocationId()).isEqualTo(STATION);
            assertThat(row.getLogonDate()).isEqualTo(LOGON);
            assertThat(row.getLogoffDate()).isEqualTo(LOGOFF);
            assertThat(row.getMeasuredAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("erfasst auch Mitglieder, die sich nie bei dieser Anwendung angemeldet haben")
    void coversUnregisteredMembers() {
        esiLiefert(mitglied(MITGLIED, STATION, LOGON, LOGOFF),
                mitglied(ZWEITES_MITGLIED, STATION, LOGON, LOGOFF));

        service.sync(CORP);

        // Das ist der ganze Grund fuer die Mitgliederverfolgung statt
        // /characters/{id}/location/: der gesuchte Alt hat kein Token, und mit
        // dem Standort-Endpunkt bliebe er strukturell unsichtbar.
        assertThat(captured()).hasSize(2);
    }

    @Test
    @DisplayName("schreibt keine Zeile, wenn sich gegenueber der letzten nichts geaendert hat")
    void writesNothingWhenTheStateIsUnchanged() {
        when(presenceRepo.findLatestPerCharacter(CORP))
                .thenReturn(List.of(zeile(MITGLIED, STATION, LOGON, LOGOFF)));
        esiLiefert(mitglied(MITGLIED, STATION, LOGON, LOGOFF));

        assertThat(service.sync(CORP)).isZero();

        // Ohne diese Bremse schriebe jeder Lauf fuer jedes Mitglied dieselbe
        // Aussage neu: 400 Mitglieder mal acht Laeufe mal 90 Tage sind 288.000
        // Zeilen, von denen die allermeisten nichts Neues sagen.
        verify(store).appendPresence(List.of());
    }

    @Test
    @DisplayName("schreibt eine Zeile, sobald sich Logon, Logoff oder Standort aendern")
    void writesWhenSomethingChanged() {
        when(presenceRepo.findLatestPerCharacter(CORP))
                .thenReturn(List.of(zeile(MITGLIED, STATION, LOGON, LOGOFF)));
        esiLiefert(mitglied(MITGLIED, STATION, LOGON, Instant.parse("2026-08-30T23:00:00Z")));

        assertThat(service.sync(CORP)).isEqualTo(1);
    }

    @Test
    @DisplayName("schreibt bei ausgefallener Quelle keine Teildaten")
    void writesNothingWhenTheSourceFailed() {
        esiFaelltAus();

        assertThat(service.sync(CORP)).isZero();

        // Ohne diese Zeile entstuende eine Messung, die aussieht, als sei
        // niemand online gewesen. Eine spaetere Auswertung koennte sie nicht
        // von einem echten leeren Zeitraum unterscheiden - derselbe Fehler wie
        // bei den Fuzzwork-Nullpreisen.
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("uebernimmt einen fehlenden Standort als unbekannt und nicht als null")
    void missingLocationStaysNull() {
        esiLiefert(mitglied(MITGLIED, null, LOGON, LOGOFF));

        service.sync(CORP);

        assertThat(captured()).singleElement()
                .satisfies(row -> assertThat(row.getLocationId()).isNull());
    }

    @Test
    @DisplayName("ueberspringt Zeilen ohne Charakter-ID und doppelte Mitglieder")
    void skipsRowsWithoutIdAndDuplicates() {
        esiLiefert(mitglied(null, STATION, LOGON, LOGOFF),
                mitglied(MITGLIED, STATION, LOGON, LOGOFF),
                mitglied(MITGLIED, STATION, LOGON, LOGOFF));

        service.sync(CORP);

        // Zwei Zeilen mit demselben Messzeitpunkt fuer denselben Charakter
        // wuerden die Reihe verfaelschen, aus der die Korrelation entsteht.
        assertThat(captured()).hasSize(1);
    }

    @Test
    @DisplayName("tut nichts, wenn die Aufzeichnung abgeschaltet ist")
    void doesNothingWhenDisabled() {
        properties.setPresenceEnabled(false);
        esiLiefert(mitglied(MITGLIED, STATION, LOGON, LOGOFF));

        assertThat(service.sync(CORP)).isZero();

        // Der Schalter greift VOR dem Director-Token und vor dem ESI-Aufruf.
        // Diese Erfassung ist die eingriffstiefste der vier - abgeschaltet muss
        // heissen, dass gar nicht erst gefragt wird.
        verify(directorTokenProvider, never()).attempt(anyLong(), anyString(), any());
        verifyNoInteractions(store);
    }
}
