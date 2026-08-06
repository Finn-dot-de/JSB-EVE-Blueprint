package com.eve.own.auth.backend.domain.mining.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterMiningRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.mining.dto.MiningDtos;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Mining-Rangliste")
class MiningLeaderboardServiceTest {

    private static final Long VIEWER_ID = 1000L;

    @Mock private CharacterRepository characterRepo;
    @Mock private CharacterMiningRepository miningRepo;

    private MiningLeaderboardService service;

    @BeforeEach
    void setUp() {
        service = new MiningLeaderboardService(characterRepo, miningRepo);

        Character viewer = new Character();
        viewer.setId(VIEWER_ID);
        viewer.setMainCharacterId(VIEWER_ID);
        when(characterRepo.findById(VIEWER_ID)).thenReturn(Optional.of(viewer));
        when(miningRepo.findAvailableMiningMonths()).thenReturn(List.of("2026-08", "2026-07"));
        when(miningRepo.aggregateMiningByAccount(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of());
    }

    /** Spalten: mainId, mainName, Volumen, Wert, Einheiten. */
    private static Object[] row(long mainId, String name, double volume, double value, long units) {
        return new Object[]{mainId, name, volume, value, units};
    }

    @Test
    @DisplayName("zeigt ohne Vorgabe den neuesten Monat mit Daten")
    void defaultsToNewestMonth() {
        MiningDtos.MiningLeaderboardDto board = service.leaderboard(null, VIEWER_ID);

        assertThat(board.month()).isEqualTo("2026-08");
        assertThat(board.availableMonths()).containsExactly("2026-08", "2026-07");
    }

    @Test
    @DisplayName("faellt ohne jegliche Daten auf den Gesamtzeitraum zurueck")
    void fallsBackToAllWithoutData() {
        when(miningRepo.findAvailableMiningMonths()).thenReturn(List.of());

        assertThat(service.leaderboard(null, VIEWER_ID).month())
                .isEqualTo(MiningLeaderboardService.ALL_MONTHS);
    }

    @Test
    @DisplayName("nimmt einen angeforderten Monat und entfernt Leerzeichen")
    void usesRequestedMonth() {
        assertThat(service.leaderboard("  2026-07  ", VIEWER_ID).month()).isEqualTo("2026-07");
        assertThat(service.leaderboard("", VIEWER_ID).month()).isEqualTo("2026-08");
    }

    @Test
    @DisplayName("vergibt die Plaetze in der Reihenfolge der Abfrage")
    void assignsRanksInQueryOrder() {
        when(miningRepo.aggregateMiningByAccount("2026-08")).thenReturn(List.of(
                row(2000L, "Beste Pilotin", 5_000, 50_000, 500),
                row(VIEWER_ID, "Ich", 1_000, 10_000, 100)));

        List<MiningDtos.MiningLeaderRowDto> rows = service.leaderboard(null, VIEWER_ID).rows();

        assertThat(rows).extracting(MiningDtos.MiningLeaderRowDto::rank).containsExactly(1, 2);
        assertThat(rows.getFirst().mainName()).isEqualTo("Beste Pilotin");
    }

    @Test
    @DisplayName("markiert die eigene Zeile")
    void marksOwnRow() {
        when(miningRepo.aggregateMiningByAccount("2026-08")).thenReturn(List.of(
                row(2000L, "Andere", 5_000, 50_000, 500),
                row(VIEWER_ID, "Ich", 1_000, 10_000, 100)));

        List<MiningDtos.MiningLeaderRowDto> rows = service.leaderboard(null, VIEWER_ID).rows();

        assertThat(rows.getFirst().isMe()).isFalse();
        assertThat(rows.get(1).isMe()).isTrue();
    }

    @Test
    @DisplayName("markiert bei einem Alt die Zeile seines Accounts")
    void marksAccountRowForAnAlt() {
        Character alt = new Character();
        alt.setId(1001L);
        alt.setMainCharacterId(VIEWER_ID);
        when(characterRepo.findById(1001L)).thenReturn(Optional.of(alt));
        when(miningRepo.aggregateMiningByAccount("2026-08"))
                .thenReturn(List.<Object[]>of(row(VIEWER_ID, "Main", 1_000, 10_000, 100)));

        assertThat(service.leaderboard(null, 1001L).rows().getFirst().isMe()).isTrue();
    }

    @Test
    @DisplayName("summiert Volumen und Wert ueber alle Zeilen")
    void sumsTotals() {
        when(miningRepo.aggregateMiningByAccount("2026-08")).thenReturn(List.of(
                row(2000L, "A", 5_000, 50_000, 500),
                row(3000L, "B", 1_500, 15_000, 150)));

        MiningDtos.MiningLeaderboardDto board = service.leaderboard(null, VIEWER_ID);

        assertThat(board.totalVolume()).isEqualTo(6_500.0);
        assertThat(board.totalValue()).isEqualTo(65_000.0);
    }

    @Test
    @DisplayName("faengt fehlende Werte in den Ergebniszeilen ab")
    void handlesNullColumns() {
        when(miningRepo.aggregateMiningByAccount("2026-08"))
                .thenReturn(List.<Object[]>of(new Object[]{4000L, null, null, null, null}));

        MiningDtos.MiningLeaderRowDto row = service.leaderboard(null, VIEWER_ID).rows().getFirst();

        assertThat(row.mainName()).isEqualTo("Account 4000");
        assertThat(row.volume()).isZero();
        assertThat(row.value()).isZero();
        assertThat(row.units()).isZero();
        assertThat(row.portraitUrl()).contains("/characters/4000/portrait");
    }

    @Test
    @DisplayName("kommt mit einem unbekannten Betrachter zurecht")
    void toleratesUnknownViewer() {
        when(characterRepo.findById(404L)).thenReturn(Optional.empty());
        when(miningRepo.aggregateMiningByAccount("2026-08"))
                .thenReturn(List.<Object[]>of(row(2000L, "A", 1, 1, 1)));

        assertThat(service.leaderboard(null, 404L).rows().getFirst().isMe()).isFalse();
    }
}
