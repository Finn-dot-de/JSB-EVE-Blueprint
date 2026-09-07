package com.eve.own.auth.backend.domain.fleet.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.testsupport.FakeTuple;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Die Abfragen der FAT-Statistik greifen ueber Flotten und Teilnahmen hinweg.
 * Geprueft wird, dass sie das Fenster gebunden bekommen und die Verknuepfung
 * so steht, dass nichts still verschwindet.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Abfragen der FAT-Statistik")
class FleetStatisticsQueryRepositoryTest {

    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private FleetStatisticsQueryRepository repository;

    private final List<String> ausgefuehrt = new ArrayList<>();
    private final Map<String, Object> gebunden = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        repository = new FleetStatisticsQueryRepository();
        Field em = FleetStatisticsQueryRepository.class.getDeclaredField("em");
        em.setAccessible(true);
        em.set(repository, entityManager);

        when(entityManager.createNativeQuery(anyString(), eq(Tuple.class))).thenAnswer(aufruf -> {
            ausgefuehrt.add(aufruf.getArgument(0));
            return query;
        });
        when(query.setParameter(anyString(), any())).thenAnswer(aufruf -> {
            gebunden.put(aufruf.getArgument(0), aufruf.getArgument(1));
            return query;
        });
        when(query.getResultList()).thenReturn(List.of());
        when(query.getSingleResult()).thenReturn(
                FakeTuple.of("skelettZeilen", 0L, "ersteFlotte", null));
    }

    private String letztesSql() {
        return ausgefuehrt.getLast();
    }

    @Test
    @DisplayName("das Skelett bekommt den Fensterbeginn gebunden")
    void skelettBindetFenster() {
        Instant von = Instant.parse("2026-06-08T12:00:00Z");
        repository.skelett(von);

        // Ohne die Bindung liefe die Abfrage ueber den ganzen Bestand - und
        // die Aussage der Seite waere eine andere als ihre Ueberschrift.
        assertThat(gebunden).containsEntry("von", von);
        assertThat(letztesSql()).contains("e.start_time >= :von");
    }

    @Test
    @DisplayName("eine Flotte ohne Teilnehmer faellt nicht aus dem Skelett")
    void flotteOhneTeilnehmerBleibt() {
        repository.skelett(Instant.now());

        // Mit einem INNER JOIN verschwaende jede Flotte, auf der niemand war -
        // und der Nenner, gegen den jede Teilnahmezahl gelesen wird, waere zu
        // klein.
        assertThat(letztesSql()).contains("FROM fleet_events e")
                .contains("LEFT JOIN fleet_attendance a");
    }

    @Test
    @DisplayName("das Skelett liefert die Teilnahme-ID mit, damit sich Doppel entdoppeln lassen")
    void skelettLiefertAttendanceId() {
        repository.skelett(Instant.now());

        // fleet_attendance hat keinen Eindeutigkeitsindex auf (Flotte,
        // Charakter). Ohne diese Spalte liesse sich bei einem Doppel nicht
        // entscheiden, welche der beiden Zeilen die juengere ist.
        assertThat(letztesSql()).contains("a.id                AS \"attendanceId\"");
    }

    @Test
    @DisplayName("das Skelett bringt den Account mit, mit Rueckfall auf den Charakter selbst")
    void skelettLiefertAccountId() {
        repository.skelett(Instant.now());

        // Gezaehlt wird je Account: Wer drei Alts in eine Flotte bringt, war
        // einmal dabei. Der LEFT JOIN und der COALESCE gehoeren zusammen -
        // ein INNER JOIN wuerde jeden Gast ueber den Link verschlucken, und
        // ohne den Rueckfall haetten alle Unbekannten denselben leeren
        // Schluessel und waeren ein einziger Account.
        assertThat(letztesSql())
                .contains("COALESCE(c.main_character_id, a.character_id) AS \"accountId\"")
                .contains("LEFT JOIN characters c ON c.character_id = a.character_id");
    }

    @Test
    @DisplayName("die Account-Abfrage zaehlt alle Charaktere, nicht nur die geflogenen")
    void accountsZaehltAlleCharaktere() {
        repository.accounts(List.of(42L, 43L));

        // Die Zahl beantwortet, ob das Auth zu diesem Account ueberhaupt eine
        // Verknuepfung kennt. Sie ueber die Teilnahmen zu bilden hiesse, einen
        // Account fuer unverbunden zu halten, nur weil seine Alts an diesem
        // Abend nicht dabei waren.
        assertThat(gebunden).containsEntry("ids", List.of(42L, 43L));
        assertThat(letztesSql()).contains("FROM characters c")
                .contains("GROUP BY COALESCE(c.main_character_id, c.character_id)");
    }

    @Test
    @DisplayName("ohne Accounts wird gar nicht erst gefragt")
    void accountsOhneIdsFragtNicht() {
        List<Tuple> zeilen = repository.accounts(List.of());

        // Ein IN () waere ein Syntaxfehler - und eine Abfrage, die nichts
        // nachschlagen soll, gehoert ohnehin nicht abgeschickt.
        assertThat(zeilen).isEmpty();
        assertThat(ausgefuehrt).isEmpty();
    }

    @Test
    @DisplayName("die Vorabfrage liefert Zeilenzahl und aelteste Flotte in einem Zug")
    void vorabLiefertBeides() {
        when(query.getSingleResult()).thenReturn(FakeTuple.of(
                "skelettZeilen", 4711L,
                "ersteFlotte", Timestamp.from(Instant.parse("2026-01-02T03:04:05Z"))));

        var vorab = repository.vorab(Instant.parse("2026-06-08T12:00:00Z"));

        assertThat(vorab.skelettZeilen()).isEqualTo(4711L);
        assertThat(vorab.ersteFlotteInsgesamt()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        // Die aelteste Flotte bewusst OHNE Fenstergrenze - nur so laesst sich
        // sagen, ob "90 Tage" auch 90 Tage Daten sind.
        assertThat(letztesSql()).contains("SELECT MIN(start_time) FROM fleet_events");
    }

    @Test
    @DisplayName("ein Zeitpunkt wird aus jedem Treibertyp gelesen, ein LocalDateTime als UTC")
    void zeitpunktAusJedemTyp() {
        Instant erwartet = Instant.parse("2026-03-04T05:06:07Z");

        assertThat(FleetStatisticsQueryRepository.zeitpunkt(null)).isNull();
        assertThat(FleetStatisticsQueryRepository.zeitpunkt(erwartet)).isEqualTo(erwartet);
        assertThat(FleetStatisticsQueryRepository.zeitpunkt(Timestamp.from(erwartet)))
                .isEqualTo(erwartet);
        assertThat(FleetStatisticsQueryRepository.zeitpunkt(
                OffsetDateTime.ofInstant(erwartet, ZoneOffset.UTC))).isEqualTo(erwartet);
        // Der entscheidende Fall: Hibernate legt Instant-Felder in
        // "timestamp without time zone" ab. Als Serverzeit gelesen verschoebe
        // sich die ganze Auswertung um den Zonenversatz.
        assertThat(FleetStatisticsQueryRepository.zeitpunkt(
                LocalDateTime.ofInstant(erwartet, ZoneOffset.UTC))).isEqualTo(erwartet);

        assertThatThrownBy(() -> FleetStatisticsQueryRepository.zeitpunkt("2026-03-04"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
