package com.eve.own.auth.backend.domain.fleet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.fleet.dto.ReadinessDtos;
import com.eve.own.auth.backend.domain.fleet.entity.FleetDoctrine;
import com.eve.own.auth.backend.domain.fleet.repository.FleetDoctrineRepository;
import com.eve.own.auth.backend.domain.fleet.repository.ReadinessQueryRepository;
import com.eve.own.auth.backend.testsupport.FakeTuple;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Das Readiness-Board beantwortet: wer kann diese Doktrin heute wirklich
 * fliegen? Dafuer muessen Hangar <em>und</em> Skills stimmen.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Doktrin-Bereitschaft")
class FleetReadinessServiceTest {

    private static final Long MAIN_ID = 1000L;
    private static final Long ALT_ID = 1001L;
    private static final Long NESTOR = 33472L;

    @Mock private FleetDoctrineRepository doctrineRepo;
    @Mock private ReadinessQueryRepository queryRepo;
    @Mock private EftParserService eftParser;

    private FleetReadinessService service;

    @BeforeEach
    void setUp() {
        service = new FleetReadinessService(doctrineRepo, queryRepo, eftParser);

        when(doctrineRepo.findAll()).thenReturn(List.of(doctrine("Armor", NESTOR, "Nestor")));
        when(queryRepo.resolveTypesByName(anyList())).thenReturn(List.of());
        when(queryRepo.accountRoster()).thenReturn(List.of());
        when(queryRepo.hullOwnership(anyList())).thenReturn(List.of());
        when(queryRepo.skillRequirements(anyList())).thenReturn(List.of());
        when(queryRepo.skillGaps(anyList())).thenReturn(List.of());
        when(queryRepo.charactersWithSkillData()).thenReturn(List.of());
    }

    private static FleetDoctrine doctrine(String name, Long shipTypeId, String shipType) {
        FleetDoctrine fit = new FleetDoctrine();
        fit.setDoctrineName(name);
        fit.setShipTypeId(shipTypeId);
        fit.setShipType(shipType);
        fit.setName("Standard-Fit");
        return fit;
    }

    /** Ein Account mit Main und optionalem Alt. */
    private void rosterOf(Long... characterIds) {
        List<jakarta.persistence.Tuple> rows = new java.util.ArrayList<>();
        for (Long characterId : characterIds) {
            rows.add(FakeTuple.of(
                    "mainId", MAIN_ID, "mainName", "Der Main", "corporationName", "Corp",
                    "characterId", characterId, "characterName", "Pilot " + characterId));
        }
        when(queryRepo.accountRoster()).thenReturn(rows);
    }

    private void ownsHull(Long characterId, long quantity) {
        when(queryRepo.hullOwnership(anyList())).thenReturn(List.of(FakeTuple.of(
                "characterId", characterId, "typeId", NESTOR, "quantity", quantity)));
    }

    private void hullRequiresSkills(int count) {
        List<jakarta.persistence.Tuple> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(FakeTuple.of("typeId", NESTOR, "skillTypeId", (long) i,
                    "skillName", "Skill " + i, "requiredLevel", 3L));
        }
        when(queryRepo.skillRequirements(anyList())).thenReturn(rows);
    }

    private void hasSkillData(Long... characterIds) {
        when(queryRepo.charactersWithSkillData()).thenReturn(List.of(characterIds));
    }

    private void missesSkill(Long characterId, long skillTypeId) {
        when(queryRepo.skillGaps(anyList())).thenReturn(List.of(FakeTuple.of(
                "characterId", characterId, "typeId", NESTOR, "skillTypeId", skillTypeId,
                "skillName", "Skill " + skillTypeId, "requiredLevel", 3L, "currentLevel", 1L)));
    }

    private ReadinessDtos.HullReadinessDto board() {
        return service.checkReadiness("Armor").hulls().getFirst();
    }

    @Nested
    @DisplayName("Doktrin-Auswahl")
    class DoctrineSelection {

        @Test
        @DisplayName("listet die Doktrinen ohne Dubletten und ohne Leereintraege")
        void listsDoctrineNames() {
            when(doctrineRepo.findAll()).thenReturn(List.of(
                    doctrine("Shield", 1L, "A"), doctrine("Armor", 2L, "B"),
                    doctrine("Armor", 3L, "C"), doctrine("  ", 4L, "D"), doctrine(null, 5L, "E")));

            assertThat(service.doctrineNames()).containsExactly("Armor", "Shield");
        }

        @Test
        @DisplayName("fuehrt unterschiedlich geschriebene Namen getrennt auf")
        void listsCaseVariantsSeparately() {
            // Bekannte Eigenheit: die Liste unterscheidet Gross- und Kleinschreibung,
            // der Filter beim Abruf tut es nicht. Beide Eintraege liefern also
            // dasselbe Board.
            when(doctrineRepo.findAll()).thenReturn(List.of(
                    doctrine("Armor", 1L, "A"), doctrine("armor", 2L, "B")));

            assertThat(service.doctrineNames()).containsExactlyInAnyOrder("Armor", "armor");
        }

        @Test
        @DisplayName("meldet ein leeres Board, wenn die Doktrin keine Huellen hat")
        void emptyBoardWithoutHulls() {
            when(doctrineRepo.findAll()).thenReturn(List.of());

            ReadinessDtos.DoctrineReadinessDto readiness = service.checkReadiness("Leer");

            assertThat(readiness.hulls()).isEmpty();
            assertThat(readiness.hullsChecked()).isZero();
        }

        @Test
        @DisplayName("loest einen fehlenden Schiffstyp ueber den Namen auf")
        void resolvesMissingShipTypeIdByName() {
            when(doctrineRepo.findAll()).thenReturn(List.of(doctrine("Armor", null, "Nestor")));
            when(queryRepo.resolveTypesByName(anyList())).thenReturn(List.of(
                    FakeTuple.of("lookup", "nestor", "typeId", NESTOR)));

            assertThat(service.checkReadiness("Armor").hulls()).singleElement()
                    .satisfies(hull -> assertThat(hull.typeId()).isEqualTo(NESTOR));
        }

        @Test
        @DisplayName("laesst ein Fitting ohne aufloesbaren Schiffstyp weg")
        void skipsUnresolvableShip() {
            when(doctrineRepo.findAll()).thenReturn(List.of(doctrine("Armor", null, "Erfunden")));

            assertThat(service.checkReadiness("Armor").hulls()).isEmpty();
        }

        @Test
        @DisplayName("sammelt mehrere Fittings derselben Huelle unter einem Eintrag")
        void mergesFitsOfSameHull() {
            FleetDoctrine erstes = doctrine("Armor", NESTOR, "Nestor");
            erstes.setName("Logi A");
            FleetDoctrine zweites = doctrine("Armor", NESTOR, "Nestor");
            zweites.setName("Logi B");
            when(doctrineRepo.findAll()).thenReturn(List.of(erstes, zweites));

            // Beide Fittings fliegen dieselbe Huelle - im Board steht sie einmal.
            assertThat(service.checkReadiness("Armor").hulls()).singleElement()
                    .satisfies(hull -> assertThat(hull.typeId()).isEqualTo(NESTOR));
        }
    }

    @Nested
    @DisplayName("Bereitschaft eines Accounts")
    class AccountReadiness {

        @Test
        @DisplayName("gilt als bereit, wenn Schiff und Skills vorhanden sind")
        void readyWithShipAndSkills() {
            rosterOf(MAIN_ID);
            ownsHull(MAIN_ID, 1);
            hullRequiresSkills(2);
            hasSkillData(MAIN_ID);

            ReadinessDtos.HullReadinessDto hull = board();

            assertThat(hull.ready()).singleElement()
                    .satisfies(account -> {
                        assertThat(account.isReady()).isTrue();
                        assertThat(account.hasShip()).isTrue();
                        assertThat(account.hasSkills()).isTrue();
                        assertThat(account.pilotsCapable()).isEqualTo(1);
                    });
            assertThat(hull.notReady()).isEmpty();
            assertThat(hull.coverage()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("gilt als nicht bereit, wenn das Schiff fehlt")
        void notReadyWithoutShip() {
            rosterOf(MAIN_ID);
            hullRequiresSkills(1);
            hasSkillData(MAIN_ID);

            ReadinessDtos.HullReadinessDto hull = board();

            assertThat(hull.notReady()).singleElement()
                    .satisfies(account -> {
                        assertThat(account.hasShip()).isFalse();
                        assertThat(account.hasSkills()).isTrue();
                    });
        }

        @Test
        @DisplayName("gilt als nicht bereit, wenn ein Skill fehlt")
        void notReadyWithSkillGap() {
            rosterOf(MAIN_ID);
            ownsHull(MAIN_ID, 1);
            hullRequiresSkills(2);
            hasSkillData(MAIN_ID);
            missesSkill(MAIN_ID, 2L);

            ReadinessDtos.HullReadinessDto hull = board();

            assertThat(hull.notReady()).singleElement().satisfies(account -> {
                assertThat(account.hasShip()).isTrue();
                assertThat(account.hasSkills()).isFalse();
                assertThat(account.bestSkillsMet()).isEqualTo(1);
                assertThat(account.skillsRequired()).isEqualTo(2);
            });
        }

        @Test
        @DisplayName("unterscheidet 'kann nicht' von 'keine Skill-Daten'")
        void distinguishesMissingDataFromMissingSkills() {
            // Ein Charakter, dessen Skills nie gesynct wurden, darf nicht als
            // unfaehig dastehen - die Anwendung weiss es schlicht nicht.
            rosterOf(MAIN_ID);
            ownsHull(MAIN_ID, 1);
            hullRequiresSkills(1);

            ReadinessDtos.HullReadinessDto hull = board();

            assertThat(hull.notReady()).singleElement()
                    .satisfies(account -> assertThat(account.skillDataAvailable()).isFalse());
        }

        @Test
        @DisplayName("zaehlt einen Account als bereit, sobald ein Charakter es kann")
        void oneCapableCharacterIsEnough() {
            rosterOf(MAIN_ID, ALT_ID);
            ownsHull(ALT_ID, 1);
            hullRequiresSkills(1);
            hasSkillData(ALT_ID);

            ReadinessDtos.HullReadinessDto hull = board();

            assertThat(hull.ready()).hasSize(1);
            assertThat(hull.ready().getFirst().charactersOwning()).isEqualTo(1);
        }

        @Test
        @DisplayName("summiert die Huellen ueber alle Charaktere des Accounts")
        void sumsHullsAcrossAccount() {
            rosterOf(MAIN_ID, ALT_ID);
            when(queryRepo.hullOwnership(anyList())).thenReturn(List.of(
                    FakeTuple.of("characterId", MAIN_ID, "typeId", NESTOR, "quantity", 2L),
                    FakeTuple.of("characterId", ALT_ID, "typeId", NESTOR, "quantity", 3L)));
            hasSkillData(MAIN_ID);

            assertThat(board().hullsTotal()).isEqualTo(5L);
        }

        @Test
        @DisplayName("stellt bei den Nicht-Bereiten die naechstliegenden nach oben")
        void sortsNotReadyByProximity() {
            when(queryRepo.accountRoster()).thenReturn(List.of(
                    FakeTuple.of("mainId", 1L, "mainName", "Ohne alles", "corporationName", "C",
                            "characterId", 1L, "characterName", "A"),
                    FakeTuple.of("mainId", 2L, "mainName", "Hat Schiff", "corporationName", "C",
                            "characterId", 2L, "characterName", "B")));
            when(queryRepo.hullOwnership(anyList())).thenReturn(List.of(
                    FakeTuple.of("characterId", 2L, "typeId", NESTOR, "quantity", 1L)));
            hullRequiresSkills(1);

            assertThat(board().notReady())
                    .extracting(ReadinessDtos.AccountReadinessDto::mainName)
                    .containsExactly("Hat Schiff", "Ohne alles");
        }

        @Test
        @DisplayName("meldet je Charakter die konkret fehlenden Skills")
        void reportsSkillGapsPerCharacter() {
            rosterOf(MAIN_ID);
            ownsHull(MAIN_ID, 1);
            hullRequiresSkills(2);
            hasSkillData(MAIN_ID);
            missesSkill(MAIN_ID, 2L);

            ReadinessDtos.CharacterReadinessDto pilot =
                    board().notReady().getFirst().characters().getFirst();

            assertThat(pilot.missingSkills()).singleElement().satisfies(gap -> {
                assertThat(gap.skillName()).isEqualTo("Skill 2");
                assertThat(gap.requiredLevel()).isEqualTo(3);
                assertThat(gap.currentLevel()).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("markiert den Main innerhalb des Accounts")
        void marksMainCharacter() {
            rosterOf(MAIN_ID, ALT_ID);
            hullRequiresSkills(1);

            List<ReadinessDtos.CharacterReadinessDto> characters =
                    board().notReady().getFirst().characters();

            assertThat(characters).filteredOn(ReadinessDtos.CharacterReadinessDto::main)
                    .singleElement()
                    .satisfies(main -> assertThat(main.characterId()).isEqualTo(MAIN_ID));
        }

        @Test
        @DisplayName("meldet ohne jeden Account eine Abdeckung von null")
        void zeroCoverageWithoutAccounts() {
            hullRequiresSkills(1);

            ReadinessDtos.HullReadinessDto hull = board();

            assertThat(hull.accountsTotal()).isZero();
            assertThat(hull.coverage()).isZero();
        }
    }

    @Nested
    @DisplayName("EFT-Sandbox")
    class Sandbox {

        @Test
        @DisplayName("wertet ein eingefuegtes Fitting wie eine Doktrin aus")
        void evaluatesPastedFit() {
            when(eftParser.parseAndResolve(anyString())).thenReturn(new ReadinessDtos.ParsedFitDto(
                    NESTOR, "Nestor", "Mein Fit", "icon", "render", 3, List.of(), List.of()));
            rosterOf(MAIN_ID);
            ownsHull(MAIN_ID, 1);
            hasSkillData(MAIN_ID);

            ReadinessDtos.SandboxResultDto result = service.sandbox("[Nestor, Mein Fit]");

            assertThat(result.fit().shipTypeName()).isEqualTo("Nestor");
            assertThat(result.board().typeName()).isEqualTo("Nestor");
            assertThat(result.board().ready()).hasSize(1);
        }

        @Test
        @DisplayName("kommt mit einem Fitting ohne Namen zurecht")
        void handlesFitWithoutName() {
            when(eftParser.parseAndResolve(anyString())).thenReturn(new ReadinessDtos.ParsedFitDto(
                    NESTOR, "Nestor", null, "icon", "render", 0, List.of(), List.of()));

            assertThat(service.sandbox("[Nestor, ]").board().typeId()).isEqualTo(NESTOR);
        }
    }
}
