package com.eve.own.auth.backend.domain.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.character.entity.Alliance;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CharacterStats;
import com.eve.own.auth.backend.domain.character.entity.Corporation;
import com.eve.own.auth.backend.domain.character.repository.CharacterAssetRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterLpRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterStatsRepository;
import com.eve.own.auth.backend.domain.dashboard.dto.DashboardDto;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Startseite eines Accounts")
class DashboardServiceTest {

    private static final Long MAIN_ID = 1000L;
    private static final Long ALT_ID = 1001L;
    private static final Long CORPORATION_ID = 98000001L;

    @Mock private CharacterRepository characterRepo;
    @Mock private CharacterStatsRepository statsRepo;
    @Mock private CharacterAssetRepository assetRepo;
    @Mock private CharacterLpRepository lpRepo;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        service = new DashboardService(characterRepo, statsRepo, assetRepo, lpRepo);

        Character main = character(MAIN_ID, MAIN_ID, null);
        when(characterRepo.findById(MAIN_ID)).thenReturn(Optional.of(main));
        when(characterRepo.findByMainCharacterId(MAIN_ID))
                .thenReturn(List.of(main, character(ALT_ID, MAIN_ID, null)));
        when(statsRepo.findAllById(anyList())).thenReturn(List.of());
        when(assetRepo.aggregateAssetsByGroup(anyList())).thenReturn(List.of());
        when(lpRepo.aggregateLp(anyList())).thenReturn(List.of());
    }

    private static Character character(Long id, Long mainId, Long factionId) {
        Corporation corporation = new Corporation();
        corporation.setId(CORPORATION_ID);
        corporation.setName("Corp Eins");
        corporation.setFactionId(factionId);

        Character character = new Character();
        character.setId(id);
        character.setName("Pilot " + id);
        character.setMainCharacterId(mainId);
        character.setCorporation(corporation);
        return character;
    }

    private static CharacterStats stats(Long id, Double wallet, Long skillPoints) {
        CharacterStats stats = new CharacterStats();
        stats.setCharacterId(id);
        stats.setWalletBalance(wallet);
        stats.setSkillPoints(skillPoints);
        return stats;
    }

    /** Eine Zeile der Bestandsabfrage: Gruppenname und Menge. */
    private static Object[] group(String groupName, long quantity) {
        return new Object[]{groupName, quantity};
    }

    /** Eine Zeile der Loyalitaetspunkt-Abfrage: Corporation und Punkte. */
    private static Object[] loyalty(long corporationId, long amount) {
        return new Object[]{corporationId, amount};
    }

    @Nested
    @DisplayName("Kopfdaten")
    class Header {

        @Test
        @DisplayName("nennt Charakter, Corporation und die Zahl der Charaktere")
        void showsBasics() {
            DashboardDto dashboard = service.getDashboardData(MAIN_ID);

            assertThat(dashboard.characterName()).isEqualTo("Pilot 1000");
            assertThat(dashboard.corporationName()).isEqualTo("Corp Eins");
            assertThat(dashboard.totalCharacters()).isEqualTo(2);
            assertThat(dashboard.portraitUrl()).contains("size=128");
            assertThat(dashboard.linkedCharacters()).hasSize(2);
        }

        @Test
        @DisplayName("nennt die Allianz, wenn die Corporation einer angehoert")
        void showsAlliance() {
            Alliance alliance = new Alliance();
            alliance.setId(99005338L);
            alliance.setName("Die Allianz");
            Character main = character(MAIN_ID, MAIN_ID, null);
            main.getCorporation().setAlliance(alliance);
            when(characterRepo.findById(MAIN_ID)).thenReturn(Optional.of(main));

            DashboardDto dashboard = service.getDashboardData(MAIN_ID);

            assertThat(dashboard.allianceName()).isEqualTo("Die Allianz");
            assertThat(dashboard.allianceId()).isEqualTo(99005338L);
        }

        @Test
        @DisplayName("laesst die Allianz leer, wenn es keine gibt")
        void handlesMissingAlliance() {
            DashboardDto dashboard = service.getDashboardData(MAIN_ID);

            assertThat(dashboard.allianceName()).isNull();
            assertThat(dashboard.allianceId()).isNull();
        }

        @Test
        @DisplayName("weist einen unbekannten Charakter ab")
        void rejectsUnknownCharacter() {
            when(characterRepo.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getDashboardData(404L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Vermoegen")
    class Wealth {

        @Test
        @DisplayName("summiert ISK und Skillpunkte des ganzen Accounts")
        void sumsAcrossAccount() {
            when(statsRepo.findAllById(anyList())).thenReturn(List.of(
                    stats(MAIN_ID, 1_000_000.0, 50_000_000L),
                    stats(ALT_ID, 500_000.0, 20_000_000L)));

            DashboardDto dashboard = service.getDashboardData(MAIN_ID);

            assertThat(dashboard.totalWalletBalance()).isEqualTo(1_500_000.0);
            assertThat(dashboard.totalSkillPoints()).isEqualTo(70_000_000L);
        }

        @Test
        @DisplayName("uebergeht Charaktere ohne erfasste Werte")
        void ignoresMissingValues() {
            when(statsRepo.findAllById(anyList())).thenReturn(List.of(
                    stats(MAIN_ID, null, null),
                    stats(ALT_ID, 500_000.0, 20_000_000L)));

            DashboardDto dashboard = service.getDashboardData(MAIN_ID);

            assertThat(dashboard.totalWalletBalance()).isEqualTo(500_000.0);
            assertThat(dashboard.totalSkillPoints()).isEqualTo(20_000_000L);
        }
    }

    @Nested
    @DisplayName("Bestaende")
    class Assets {

        @Test
        @DisplayName("zeigt auch leere Kaesten, damit das Raster nicht springt")
        void showsEmptyBuckets() {
            DashboardDto dashboard = service.getDashboardData(MAIN_ID);

            assertThat(dashboard.assets().subcapital())
                    .containsOnlyKeys("Frigate", "Destroyer", "Cruiser", "Battlecruiser", "Battleship")
                    .containsValue(0L);
            assertThat(dashboard.assets().capital()).isNotEmpty();
            assertThat(dashboard.assets().structures()).isNotEmpty();
        }

        @Test
        @DisplayName("addiert verwandte SDE-Gruppen in denselben Kasten")
        void mergesRelatedGroups() {
            when(assetRepo.aggregateAssetsByGroup(anyList())).thenReturn(List.of(
                    group("Frigate", 3), group("Assault Frigate", 2), group("Interceptor", 1)));

            assertThat(service.getDashboardData(MAIN_ID).assets().subcapital())
                    .containsEntry("Frigate", 6L);
        }

        @Test
        @DisplayName("laesst Gruppen ohne Kasten aussen vor")
        void ignoresUnmappedGroups() {
            when(assetRepo.aggregateAssetsByGroup(anyList())).thenReturn(List.of(
                    group("Ammunition", 1000), group("Cruiser", 2)));

            var subcapital = service.getDashboardData(MAIN_ID).assets().subcapital();
            assertThat(subcapital).containsEntry("Cruiser", 2L);
            assertThat(subcapital).doesNotContainKey("Ammunition");
        }

        @Test
        @DisplayName("sortiert Strukturen und Handelsgueter in ihre eigenen Kaesten")
        void sortsStructuresAndNotables() {
            when(assetRepo.aggregateAssetsByGroup(anyList())).thenReturn(List.of(
                    group("Citadel", 1), group("Large Skill Injector", 4)));

            DashboardDto dashboard = service.getDashboardData(MAIN_ID);

            assertThat(dashboard.assets().structures()).containsEntry("Citadel", 1L);
            assertThat(dashboard.assets().notable()).containsEntry("Skill Injector", 4L);
        }
    }

    @Nested
    @DisplayName("Zugehoerigkeiten")
    class Affiliations {

        @Test
        @DisplayName("zaehlt die Charaktere je Miliz")
        void countsMilitias() {
            Character amarr = character(MAIN_ID, MAIN_ID, 500007L);
            Character alt = character(ALT_ID, MAIN_ID, 500007L);
            when(characterRepo.findById(MAIN_ID)).thenReturn(Optional.of(amarr));
            when(characterRepo.findByMainCharacterId(MAIN_ID)).thenReturn(List.of(amarr, alt));

            assertThat(service.getDashboardData(MAIN_ID).affiliations().militias())
                    .containsEntry("Amarr", 2L)
                    .containsEntry("Gallente", 0L);
        }

        @Test
        @DisplayName("ignoriert Fraktionen ausserhalb der Milizen")
        void ignoresUnknownFactions() {
            Character exotic = character(MAIN_ID, MAIN_ID, 999999L);
            when(characterRepo.findById(MAIN_ID)).thenReturn(Optional.of(exotic));
            when(characterRepo.findByMainCharacterId(MAIN_ID)).thenReturn(List.of(exotic));

            assertThat(service.getDashboardData(MAIN_ID).affiliations().militias())
                    .containsValue(0L);
        }

        @Test
        @DisplayName("weist Paragon-Punkte als Evermarks getrennt aus")
        void separatesEvermarks() {
            when(lpRepo.aggregateLp(anyList())).thenReturn(List.of(
                    loyalty(1000419L, 5000), loyalty(1000125L, 3000)));

            var affiliations = service.getDashboardData(MAIN_ID).affiliations();

            assertThat(affiliations.evermarks()).isEqualTo(5000L);
            assertThat(affiliations.loyaltyPoints()).containsEntry("CONCORD", 3000L);
        }

        @Test
        @DisplayName("summiert alle Punkte, auch die nicht einzeln ausgewiesenen")
        void sumsAllLoyaltyPoints() {
            when(lpRepo.aggregateLp(anyList())).thenReturn(List.of(
                    loyalty(1000125L, 3000), loyalty(999999L, 1000)));

            assertThat(service.getDashboardData(MAIN_ID).affiliations().loyaltyPoints())
                    .containsEntry("Total", 4000L);
        }

        @Test
        @DisplayName("stellt die Gesamtsumme vor die einzelnen Corporations")
        void putsTotalFirst() {
            assertThat(service.getDashboardData(MAIN_ID).affiliations().loyaltyPoints().keySet())
                    .first()
                    .isEqualTo("Total");
        }

        @Test
        @DisplayName("kommt mit einem Charakter ohne Corporation in der Liste zurecht")
        void toleratesCharacterWithoutCorporation() {
            Character noCorp = new Character();
            noCorp.setId(ALT_ID);
            noCorp.setMainCharacterId(MAIN_ID);
            when(characterRepo.findByMainCharacterId(MAIN_ID))
                    .thenReturn(List.of(character(MAIN_ID, MAIN_ID, 500007L), noCorp));

            assertThat(service.getDashboardData(MAIN_ID).affiliations().militias())
                    .containsEntry("Amarr", 1L);
        }
    }
}
