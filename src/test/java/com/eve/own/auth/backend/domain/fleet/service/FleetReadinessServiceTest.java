package com.eve.own.auth.backend.domain.fleet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.fleet.dto.ReadinessDtos;
import com.eve.own.auth.backend.domain.fleet.dto.SkillPlanDtos;
import com.eve.own.auth.backend.domain.fleet.entity.FleetDoctrine;
import com.eve.own.auth.backend.domain.fleet.repository.FleetDoctrineRepository;
import com.eve.own.auth.backend.domain.fleet.repository.ReadinessQueryRepository;
import com.eve.own.auth.backend.testsupport.FakeTuple;
import jakarta.persistence.Tuple;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
 * fliegen? Geprueft wird der ganze Fit - der Hangar auf die Huelle, die
 * Skills auf Huelle, Module, Drohnen und Ladung.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Doktrin-Bereitschaft")
class FleetReadinessServiceTest {

    private static final Long MAIN_ID = 1000L;
    private static final Long ALT_ID = 1001L;
    private static final Long NESTOR = 33472L;
    private static final Long LAUNCHER = 2404L;
    private static final Long AMMO = 24492L;

    @Mock private FleetDoctrineRepository doctrineRepo;
    @Mock private ReadinessQueryRepository queryRepo;
    @Mock private EftParserService eftParser;
    @Mock private SkillPlanService skillPlanService;

    private FleetReadinessService service;

    /** Anforderungen und Luecken werden ueber mehrere Aufrufe hinweg gesammelt. */
    private final List<Tuple> requirementRows = new ArrayList<>();
    private final List<Tuple> gapRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new FleetReadinessService(doctrineRepo, queryRepo, eftParser, skillPlanService);
        requirementRows.clear();
        gapRows.clear();

        when(doctrineRepo.findAll()).thenReturn(List.of(doctrine("Armor", NESTOR, "Nestor")));
        when(queryRepo.resolveTypesByName(anyList())).thenReturn(List.of());
        when(queryRepo.accountRoster()).thenReturn(List.of());
        when(queryRepo.hullOwnership(anyList(), anyList())).thenReturn(List.of());
        when(queryRepo.skillRequirements(anyList())).thenReturn(requirementRows);
        when(queryRepo.skillGaps(anyList(), anyList())).thenReturn(gapRows);
        when(queryRepo.skillLevels(anyList(), anyList())).thenReturn(List.of());
        when(skillPlanService.skillsByDoctrine(any())).thenReturn(Map.of());
        when(queryRepo.charactersWithSkillData()).thenReturn(List.of());
    }

    // ==================================================================
    // Aufbau
    // ==================================================================

    private static FleetDoctrine doctrine(String name, Long shipTypeId, String shipType) {
        FleetDoctrine fit = new FleetDoctrine();
        fit.setDoctrineName(name);
        fit.setShipTypeId(shipTypeId);
        fit.setShipType(shipType);
        fit.setName("Standard-Fit");
        return fit;
    }

    /** Ein Doktrin-Eintrag mit hinterlegtem EFT-Text - er wird geparst. */
    private static FleetDoctrine doctrineWithEft(String doctrineName, String fitName) {
        FleetDoctrine fit = doctrine(doctrineName, NESTOR, "Nestor");
        fit.setName(fitName);
        fit.setEftString("[Nestor, " + fitName + "]");
        return fit;
    }

    /** Setzt die ID einer Doktrin-Zeile - sie ist der Schluessel zum Skillplan. */
    private static FleetDoctrine withId(FleetDoctrine fit, Long id) {
        fit.setId(id);
        return fit;
    }

    /** Ein geparstes Fitting mit den genannten Modulen in einer Slot-Gruppe. */
    private static ReadinessDtos.ParsedFitDto parsedFit(String fitName, Long... moduleTypeIds) {
        List<ReadinessDtos.FitModuleDto> modules = Arrays.stream(moduleTypeIds)
                .map(typeId -> new ReadinessDtos.FitModuleDto(
                        typeId, "Modul " + typeId, "icon", 1, null, null))
                .toList();
        return new ReadinessDtos.ParsedFitDto(
                NESTOR, "Nestor", fitName, "icon", "render", modules.size(),
                modules.isEmpty()
                        ? List.of()
                        : List.of(new ReadinessDtos.FitSlotGroupDto(
                                "High Slots", "icon", modules.size(), modules)),
                List.of());
    }

    /** Ein Account mit Main und optionalen Alts. */
    private void rosterOf(Long... characterIds) {
        List<Tuple> rows = new ArrayList<>();
        for (Long characterId : characterIds) {
            rows.add(FakeTuple.of(
                    "mainId", MAIN_ID, "mainName", "Der Main", "corporationName", "Corp",
                    "characterId", characterId, "characterName", "Pilot " + characterId));
        }
        when(queryRepo.accountRoster()).thenReturn(rows);
    }

    private void ownsHull(Long characterId, long quantity) {
        when(queryRepo.hullOwnership(anyList(), anyList())).thenReturn(List.of(FakeTuple.of(
                "characterId", characterId, "typeId", NESTOR, "quantity", quantity)));
    }

    private void hullRequiresSkills(int count) {
        for (int i = 1; i <= count; i++) {
            requires(NESTOR, i, 3);
        }
    }

    /** Ein Typ verlangt einen Skill auf einer Stufe. */
    private void requires(Long typeId, long skillTypeId, int level) {
        requirementRows.add(FakeTuple.of("typeId", typeId, "skillTypeId", skillTypeId,
                "skillName", "Skill " + skillTypeId, "requiredLevel", (long) level));
    }

    /** Einem Charakter fehlt ein von diesem Typ verlangter Skill. */
    private void misses(Long characterId, Long typeId, long skillTypeId, int required, int current) {
        gapRows.add(FakeTuple.of(
                "characterId", characterId, "typeId", typeId, "skillTypeId", skillTypeId,
                "skillName", "Skill " + skillTypeId,
                "requiredLevel", (long) required, "currentLevel", (long) current));
    }

    private void hasSkillData(Long... characterIds) {
        when(queryRepo.charactersWithSkillData()).thenReturn(List.of(characterIds));
    }

    private void missesSkill(Long characterId, long skillTypeId) {
        misses(characterId, NESTOR, skillTypeId, 3, 1);
    }

    private ReadinessDtos.FitReadinessDto board() {
        return service.checkReadiness("Armor").fits().getFirst();
    }

    // ==================================================================
    // Tests
    // ==================================================================

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
        @DisplayName("meldet ein leeres Board, wenn die Doktrin keine Fittings hat")
        void emptyBoardWithoutFits() {
            when(doctrineRepo.findAll()).thenReturn(List.of());

            ReadinessDtos.DoctrineReadinessDto readiness = service.checkReadiness("Leer");

            assertThat(readiness.fits()).isEmpty();
            assertThat(readiness.fitsChecked()).isZero();
        }

        @Test
        @DisplayName("loest einen fehlenden Schiffstyp ueber den Namen auf")
        void resolvesMissingShipTypeIdByName() {
            when(doctrineRepo.findAll()).thenReturn(List.of(doctrine("Armor", null, "Nestor")));
            when(queryRepo.resolveTypesByName(anyList())).thenReturn(List.of(
                    FakeTuple.of("lookup", "nestor", "typeId", NESTOR)));

            assertThat(service.checkReadiness("Armor").fits()).singleElement()
                    .satisfies(fit -> assertThat(fit.typeId()).isEqualTo(NESTOR));
        }

        @Test
        @DisplayName("laesst ein Fitting ohne aufloesbaren Schiffstyp weg")
        void skipsUnresolvableShip() {
            when(doctrineRepo.findAll()).thenReturn(List.of(doctrine("Armor", null, "Erfunden")));

            assertThat(service.checkReadiness("Armor").fits()).isEmpty();
        }

        @Test
        @DisplayName("fuehrt zwei Fittings derselben Huelle getrennt auf")
        void keepsFitsOfSameHullApart() {
            // Frueher wurden sie zu einem Eintrag verschmolzen. Das geht nicht
            // mehr: dieselbe Huelle mit anderen Modulen ist eine andere
            // Anforderung - wer das eine Fitting stellen kann, kann das andere
            // womoeglich nicht.
            FleetDoctrine erstes = doctrineWithEft("Armor", "Logi A");
            FleetDoctrine zweites = doctrineWithEft("Armor", "Logi B");
            erstes.setId(1L);
            zweites.setId(2L);
            when(doctrineRepo.findAll()).thenReturn(List.of(erstes, zweites));
            when(eftParser.parseAndResolve("[Nestor, Logi A]")).thenReturn(parsedFit("Logi A", LAUNCHER));
            when(eftParser.parseAndResolve("[Nestor, Logi B]")).thenReturn(parsedFit("Logi B", AMMO));

            assertThat(service.checkReadiness("Armor").fits())
                    .hasSize(2)
                    .extracting(ReadinessDtos.FitReadinessDto::fitName)
                    .containsExactly("Logi A", "Logi B");
        }
    }

    @Nested
    @DisplayName("Der komplette Fit")
    class CompleteFit {

        @Test
        @DisplayName("zaehlt die Skills der Module zu denen des Rumpfs")
        void addsModuleSkillsToHullSkills() {
            when(doctrineRepo.findAll()).thenReturn(List.of(doctrineWithEft("Armor", "Raketen")));
            when(eftParser.parseAndResolve(anyString())).thenReturn(parsedFit("Raketen", LAUNCHER));
            requires(NESTOR, 1L, 3);
            requires(LAUNCHER, 2L, 4);

            ReadinessDtos.FitReadinessDto fit = board();

            assertThat(fit.requiredSkills()).hasSize(2);
            assertThat(fit.hullSkillsRequired()).isEqualTo(1);
            assertThat(fit.moduleCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("nimmt bei doppeltem Skill die hoehere Anforderung")
        void takesTheHigherRequirement() {
            // Rumpf und Werfer verlangen denselben Skill auf verschiedenen Stufen.
            // Die niedrigere zu nehmen waere ein zu mildes Urteil.
            when(doctrineRepo.findAll()).thenReturn(List.of(doctrineWithEft("Armor", "Raketen")));
            when(eftParser.parseAndResolve(anyString())).thenReturn(parsedFit("Raketen", LAUNCHER));
            requires(NESTOR, 7L, 2);
            requires(LAUNCHER, 7L, 5);

            assertThat(board().requiredSkills()).singleElement()
                    .satisfies(skill -> assertThat(skill.level()).isEqualTo(5));
        }

        @Test
        @DisplayName("zaehlt denselben Modultyp nur einmal")
        void countsARepeatedModuleOnce() {
            when(doctrineRepo.findAll()).thenReturn(List.of(doctrineWithEft("Armor", "Raketen")));
            when(eftParser.parseAndResolve(anyString()))
                    .thenReturn(parsedFit("Raketen", LAUNCHER, LAUNCHER, LAUNCHER));
            requires(LAUNCHER, 2L, 4);

            assertThat(board().requiredSkills()).hasSize(1);
        }

        @Test
        @DisplayName("prueft auch die geladene Munition")
        void checksLoadedCharges() {
            ReadinessDtos.FitModuleDto werfer = new ReadinessDtos.FitModuleDto(
                    LAUNCHER, "Werfer", "icon", 1, "Scourge Rage", AMMO);
            when(doctrineRepo.findAll()).thenReturn(List.of(doctrineWithEft("Armor", "Raketen")));
            when(eftParser.parseAndResolve(anyString())).thenReturn(new ReadinessDtos.ParsedFitDto(
                    NESTOR, "Nestor", "Raketen", "icon", "render", 1,
                    List.of(new ReadinessDtos.FitSlotGroupDto("High Slots", "icon", 1, List.of(werfer))),
                    List.of()));
            requires(AMMO, 9L, 3);

            assertThat(board().requiredSkills())
                    .extracting(ReadinessDtos.RequiredSkillDto::skillTypeId)
                    .containsExactly(9L);
        }

        @Test
        @DisplayName("laesst einen Piloten an einem fehlenden Modul-Skill scheitern")
        void moduleSkillGapBlocksReadiness() {
            when(doctrineRepo.findAll()).thenReturn(List.of(doctrineWithEft("Armor", "Raketen")));
            when(eftParser.parseAndResolve(anyString())).thenReturn(parsedFit("Raketen", LAUNCHER));
            rosterOf(MAIN_ID);
            ownsHull(MAIN_ID, 1);
            hasSkillData(MAIN_ID);
            requires(NESTOR, 1L, 3);
            requires(LAUNCHER, 2L, 4);
            misses(MAIN_ID, LAUNCHER, 2L, 4, 2);

            ReadinessDtos.FitReadinessDto fit = board();

            assertThat(fit.ready()).isEmpty();
            assertThat(fit.notReady()).singleElement()
                    .satisfies(account -> assertThat(account.isReady()).isFalse());
        }

        @Test
        @DisplayName("trennt 'kann den Rumpf nicht fliegen' von 'nur Modul-Skills fehlen'")
        void separatesHullFromModuleGaps() {
            // Der Unterschied entscheidet, ob jemand in Tagen einsatzbereit ist
            // oder von vorn anfangen muss.
            when(doctrineRepo.findAll()).thenReturn(List.of(doctrineWithEft("Armor", "Raketen")));
            when(eftParser.parseAndResolve(anyString())).thenReturn(parsedFit("Raketen", LAUNCHER));
            rosterOf(MAIN_ID);
            ownsHull(MAIN_ID, 1);
            hasSkillData(MAIN_ID);
            requires(NESTOR, 1L, 3);
            requires(LAUNCHER, 2L, 4);
            misses(MAIN_ID, LAUNCHER, 2L, 4, 2);

            ReadinessDtos.CharacterReadinessDto pilot =
                    board().notReady().getFirst().characters().getFirst();

            assertThat(pilot.canFly()).isFalse();
            assertThat(pilot.canFlyHull()).isTrue();
            assertThat(pilot.missingSkills()).singleElement()
                    .satisfies(gap -> assertThat(gap.skillTypeId()).isEqualTo(2L));
        }

        @Test
        @DisplayName("meldet auch den Rumpf als nicht fliegbar, wenn dessen Skill fehlt")
        void reportsHullGapAsSuch() {
            when(doctrineRepo.findAll()).thenReturn(List.of(doctrineWithEft("Armor", "Raketen")));
            when(eftParser.parseAndResolve(anyString())).thenReturn(parsedFit("Raketen", LAUNCHER));
            rosterOf(MAIN_ID);
            hasSkillData(MAIN_ID);
            requires(NESTOR, 1L, 3);
            misses(MAIN_ID, NESTOR, 1L, 3, 0);

            ReadinessDtos.CharacterReadinessDto pilot =
                    board().notReady().getFirst().characters().getFirst();

            assertThat(pilot.canFlyHull()).isFalse();
            assertThat(pilot.canFly()).isFalse();
        }

        @Test
        @DisplayName("faellt bei unlesbarem Fitting auf die Huelle zurueck und sagt es")
        void fallsBackToHullOnUnreadableFit() {
            // Ein kaputter EFT-Text darf das Schiff nicht aus der Doktrin
            // verschwinden lassen - aber der Nutzer muss erfahren, dass die
            // Module ungeprueft blieben.
            when(doctrineRepo.findAll()).thenReturn(List.of(doctrineWithEft("Armor", "Kaputt")));
            when(eftParser.parseAndResolve(anyString()))
                    .thenThrow(new IllegalArgumentException("Unbekannter Schiffstyp: \"Xyz\""));

            ReadinessDtos.FitReadinessDto fit = board();

            assertThat(fit.typeId()).isEqualTo(NESTOR);
            assertThat(fit.moduleCount()).isZero();
            assertThat(fit.unresolved()).singleElement()
                    .satisfies(reason -> assertThat(reason).contains("Unbekannter Schiffstyp"));
        }

        @Test
        @DisplayName("reicht nicht aufloesbare Module als ungeprueft durch")
        void passesThroughUnresolvedModules() {
            when(doctrineRepo.findAll()).thenReturn(List.of(doctrineWithEft("Armor", "Raketen")));
            when(eftParser.parseAndResolve(anyString())).thenReturn(new ReadinessDtos.ParsedFitDto(
                    NESTOR, "Nestor", "Raketen", "icon", "render", 0, List.of(),
                    List.of("Erfundenes Modul")));

            assertThat(board().unresolved()).containsExactly("Erfundenes Modul");
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

            ReadinessDtos.FitReadinessDto fit = board();

            assertThat(fit.ready()).singleElement()
                    .satisfies(account -> {
                        assertThat(account.isReady()).isTrue();
                        assertThat(account.hasShip()).isTrue();
                        assertThat(account.hasSkills()).isTrue();
                        assertThat(account.pilotsCapable()).isEqualTo(1);
                    });
            assertThat(fit.notReady()).isEmpty();
            assertThat(fit.coverage()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("gilt als nicht bereit, wenn das Schiff fehlt")
        void notReadyWithoutShip() {
            rosterOf(MAIN_ID);
            hullRequiresSkills(1);
            hasSkillData(MAIN_ID);

            assertThat(board().notReady()).singleElement()
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

            assertThat(board().notReady()).singleElement().satisfies(account -> {
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

            assertThat(board().notReady()).singleElement()
                    .satisfies(account -> assertThat(account.skillDataAvailable()).isFalse());
        }

        @Test
        @DisplayName("zaehlt einen Account als bereit, sobald ein Charakter es kann")
        void oneCapableCharacterIsEnough() {
            rosterOf(MAIN_ID, ALT_ID);
            ownsHull(ALT_ID, 1);
            hullRequiresSkills(1);
            hasSkillData(ALT_ID);

            ReadinessDtos.FitReadinessDto fit = board();

            assertThat(fit.ready()).hasSize(1);
            assertThat(fit.ready().getFirst().charactersOwning()).isEqualTo(1);
        }

        @Test
        @DisplayName("gilt als nicht bereit, wenn Schiff und Skills auf verschiedenen Charakteren liegen")
        void notReadyWhenShipAndSkillsAreOnDifferentCharacters() {
            // Der Main hat das Schiff, kann es aber nicht fliegen; der Alt kann
            // fliegen, hat aber keins. Zusammengezaehlt saehe das nach
            // Einsatzbereitschaft aus - undocken kann trotzdem niemand.
            rosterOf(MAIN_ID, ALT_ID);
            when(queryRepo.hullOwnership(anyList(), anyList())).thenReturn(List.of(
                    FakeTuple.of("characterId", MAIN_ID, "typeId", NESTOR, "quantity", 1L)));
            hasSkillData(MAIN_ID, ALT_ID);
            hullRequiresSkills(1);
            missesSkill(MAIN_ID, 1L);

            ReadinessDtos.FitReadinessDto fit = board();

            assertThat(fit.ready()).isEmpty();
            assertThat(fit.accountsReady()).isZero();
            assertThat(fit.notReady()).singleElement().satisfies(account -> {
                // Beide Teilangaben bleiben wahr - nur zusammen ergeben sie nichts.
                assertThat(account.hasShip()).isTrue();
                assertThat(account.hasSkills()).isTrue();
                assertThat(account.isReady()).isFalse();
            });
        }

        @Test
        @DisplayName("gilt als bereit, wenn ein Charakter Schiff und Skills zugleich hat")
        void readyWhenOneCharacterHasBoth() {
            rosterOf(MAIN_ID, ALT_ID);
            ownsHull(ALT_ID, 1);
            hasSkillData(ALT_ID);
            hullRequiresSkills(1);

            assertThat(board().ready()).singleElement()
                    .satisfies(account -> assertThat(account.isReady()).isTrue());
        }

        @Test
        @DisplayName("stellt bei gleichem Schiffsstand den mit den Skills nach oben")
        void sortsNotReadyBySkillsWhenShipIsEqual() {
            // Deckt das zweite Sortierkriterium ab. Eine Kette aus mehrfachem
            // reversed() drehte hier zuvor die Reihenfolge um, weil jedes
            // reversed() den gesamten bis dahin gebauten Vergleich negiert.
            when(queryRepo.accountRoster()).thenReturn(List.of(
                    FakeTuple.of("mainId", 1L, "mainName", "Ohne Skills", "corporationName", "C",
                            "characterId", 1L, "characterName", "A"),
                    FakeTuple.of("mainId", 2L, "mainName", "Mit Skills", "corporationName", "C",
                            "characterId", 2L, "characterName", "B")));
            hasSkillData(1L, 2L);
            hullRequiresSkills(1);
            misses(1L, NESTOR, 1L, 3, 0);

            // Keiner hat ein Schiff, also entscheidet allein der Skill-Stand.
            assertThat(board().notReady())
                    .extracting(ReadinessDtos.AccountReadinessDto::mainName)
                    .containsExactly("Mit Skills", "Ohne Skills");
        }

        @Test
        @DisplayName("stellt je Account den einsatzfaehigen Charakter nach oben")
        void sortsCapableCharacterFirst() {
            rosterOf(MAIN_ID, ALT_ID);
            when(queryRepo.hullOwnership(anyList(), anyList())).thenReturn(List.of(
                    FakeTuple.of("characterId", ALT_ID, "typeId", NESTOR, "quantity", 1L)));
            hasSkillData(MAIN_ID, ALT_ID);
            hullRequiresSkills(1);

            // Der Alt hat Schiff und Skills, der Main nur die Skills.
            assertThat(board().ready().getFirst().characters())
                    .extracting(ReadinessDtos.CharacterReadinessDto::characterId)
                    .containsExactly(ALT_ID, MAIN_ID);
        }

        @Test
        @DisplayName("summiert die Huellen ueber alle Charaktere des Accounts")
        void sumsHullsAcrossAccount() {
            rosterOf(MAIN_ID, ALT_ID);
            when(queryRepo.hullOwnership(anyList(), anyList())).thenReturn(List.of(
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
            when(queryRepo.hullOwnership(anyList(), anyList())).thenReturn(List.of(
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

            ReadinessDtos.FitReadinessDto fit = board();

            assertThat(fit.accountsTotal()).isZero();
            assertThat(fit.coverage()).isZero();
        }
    }

    @Nested
    @DisplayName("Skillplan als Pflicht")
    class SkillPlanTier {

        /** Ein Fitting mit hinterlegtem Plan, der diesen Skill auf dieser Stufe verlangt. */
        private void planRequires(long skillTypeId, int level) {
            when(doctrineRepo.findAll()).thenReturn(List.of(withId(doctrineWithEft("Armor", "Fit"), 5L)));
            when(eftParser.parseAndResolve(anyString())).thenReturn(parsedFit("Fit"));
            when(skillPlanService.skillsByDoctrine(any())).thenReturn(Map.of(
                    5L, new SkillPlanService.DoctrineSkillsDto(
                            List.of("Magic 14"),
                            List.of(new SkillPlanDtos.SkillEntryDto(
                                    skillTypeId, "Support " + skillTypeId, level)))));
        }

        private void hasSkillLevel(Long characterId, long skillTypeId, int level) {
            when(queryRepo.skillLevels(anyList(), anyList())).thenReturn(List.of(FakeTuple.of(
                    "characterId", characterId, "skillTypeId", skillTypeId,
                    "activeLevel", (long) level)));
        }

        @Test
        @DisplayName("laesst einen fehlenden Plan-Skill die Bereitschaft verhindern")
        void planGapBlocksReadiness() {
            // Der Plan ist Pflicht, nicht Empfehlung: ohne die
            // Unterstuetzungs-Skills bekommt der Pilot das Fitting zwar an,
            // richtet damit aber nichts aus.
            planRequires(900L, 5);
            rosterOf(MAIN_ID);
            ownsHull(MAIN_ID, 1);
            hasSkillData(MAIN_ID);
            hasSkillLevel(MAIN_ID, 900L, 2);

            ReadinessDtos.FitReadinessDto fit = board();

            assertThat(fit.ready()).isEmpty();
            assertThat(fit.accountsReady()).isZero();
            assertThat(fit.notReady()).singleElement()
                    .satisfies(account -> assertThat(account.canFly()).isFalse());
        }

        @Test
        @DisplayName("gilt als bereit, sobald auch der Plan erfuellt ist")
        void readyWhenPlanIsMet() {
            planRequires(900L, 3);
            rosterOf(MAIN_ID);
            ownsHull(MAIN_ID, 1);
            hasSkillData(MAIN_ID);
            hasSkillLevel(MAIN_ID, 900L, 5);

            ReadinessDtos.FitReadinessDto fit = board();

            assertThat(fit.accountsReady()).isEqualTo(1);
            assertThat(fit.ready().getFirst().characters().getFirst().canFly()).isTrue();
        }

        @Test
        @DisplayName("weist die Plan-Luecken getrennt aus, obwohl beide gleich zaehlen")
        void keepsPlanGapsSeparate() {
            // Getrennt allein zur Erklaerung: erkennbar bleiben soll, woher
            // eine Anforderung stammt.
            planRequires(900L, 4);
            rosterOf(MAIN_ID);
            ownsHull(MAIN_ID, 1);
            hasSkillData(MAIN_ID);
            hasSkillLevel(MAIN_ID, 900L, 1);

            ReadinessDtos.CharacterReadinessDto pilot =
                    board().notReady().getFirst().characters().getFirst();

            assertThat(pilot.canFly()).isFalse();
            assertThat(pilot.missingSkills()).isEmpty();
            assertThat(pilot.missingPlanSkills()).singleElement().satisfies(gap -> {
                assertThat(gap.requiredLevel()).isEqualTo(4);
                assertThat(gap.currentLevel()).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("zaehlt einen gar nicht trainierten Plan-Skill als Stufe null")
        void treatsUntrainedSkillAsZero() {
            planRequires(900L, 3);
            rosterOf(MAIN_ID);
            ownsHull(MAIN_ID, 1);
            hasSkillData(MAIN_ID);

            assertThat(board().notReady().getFirst().characters().getFirst().missingPlanSkills())
                    .singleElement()
                    .satisfies(gap -> assertThat(gap.currentLevel()).isZero());
        }

        @Test
        @DisplayName("zaehlt Plan-Skills in die Gesamtzahl der noetigen Skills")
        void countsPlanSkillsInTheTotal() {
            planRequires(900L, 3);
            requires(NESTOR, 1L, 3);
            rosterOf(MAIN_ID);
            hasSkillData(MAIN_ID);

            assertThat(board().notReady().getFirst().skillsRequired()).isEqualTo(2);
        }

        @Test
        @DisplayName("fuehrt einen Skill aus beiden Quellen nur einmal, mit der hoeheren Stufe")
        void mergesASkillFromBothSources() {
            // Verlangt das Modul Stufe 2 und der Plan Stufe 5, ist 5 bindend -
            // und der Skill steht einmal bei den Modulen, nicht zweimal.
            when(doctrineRepo.findAll())
                    .thenReturn(List.of(withId(doctrineWithEft("Armor", "Fit"), 5L)));
            when(eftParser.parseAndResolve(anyString())).thenReturn(parsedFit("Fit"));
            when(skillPlanService.skillsByDoctrine(any())).thenReturn(Map.of(
                    5L, new SkillPlanService.DoctrineSkillsDto(
                            List.of("Magic 14"),
                            List.of(new SkillPlanDtos.SkillEntryDto(7L, "Skill 7", 5)))));
            requires(NESTOR, 7L, 2);
            rosterOf(MAIN_ID);
            hasSkillData(MAIN_ID);
            misses(MAIN_ID, NESTOR, 7L, 2, 1);
            when(queryRepo.skillLevels(anyList(), anyList())).thenReturn(List.of(FakeTuple.of(
                    "characterId", MAIN_ID, "skillTypeId", 7L, "activeLevel", 1L)));

            ReadinessDtos.AccountReadinessDto account = board().notReady().getFirst();
            ReadinessDtos.CharacterReadinessDto pilot = account.characters().getFirst();

            assertThat(pilot.missingSkills()).singleElement()
                    .satisfies(gap -> assertThat(gap.requiredLevel()).isEqualTo(5));
            assertThat(pilot.missingPlanSkills()).isEmpty();
            assertThat(account.skillsRequired()).isEqualTo(1);
        }

        @Test
        @DisplayName("nennt die Plaene und ihre Anforderungen am Fitting")
        void namesThePlans() {
            planRequires(900L, 3);
            rosterOf(MAIN_ID);

            ReadinessDtos.FitReadinessDto fit = board();

            assertThat(fit.planNames()).containsExactly("Magic 14");
            assertThat(fit.planSkills()).singleElement()
                    .satisfies(skill -> assertThat(skill.level()).isEqualTo(3));
        }

        @Test
        @DisplayName("verlangt ohne hinterlegten Plan nichts darueber hinaus")
        void nothingExtraWithoutAnyPlan() {
            rosterOf(MAIN_ID);
            ownsHull(MAIN_ID, 1);
            hasSkillData(MAIN_ID);
            hullRequiresSkills(1);

            assertThat(board().accountsReady()).isEqualTo(1);
        }

        @Test
        @DisplayName("prueft in der Sandbox keinen Plan")
        void sandboxHasNoPlan() {
            // Ein eingefuegtes Fitting haengt an keiner Doktrin.
            when(eftParser.parseAndResolve(anyString())).thenReturn(parsedFit("Frei"));

            assertThat(service.sandbox("[Nestor, Frei]").board().planNames()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Selbstauskunft eines Mitglieds")
    class MyReadiness {

        @Test
        @DisplayName("wertet nur den eigenen Account aus")
        void looksAtOwnAccountOnly() {
            // Der Endpunkt steht jedem Mitglied offen - er darf deshalb keine
            // fremden Charaktere heranziehen.
            when(queryRepo.accountRosterOf(MAIN_ID)).thenReturn(List.of(FakeTuple.of(
                    "mainId", MAIN_ID, "mainName", "Der Main", "corporationName", "Corp",
                    "characterId", MAIN_ID, "characterName", "Der Main")));
            when(queryRepo.hullOwnership(anyList(), anyList())).thenReturn(List.of(FakeTuple.of(
                    "characterId", MAIN_ID, "typeId", NESTOR, "quantity", 2L)));
            hasSkillData(MAIN_ID);
            hullRequiresSkills(1);

            List<ReadinessDtos.MyFitDto> mine = service.myReadiness(MAIN_ID);

            assertThat(mine).singleElement().satisfies(fit -> {
                assertThat(fit.canFly()).isTrue();
                assertThat(fit.hasShip()).isTrue();
                assertThat(fit.owned()).isEqualTo(2L);
                assertThat(fit.bestCharacterName()).isEqualTo("Der Main");
            });
            // Der volle Roster darf gar nicht erst geladen werden.
            verify(queryRepo, never()).accountRoster();
        }

        @Test
        @DisplayName("meldet fehlende Skills des besten eigenen Charakters")
        void reportsGapsOfTheBestOwnCharacter() {
            when(queryRepo.accountRosterOf(MAIN_ID)).thenReturn(List.of(FakeTuple.of(
                    "mainId", MAIN_ID, "mainName", "Der Main", "corporationName", "Corp",
                    "characterId", MAIN_ID, "characterName", "Der Main")));
            hasSkillData(MAIN_ID);
            hullRequiresSkills(2);
            missesSkill(MAIN_ID, 2L);

            assertThat(service.myReadiness(MAIN_ID)).singleElement().satisfies(fit -> {
                assertThat(fit.canFly()).isFalse();
                assertThat(fit.missingSkills()).singleElement()
                        .satisfies(gap -> assertThat(gap.skillTypeId()).isEqualTo(2L));
            });
        }

        @Test
        @DisplayName("liefert nichts, wenn der Charakter unbekannt ist")
        void emptyForUnknownCharacter() {
            when(queryRepo.accountRosterOf(anyLong())).thenReturn(List.of());

            assertThat(service.myReadiness(9999L)).isEmpty();
        }

        @Test
        @DisplayName("nennt die Doktrin zu jedem Fitting, damit sich gruppieren laesst")
        void namesTheDoctrine() {
            when(queryRepo.accountRosterOf(MAIN_ID)).thenReturn(List.of(FakeTuple.of(
                    "mainId", MAIN_ID, "mainName", "Der Main", "corporationName", "Corp",
                    "characterId", MAIN_ID, "characterName", "Der Main")));

            assertThat(service.myReadiness(MAIN_ID)).singleElement()
                    .satisfies(fit -> assertThat(fit.doctrineName()).isEqualTo("Armor"));
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
        @DisplayName("prueft in der Sandbox ebenfalls die Module")
        void checksModulesInSandbox() {
            when(eftParser.parseAndResolve(anyString())).thenReturn(parsedFit("Mein Fit", LAUNCHER));
            rosterOf(MAIN_ID);
            ownsHull(MAIN_ID, 1);
            hasSkillData(MAIN_ID);
            requires(NESTOR, 1L, 3);
            requires(LAUNCHER, 2L, 4);
            misses(MAIN_ID, LAUNCHER, 2L, 4, 1);

            ReadinessDtos.SandboxResultDto result = service.sandbox("[Nestor, Mein Fit]");

            assertThat(result.board().requiredSkills()).hasSize(2);
            assertThat(result.board().ready()).isEmpty();
            assertThat(result.board().notReady()).singleElement()
                    .satisfies(account -> assertThat(account.canFly()).isFalse());
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
