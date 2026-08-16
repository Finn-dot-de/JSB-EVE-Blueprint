package com.eve.own.auth.backend.domain.assets.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.assets.entity.AssetLocation;
import com.eve.own.auth.backend.domain.assets.repository.AssetLocationRepository;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.esi.EsiService;
import com.eve.own.auth.backend.testsupport.FakeTuple;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.client.RestClientResponseException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Aufloesung der Asset-Standorte")
class AssetLocationServiceTest {

    /** Ab dieser Grenze handelt es sich um eine Spieler-Struktur. */
    private static final long STRUCTURE_ID = 1_030_000_000_001L;
    private static final long STATION_ID = 60003760L;

    @Mock private AssetLocationRepository locationRepo;
    @Mock private CharacterRepository characterRepo;
    @Mock private AuthService authService;
    @Mock private EsiService esiService;
    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private AssetLocationService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AssetLocationService(locationRepo, characterRepo, authService, esiService);

        Field em = AssetLocationService.class.getDeclaredField("em");
        em.setAccessible(true);
        em.set(service, entityManager);

        when(entityManager.createNativeQuery(anyString(), eq(Tuple.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        when(locationRepo.findById(anyLong())).thenReturn(Optional.empty());
        when(locationRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(locationRepo.findUnresolvedLocationIds()).thenReturn(List.of());
        when(characterRepo.findAllWithCorporation()).thenReturn(List.of(characterWithToken()));
        when(authService.getValidAccessToken(any())).thenReturn("token");
    }

    private static Class<Tuple> eq(Class<Tuple> type) {
        return org.mockito.ArgumentMatchers.eq(type);
    }

    private static Character characterWithToken() {
        Character character = new Character();
        character.setId(1L);
        character.setName("Pilot Eins");
        character.setRefreshToken("refresh");
        return character;
    }

    private static RestClientResponseException httpError(int status) {
        return new RestClientResponseException("Fehler", status, "", HttpHeaders.EMPTY, null, null);
    }

    @Nested
    @DisplayName("Container-Kette")
    class ContainerChain {

        @Test
        @DisplayName("gibt einen Standort ohne Elterneintrag unveraendert zurueck")
        void returnsPlainLocation() {
            assertThat(service.resolveRootLocation(Map.of(), STATION_ID)).isEqualTo(STATION_ID);
        }

        @Test
        @DisplayName("laeuft die Kette bis zum echten Standort hoch")
        void walksUpTheChain() {
            // Modul liegt im Container, Container im Schiff, Schiff in der Station.
            Map<Long, Long> chain = Map.of(
                    200L, 100L,
                    100L, 50L,
                    50L, STATION_ID);

            assertThat(service.resolveRootLocation(chain, 200L)).isEqualTo(STATION_ID);
        }

        @Test
        @DisplayName("bricht bei einem Zyklus in den ESI-Daten ab")
        void survivesCycles() {
            Map<Long, Long> cycle = new HashMap<>();
            cycle.put(1L, 2L);
            cycle.put(2L, 1L);

            assertThat(service.resolveRootLocation(cycle, 1L)).isNotNull();
        }

        @Test
        @DisplayName("bricht ab, wenn ein Element auf sich selbst zeigt")
        void survivesSelfReference() {
            assertThat(service.resolveRootLocation(Map.of(1L, 1L), 1L)).isEqualTo(1L);
        }

        @Test
        @DisplayName("kommt mit fehlendem Standort zurecht")
        void handlesNullLocation() {
            assertThat(service.resolveRootLocation(Map.of(), null)).isNull();
        }
    }

    @Nested
    @DisplayName("Offene Standorte")
    class PendingLocations {

        @Test
        @DisplayName("macht nichts, wenn nichts offen ist")
        void doesNothingWhenNothingPending() {
            service.resolvePendingLocations();

            verify(esiService, never()).getUniverseNames(anyList());
        }

        @Test
        @DisplayName("loest oeffentliche Standorte ueber die Bulk-Abfrage auf")
        void resolvesPublicLocations() {
            when(locationRepo.findUnresolvedLocationIds()).thenReturn(new ArrayList<>(List.of(STATION_ID)));
            when(esiService.getUniverseNames(anyList())).thenReturn(new EsiService.EsiIdName[]{
                    new EsiService.EsiIdName(STATION_ID, "Jita IV - Moon 4", "station")});

            service.resolvePendingLocations();

            ArgumentCaptor<AssetLocation> saved = ArgumentCaptor.forClass(AssetLocation.class);
            verify(locationRepo).save(saved.capture());
            assertThat(saved.getValue().getName()).isEqualTo("Jita IV - Moon 4");
            assertThat(saved.getValue().getLocationKind()).isEqualTo("STATION");
            assertThat(saved.getValue().getResolveFailed()).isFalse();
        }

        @Test
        @DisplayName("reichert einen Standort um System und Region aus der SDE an")
        void enrichesWithSystemAndRegion() {
            when(locationRepo.findUnresolvedLocationIds()).thenReturn(new ArrayList<>(List.of(30000142L)));
            when(esiService.getUniverseNames(anyList())).thenReturn(new EsiService.EsiIdName[]{
                    new EsiService.EsiIdName(30000142L, "Jita", "solar_system")});
            when(query.getResultList()).thenReturn(List.of(FakeTuple.of(
                    "systemName", "Jita", "regionId", 10000002L, "regionName", "The Forge")));

            service.resolvePendingLocations();

            ArgumentCaptor<AssetLocation> saved = ArgumentCaptor.forClass(AssetLocation.class);
            verify(locationRepo).save(saved.capture());
            assertThat(saved.getValue().getSystemName()).isEqualTo("Jita");
            assertThat(saved.getValue().getRegionName()).isEqualTo("The Forge");
        }

        @Test
        @DisplayName("laeuft weiter, wenn die Bulk-Abfrage scheitert")
        void survivesBulkFailure() {
            when(locationRepo.findUnresolvedLocationIds()).thenReturn(new ArrayList<>(List.of(STATION_ID)));
            when(esiService.getUniverseNames(anyList())).thenThrow(new RuntimeException("ESI weg"));

            service.resolvePendingLocations();

            verify(locationRepo, never()).save(any());
        }

        @Test
        @DisplayName("ueberspringt leere Eintraege in der Liste")
        void skipsNullIds() {
            List<Long> pending = new ArrayList<>();
            pending.add(null);
            when(locationRepo.findUnresolvedLocationIds()).thenReturn(pending);

            service.resolvePendingLocations();

            verify(esiService, never()).getUniverseNames(anyList());
        }
    }

    @Nested
    @DisplayName("Spieler-Strukturen")
    class Structures {

        @BeforeEach
        void structurePending() {
            when(locationRepo.findUnresolvedLocationIds())
                    .thenReturn(new ArrayList<>(List.of(STRUCTURE_ID)));
        }

        @Test
        @DisplayName("loest eine Struktur mit gueltigem Token auf")
        void resolvesStructure() {
            when(esiService.getStructureInfo(anyLong(), anyString())).thenReturn(
                    new EsiService.EsiStructureResponse("1DQ1-A - Keepstar", 98000001L, 30000142L, 35834L));

            service.resolvePendingLocations();

            ArgumentCaptor<AssetLocation> saved = ArgumentCaptor.forClass(AssetLocation.class);
            verify(locationRepo).save(saved.capture());
            assertThat(saved.getValue().getName()).isEqualTo("1DQ1-A - Keepstar");
            assertThat(saved.getValue().getLocationKind()).isEqualTo("STRUCTURE");
            assertThat(saved.getValue().getOwnerId()).isEqualTo(98000001L);
        }

        @Test
        @DisplayName("merkt eine Struktur als unauffindbar vor, wenn kein Token existiert")
        void marksUnknownWithoutToken() {
            when(characterRepo.findAllWithCorporation()).thenReturn(List.of());

            service.resolvePendingLocations();

            ArgumentCaptor<AssetLocation> saved = ArgumentCaptor.forClass(AssetLocation.class);
            verify(locationRepo).save(saved.capture());
            assertThat(saved.getValue().getResolveFailed()).isTrue();
        }

        @Test
        @DisplayName("merkt eine Struktur ohne Namen als unauffindbar vor")
        void marksUnknownWithoutName() {
            when(esiService.getStructureInfo(anyLong(), anyString())).thenReturn(null);

            service.resolvePendingLocations();

            ArgumentCaptor<AssetLocation> saved = ArgumentCaptor.forClass(AssetLocation.class);
            verify(locationRepo).save(saved.capture());
            assertThat(saved.getValue().getResolveFailed()).isTrue();
        }

        @Test
        @DisplayName("bricht beim aufgebrauchten Fehler-Budget sofort ab")
        void stopsOnErrorLimit() {
            List<Long> many = new ArrayList<>(List.of(
                    STRUCTURE_ID, STRUCTURE_ID + 1, STRUCTURE_ID + 2));
            when(locationRepo.findUnresolvedLocationIds()).thenReturn(many);
            when(esiService.getStructureInfo(anyLong(), anyString())).thenThrow(httpError(420));

            service.resolvePendingLocations();

            verify(esiService, times(1)).getStructureInfo(anyLong(), anyString());
        }

        @Test
        @DisplayName("gibt nach mehreren 403ern in Folge auf")
        void stopsAfterRepeatedForbidden() {
            // Ein Token ohne Struktur-Zugriff soll nicht hunderte Fehlversuche erzeugen.
            List<Long> many = new ArrayList<>();
            for (long i = 0; i < 20; i++) {
                many.add(STRUCTURE_ID + i);
            }
            when(locationRepo.findUnresolvedLocationIds()).thenReturn(many);
            when(esiService.getStructureInfo(anyLong(), anyString())).thenThrow(httpError(403));

            service.resolvePendingLocations();

            verify(esiService, times(5)).getStructureInfo(anyLong(), anyString());
        }

        @Test
        @DisplayName("merkt eine Struktur bei anderen Fehlern als unauffindbar vor")
        void marksUnknownOnOtherErrors() {
            when(esiService.getStructureInfo(anyLong(), anyString())).thenThrow(httpError(500));

            service.resolvePendingLocations();

            verify(locationRepo, atLeastOnce()).save(any());
        }

        @Test
        @DisplayName("faengt auch unerwartete Ausnahmen ab")
        void survivesUnexpectedFailure() {
            when(esiService.getStructureInfo(anyLong(), anyString()))
                    .thenThrow(new IllegalStateException("kaputt"));

            service.resolvePendingLocations();

            verify(locationRepo, atLeastOnce()).save(any());
        }
    }
}
