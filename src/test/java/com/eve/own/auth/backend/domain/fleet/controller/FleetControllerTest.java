package com.eve.own.auth.backend.domain.fleet.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.domain.fleet.dto.FleetStatisticsDtos;
import com.eve.own.auth.backend.domain.fleet.dto.FleetStatisticsDtos.Anteil;
import com.eve.own.auth.backend.domain.fleet.dto.FleetStatisticsDtos.MeineFat;
import com.eve.own.auth.backend.domain.fleet.dto.FleetStatisticsDtos.Zeitraum;
import com.eve.own.auth.backend.domain.fleet.service.FleetStatisticsService;
import com.eve.own.auth.backend.domain.fleet.service.FleetTrackingService;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Die Flotten-Endpunkte - und vor allem die Grenze zwischen den beiden
 * FAT-Sichten.
 *
 * <p>Die corpweite Tafel traegt die Namen aller Piloten samt ihrer
 * Teilnahmezahl; die eigene Sicht traegt eine einzige Zeile. Ob ein Aufrufer
 * die eine oder die andere bekommt, entscheidet sich hier an zwei Dingen: an
 * der Annotation und daran, dass der Handelnde aus dem Sicherheitskontext
 * kommt. Beides wird deshalb nicht nur benutzt, sondern festgehalten - eine
 * verlorene Annotation faellt sonst niemandem auf.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Flotten-Endpunkte")
class FleetControllerTest {

    private static final Long ANGEMELDET = 4242L;

    @Mock private FleetTrackingService fleetTrackingService;
    @Mock private FleetStatisticsService fleetStatisticsService;

    private FleetController controller;

    @BeforeEach
    void setUp() {
        controller = new FleetController(fleetTrackingService, fleetStatisticsService);

        // So setzt der JwtAuthenticationFilter das Principal: die Charakter-ID.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ANGEMELDET, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static MeineFat eigeneAntwort() {
        return new MeineFat(
                new Zeitraum(90, 90, Instant.now(), Instant.now(), null, null, false, null),
                new Anteil(6, 20), true, List.of("Ich Selbst"),
                Instant.now(), Instant.now(), true, null, null);
    }

    /** Der Endpunkt zu einem Pfad - gesucht ueber die Zuordnung, nicht den Namen. */
    private static Method endpunkt(String pfad) {
        return Stream.of(FleetController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(GetMapping.class))
                .filter(m -> List.of(m.getAnnotation(GetMapping.class).value()).contains(pfad))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Kein GET-Endpunkt auf " + pfad));
    }

    @Nested
    @DisplayName("FAT-Statistik")
    class FatStatistik {

        @Test
        @DisplayName("die corpweite Tafel bleibt der Flottenfuehrung vorbehalten")
        void corpweitNurFuerDieFuehrung() {
            // Ohne diese Zeile kann die Annotation bei einem Umbau lautlos
            // wegfallen: Der Endpunkt liefe weiter, saehe richtig aus und
            // gaebe jedem Angemeldeten die Namensliste der Corporation.
            PreAuthorize regel = endpunkt("/statistics").getAnnotation(PreAuthorize.class);

            assertThat(regel).isNotNull();
            assertThat(regel.value()).isEqualTo(AccessRules.FLEET_STAFF);
        }

        @Test
        @DisplayName("die eigene Sicht steht jedem Angemeldeten offen - und sagt das an der Annotation")
        void eigeneSichtFuerJeden() {
            // Der Sicherheitsfilter verlangt fuer /api ohnehin eine Anmeldung;
            // die Annotation sichert also nichts zusaetzlich ab. Sie steht
            // trotzdem da, weil an einem Endpunkt ganz ohne Annotation nicht
            // zu unterscheiden ist, ob der weite Kreis gewollt ist oder ob
            // jemand sie vergessen hat.
            PreAuthorize regel = endpunkt("/statistics/me").getAnnotation(PreAuthorize.class);

            assertThat(regel).isNotNull();
            assertThat(regel.value()).isEqualTo(AccessRules.AUTHENTICATED);
            assertThat(regel.value()).isNotEqualTo(AccessRules.FLEET_STAFF);
        }

        @Test
        @DisplayName("es gibt keinen Parameter, mit dem sich eine fremde Kennung anfragen liesse")
        void keineFremdeKennungAnfragbar() {
            // DAS IST DIE REGEL, an der dieser Umbau scheitern koennte: Die
            // eigene Sicht laeuft ohne Rollenpruefung. Gaebe es hier einen
            // Parameter fuer eine Kennung, waere derselbe Endpunkt eine
            // Auskunftsstelle ueber jeden anderen Piloten - und die fehlende
            // Rollenpruefung waere dann genau das Loch.
            Method eigene = endpunkt("/statistics/me");

            assertThat(eigene.getParameters()).hasSize(1);
            for (Parameter parameter : eigene.getParameters()) {
                assertThat(parameter.isAnnotationPresent(PathVariable.class)).isFalse();
                assertThat(parameter.isAnnotationPresent(RequestBody.class)).isFalse();
                assertThat(parameter.isAnnotationPresent(RequestParam.class)).isTrue();
                // Der eine erlaubte Parameter ist der Zeitraum. Alles, was nach
                // einer Kennung aussieht, waere hier der Fehler.
                String name = parameter.getName().toLowerCase(Locale.ROOT);
                assertThat(name).doesNotContain("id").doesNotContain("account")
                        .doesNotContain("character");
                assertThat(parameter.getType()).isEqualTo(Integer.class);
            }
        }

        @Test
        @DisplayName("der Handelnde kommt aus dem Sicherheitskontext, in beiden Sichten")
        void handelnderKommtAusDerSitzung() {
            when(fleetStatisticsService.meineStatistik(anyLong(), any()))
                    .thenReturn(eigeneAntwort());
            when(fleetStatisticsService.statistik(anyLong(), any())).thenReturn(
                    new FleetStatisticsDtos.FatStatistik(null, null, false, null, null));

            controller.getMyFleetStatistics(30);
            controller.getFleetStatistics(null);

            // Ohne diese Zeilen koennte die Kennung aus der Adresszeile kommen,
            // und jeder saehe die Zahlen jedes anderen.
            verify(fleetStatisticsService).meineStatistik(ANGEMELDET, 30);
            verify(fleetStatisticsService).statistik(ANGEMELDET, null);
        }

        @Test
        @DisplayName("die eigene Sicht reicht den Datensatz des Dienstes unveraendert durch")
        void eigeneSichtDurchgereicht() {
            MeineFat antwort = eigeneAntwort();
            when(fleetStatisticsService.meineStatistik(anyLong(), isNull())).thenReturn(antwort);

            // Ohne einen Aufruf faellt ein falscher Rueckgabetyp erst dem
            // ersten Benutzer auf - der Uebersetzer laesst ihn durch.
            assertThat(controller.getMyFleetStatistics(null).getBody()).isSameAs(antwort);
        }
    }
}
