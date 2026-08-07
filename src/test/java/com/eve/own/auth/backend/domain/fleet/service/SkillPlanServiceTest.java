package com.eve.own.auth.backend.domain.fleet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.fleet.dto.SkillPlanDtos;
import com.eve.own.auth.backend.domain.fleet.entity.DoctrineSkillPlan;
import com.eve.own.auth.backend.domain.fleet.entity.SkillPlan;
import com.eve.own.auth.backend.domain.fleet.entity.SkillPlanEntry;
import com.eve.own.auth.backend.domain.fleet.repository.DoctrineSkillPlanRepository;
import com.eve.own.auth.backend.domain.fleet.repository.ReadinessQueryRepository;
import com.eve.own.auth.backend.domain.fleet.repository.SkillPlanEntryRepository;
import com.eve.own.auth.backend.domain.fleet.repository.SkillPlanRepository;
import com.eve.own.auth.backend.testsupport.FakeTuple;
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

/**
 * Der Skillplan ergaenzt, was die Stammdaten nicht hergeben: die
 * Unterstuetzungs-Skills, die zu keinem Modul gehoeren und trotzdem
 * darueber entscheiden, ob ein Fitting etwas taugt.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Skillplaene")
class SkillPlanServiceTest {

    private static final Long POWER_GRID = 3413L;
    private static final Long CPU = 3426L;
    private static final Long HULL_UPGRADES = 3394L;

    @Mock private SkillPlanRepository planRepo;
    @Mock private SkillPlanEntryRepository entryRepo;
    @Mock private DoctrineSkillPlanRepository linkRepo;
    @Mock private ReadinessQueryRepository queryRepo;
    @Mock private CharacterRepository characterRepo;

    private SkillPlanService service;

    @BeforeEach
    void setUp() {
        service = new SkillPlanService(planRepo, entryRepo, linkRepo, queryRepo, characterRepo);

        when(planRepo.findAll()).thenReturn(List.of());
        when(planRepo.findByNameIgnoreCase(any())).thenReturn(Optional.empty());
        when(planRepo.save(any())).thenAnswer(call -> {
            SkillPlan plan = call.getArgument(0);
            if (plan.getId() == null) plan.setId(1L);
            return plan;
        });
        when(entryRepo.findByPlanIdIn(any())).thenReturn(List.of());
        when(linkRepo.findAll()).thenReturn(List.of());
        when(linkRepo.findByDoctrineIdIn(any())).thenReturn(List.of());
        when(queryRepo.resolveSkillsByName(anyList())).thenReturn(List.of());
        when(characterRepo.findById(anyLong())).thenReturn(Optional.empty());
    }

    private static SkillPlan plan(Long id, String name) {
        SkillPlan plan = new SkillPlan();
        plan.setId(id);
        plan.setName(name);
        return plan;
    }

    private static SkillPlanEntry entry(Long planId, Long skillTypeId, String name, int level) {
        SkillPlanEntry entry = new SkillPlanEntry();
        entry.setPlanId(planId);
        entry.setSkillTypeId(skillTypeId);
        entry.setSkillName(name);
        entry.setRequiredLevel(level);
        return entry;
    }

    private static DoctrineSkillPlan link(Long doctrineId, Long planId) {
        DoctrineSkillPlan link = new DoctrineSkillPlan();
        link.setDoctrineId(doctrineId);
        link.setPlanId(planId);
        return link;
    }

    @Nested
    @DisplayName("Anlegen und Aendern")
    class Saving {

        @Test
        @DisplayName("speichert Plan und Eintraege")
        void storesPlanAndEntries() {
            SkillPlanDtos.SkillPlanDto saved = service.save(1L, new SkillPlanDtos.SaveSkillPlanDto(
                    null, "Magic 14", "Die Grundlagen",
                    List.of(new SkillPlanDtos.SkillEntryDto(POWER_GRID, "Power Grid Management", 5))));

            assertThat(saved.name()).isEqualTo("Magic 14");
            assertThat(saved.skills()).hasSize(1);
            verify(entryRepo).save(any(SkillPlanEntry.class));
        }

        @Test
        @DisplayName("ersetzt die Eintraege vollstaendig statt sie zu ergaenzen")
        void replacesEntriesCompletely() {
            // Das Formular schickt immer den ganzen Stand; ein Abgleich Zeile
            // fuer Zeile brächte nur Gelegenheiten, etwas zu uebersehen.
            service.save(1L, new SkillPlanDtos.SaveSkillPlanDto(
                    null, "Magic 14", null,
                    List.of(new SkillPlanDtos.SkillEntryDto(POWER_GRID, "Power Grid Management", 5))));

            verify(entryRepo).deleteByPlanId(1L);
        }

        @Test
        @DisplayName("weist einen leeren Namen ab")
        void rejectsBlankName() {
            assertThatThrownBy(() -> service.save(1L,
                    new SkillPlanDtos.SaveSkillPlanDto(null, "   ", null, List.of())))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(planRepo, never()).save(any());
        }

        @Test
        @DisplayName("weist einen bereits vergebenen Namen ab")
        void rejectsDuplicateName() {
            when(planRepo.findByNameIgnoreCase("Magic 14")).thenReturn(Optional.of(plan(7L, "Magic 14")));

            assertThatThrownBy(() -> service.save(1L,
                    new SkillPlanDtos.SaveSkillPlanDto(null, "Magic 14", null, List.of())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("existiert bereits");
        }

        @Test
        @DisplayName("laesst den eigenen Namen beim Aendern zu")
        void allowsKeepingOwnName() {
            when(planRepo.findByNameIgnoreCase("Magic 14")).thenReturn(Optional.of(plan(7L, "Magic 14")));
            when(planRepo.findById(7L)).thenReturn(Optional.of(plan(7L, "Magic 14")));

            assertThat(service.save(1L, new SkillPlanDtos.SaveSkillPlanDto(
                    7L, "Magic 14", "Neu beschrieben", List.of())).name()).isEqualTo("Magic 14");
        }

        @Test
        @DisplayName("nimmt bei einem doppelt genannten Skill die hoehere Stufe")
        void keepsTheHigherLevelOnDuplicates() {
            service.save(1L, new SkillPlanDtos.SaveSkillPlanDto(
                    null, "Plan", null,
                    List.of(new SkillPlanDtos.SkillEntryDto(POWER_GRID, "Power Grid Management", 3),
                            new SkillPlanDtos.SkillEntryDto(POWER_GRID, "Power Grid Management", 5))));

            ArgumentCaptor<SkillPlanEntry> stored = ArgumentCaptor.forClass(SkillPlanEntry.class);
            verify(entryRepo, times(1)).save(stored.capture());
            assertThat(stored.getValue().getRequiredLevel()).isEqualTo(5);
        }

        @Test
        @DisplayName("haelt die Stufe im gueltigen Bereich")
        void clampsTheLevel() {
            service.save(1L, new SkillPlanDtos.SaveSkillPlanDto(
                    null, "Plan", null,
                    List.of(new SkillPlanDtos.SkillEntryDto(CPU, "CPU Management", 9))));

            ArgumentCaptor<SkillPlanEntry> stored = ArgumentCaptor.forClass(SkillPlanEntry.class);
            verify(entryRepo).save(stored.capture());
            assertThat(stored.getValue().getRequiredLevel()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("Loeschen")
    class Deleting {

        @Test
        @DisplayName("raeumt Verknuepfungen und Eintraege mit ab")
        void removesLinksAndEntries() {
            // Eine zurueckbleibende Verknuepfung zeigte sonst auf einen Plan,
            // den es nicht mehr gibt.
            when(planRepo.existsById(7L)).thenReturn(true);

            service.delete(7L);

            verify(linkRepo).deleteByPlanId(7L);
            verify(entryRepo).deleteByPlanId(7L);
            verify(planRepo).deleteById(7L);
        }

        @Test
        @DisplayName("weist einen unbekannten Plan ab")
        void rejectsUnknownPlan() {
            when(planRepo.existsById(7L)).thenReturn(false);

            assertThatThrownBy(() -> service.delete(7L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Zuordnung an ein Fitting")
    class Assigning {

        @Test
        @DisplayName("ersetzt die bisherige Zuordnung")
        void replacesExistingAssignment() {
            when(planRepo.existsById(any())).thenReturn(true);

            service.assignToDoctrine(5L, List.of(1L, 2L));

            verify(linkRepo).deleteByDoctrineId(5L);
            verify(linkRepo, times(2)).save(any(DoctrineSkillPlan.class));
        }

        @Test
        @DisplayName("legt dieselbe Zuordnung nicht doppelt an")
        void ignoresDuplicates() {
            when(planRepo.existsById(any())).thenReturn(true);

            service.assignToDoctrine(5L, List.of(1L, 1L, 1L));

            verify(linkRepo, times(1)).save(any(DoctrineSkillPlan.class));
        }

        @Test
        @DisplayName("loest die Zuordnung bei leerer Liste")
        void clearsAssignment() {
            service.assignToDoctrine(5L, List.of());

            verify(linkRepo).deleteByDoctrineId(5L);
            verify(linkRepo, never()).save(any());
        }

        @Test
        @DisplayName("weist einen unbekannten Plan ab")
        void rejectsUnknownPlan() {
            when(planRepo.existsById(9L)).thenReturn(false);

            assertThatThrownBy(() -> service.assignToDoctrine(5L, List.of(9L)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Eingefuegter Plantext")
    class Importing {

        @Test
        @DisplayName("liest roemische Ziffern und Zahlen gleichermassen")
        void readsRomanAndArabicLevels() {
            when(queryRepo.resolveSkillsByName(anyList())).thenReturn(List.of(
                    FakeTuple.of("lookup", "power grid management",
                            "typeId", POWER_GRID, "typeName", "Power Grid Management"),
                    FakeTuple.of("lookup", "cpu management",
                            "typeId", CPU, "typeName", "CPU Management")));

            SkillPlanDtos.ImportResultDto result =
                    service.importPlanText("Power Grid Management V\nCPU Management 3");

            assertThat(result.skills())
                    .extracting(SkillPlanDtos.SkillEntryDto::skillName,
                            SkillPlanDtos.SkillEntryDto::level)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple("Power Grid Management", 5),
                            org.assertj.core.groups.Tuple.tuple("CPU Management", 3));
        }

        @Test
        @DisplayName("nimmt ohne Stufenangabe die hoechste")
        void defaultsToTheHighestLevel() {
            when(queryRepo.resolveSkillsByName(anyList())).thenReturn(List.of(
                    FakeTuple.of("lookup", "hull upgrades",
                            "typeId", HULL_UPGRADES, "typeName", "Hull Upgrades")));

            assertThat(service.importPlanText("Hull Upgrades").skills())
                    .singleElement()
                    .satisfies(skill -> assertThat(skill.level()).isEqualTo(5));
        }

        @Test
        @DisplayName("meldet nicht gefundene Zeilen, statt sie zu verschlucken")
        void reportsUnresolvedLines() {
            assertThat(service.importPlanText("Erfundener Skill V").unresolved())
                    .containsExactly("Erfundener Skill");
        }

        @Test
        @DisplayName("uebergeht Leerzeilen und Kommentare")
        void ignoresBlankLinesAndComments() {
            assertThat(service.importPlanText("\n# Kommentar\n// noch einer\n   \n").skills())
                    .isEmpty();
        }

        @Test
        @DisplayName("nimmt bei einem mehrfach genannten Skill die hoechste Stufe")
        void keepsTheHighestOfRepeatedLines() {
            when(queryRepo.resolveSkillsByName(anyList())).thenReturn(List.of(
                    FakeTuple.of("lookup", "hull upgrades",
                            "typeId", HULL_UPGRADES, "typeName", "Hull Upgrades")));

            assertThat(service.importPlanText("Hull Upgrades III\nHull Upgrades V").skills())
                    .singleElement()
                    .satisfies(skill -> assertThat(skill.level()).isEqualTo(5));
        }

        @Test
        @DisplayName("kommt mit leerem Text zurecht")
        void handlesEmptyText() {
            assertThat(service.importPlanText("  ").skills()).isEmpty();
            assertThat(service.importPlanText(null).skills()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Anforderung je Fitting")
    class RequirementsPerDoctrine {

        @Test
        @DisplayName("fuehrt mehrere Plaene eines Fittings zusammen")
        void mergesSeveralPlansOfOneFitting() {
            when(linkRepo.findByDoctrineIdIn(any()))
                    .thenReturn(List.of(link(5L, 1L), link(5L, 2L)));
            when(planRepo.findAllById(any()))
                    .thenReturn(List.of(plan(1L, "Magic 14"), plan(2L, "Raketen")));
            when(entryRepo.findByPlanIdIn(any())).thenReturn(List.of(
                    entry(1L, POWER_GRID, "Power Grid Management", 4),
                    entry(2L, CPU, "CPU Management", 5)));

            SkillPlanService.DoctrineSkillsDto skills = service.skillsByDoctrine(List.of(5L)).get(5L);

            assertThat(skills.planNames()).containsExactly("Magic 14", "Raketen");
            assertThat(skills.skills()).hasSize(2);
        }

        @Test
        @DisplayName("nimmt bei demselben Skill in zwei Plaenen die hoehere Stufe")
        void takesTheHigherLevelAcrossPlans() {
            // Sonst haenge die Anforderung an der Reihenfolge der Verknuepfung.
            when(linkRepo.findByDoctrineIdIn(any()))
                    .thenReturn(List.of(link(5L, 1L), link(5L, 2L)));
            when(planRepo.findAllById(any()))
                    .thenReturn(List.of(plan(1L, "A"), plan(2L, "B")));
            when(entryRepo.findByPlanIdIn(any())).thenReturn(List.of(
                    entry(1L, POWER_GRID, "Power Grid Management", 3),
                    entry(2L, POWER_GRID, "Power Grid Management", 5)));

            assertThat(service.skillsByDoctrine(List.of(5L)).get(5L).skills())
                    .singleElement()
                    .satisfies(skill -> assertThat(skill.level()).isEqualTo(5));
        }

        @Test
        @DisplayName("liefert nichts fuer Fittings ohne Plan")
        void emptyForFittingsWithoutPlan() {
            assertThat(service.skillsByDoctrine(List.of(5L))).isEmpty();
            assertThat(service.skillsByDoctrine(List.of())).isEmpty();
            assertThat(service.skillsByDoctrine(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Uebersicht")
    class Listing {

        @Test
        @DisplayName("zaehlt, an wie vielen Fittings ein Plan haengt")
        void countsUsage() {
            // Eine Warnung davor, den Plan beilaeufig zu aendern oder zu loeschen.
            when(planRepo.findAll()).thenReturn(List.of(plan(1L, "Magic 14")));
            when(linkRepo.findAll()).thenReturn(List.of(link(5L, 1L), link(6L, 1L)));

            assertThat(service.list()).singleElement()
                    .satisfies(plan -> assertThat(plan.usedByFittings()).isEqualTo(2));
        }

        @Test
        @DisplayName("liefert ohne Plaene eine leere Liste")
        void emptyWithoutPlans() {
            assertThat(service.list()).isEmpty();
        }

        @Test
        @DisplayName("sucht Skills nur bei brauchbarer Eingabe")
        void searchesOnlyWithUsableInput() {
            assertThat(service.searchSkills("  ", 10)).isEmpty();
            assertThat(service.searchSkills(null, 10)).isEmpty();
            verify(queryRepo, never()).searchSkills(any(), anyInt());
        }

        @Test
        @DisplayName("reicht Suchtreffer durch")
        void passesSearchResults() {
            when(queryRepo.searchSkills("power", 10)).thenReturn(List.of(
                    FakeTuple.of("typeId", POWER_GRID, "typeName", "Power Grid Management")));

            assertThat(service.searchSkills("power", 10)).singleElement()
                    .satisfies(option -> assertThat(option.typeName()).isEqualTo("Power Grid Management"));
        }
    }

    @Nested
    @DisplayName("Zusammenspiel mit dem Katalog")
    class Catalog {

        @Test
        @DisplayName("laesst eine Beschreibung leer statt sie als Leerzeichen zu speichern")
        void storesBlankDescriptionAsNull() {
            service.save(1L, new SkillPlanDtos.SaveSkillPlanDto(null, "Plan", "   ", List.of()));

            ArgumentCaptor<SkillPlan> stored = ArgumentCaptor.forClass(SkillPlan.class);
            verify(planRepo).save(stored.capture());
            assertThat(stored.getValue().getDescription()).isNull();
        }

        @Test
        @DisplayName("uebergeht unbrauchbare Eintraege")
        void skipsUnusableEntries() {
            service.save(1L, new SkillPlanDtos.SaveSkillPlanDto(null, "Plan", null,
                    java.util.Arrays.asList(
                            null,
                            new SkillPlanDtos.SkillEntryDto(null, "Ohne ID", 3),
                            new SkillPlanDtos.SkillEntryDto(CPU, "  ", 3),
                            new SkillPlanDtos.SkillEntryDto(CPU, "CPU Management", 3))));

            verify(entryRepo, times(1)).save(any(SkillPlanEntry.class));
        }
    }
}
