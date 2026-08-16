package com.eve.own.auth.backend.domain.navigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.navigation.dto.NavigationDtos;
import com.eve.own.auth.backend.domain.navigation.entity.NavigationCategory;
import com.eve.own.auth.backend.domain.navigation.entity.NavigationLink;
import com.eve.own.auth.backend.domain.navigation.repository.NavigationCategoryRepository;
import com.eve.own.auth.backend.domain.navigation.repository.NavigationLinkRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * Die Seitenleiste entsteht aus Registern und Links. Reihenfolge und
 * Sichtbarkeit kommen aus der Datenbank - nur dann hat die Verwaltung
 * ueberhaupt eine Wirkung.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Navigation")
class NavigationServiceTest {

    @Mock private NavigationLinkRepository linkRepo;
    @Mock private NavigationCategoryRepository categoryRepo;

    private NavigationService service;

    private final List<NavigationCategory> categories = new ArrayList<>();
    private final List<NavigationLink> links = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new NavigationService(linkRepo, categoryRepo);
        categories.clear();
        links.clear();

        when(categoryRepo.findAll()).thenReturn(categories);
        when(linkRepo.findAll()).thenReturn(links);
        when(categoryRepo.findByNameIgnoreCase(any())).thenReturn(Optional.empty());
        when(categoryRepo.save(any())).thenAnswer(call -> {
            NavigationCategory category = call.getArgument(0);
            if (category.getId() == null) category.setId(99L);
            return category;
        });
        when(linkRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(linkRepo.findByCategoryId(any())).thenAnswer(call -> links.stream()
                .filter(link -> call.getArgument(0).equals(link.getCategoryId()))
                .toList());
        when(categoryRepo.findById(any())).thenAnswer(call -> categories.stream()
                .filter(category -> category.getId().equals(call.getArgument(0)))
                .findFirst());
        when(linkRepo.findById(any())).thenAnswer(call -> links.stream()
                .filter(link -> link.getId().equals(call.getArgument(0)))
                .findFirst());
        when(categoryRepo.existsById(any())).thenAnswer(call -> categories.stream()
                .anyMatch(category -> category.getId().equals(call.getArgument(0))));
        when(linkRepo.existsById(any())).thenAnswer(call -> links.stream()
                .anyMatch(link -> link.getId().equals(call.getArgument(0))));
    }

    // ==================================================================
    // Aufbau
    // ==================================================================

    private NavigationCategory category(Long id, String name, Integer order) {
        NavigationCategory category = new NavigationCategory();
        category.setId(id);
        category.setName(name);
        category.setIcon("fa-solid fa-folder");
        category.setSortOrder(order);
        categories.add(category);
        return category;
    }

    private NavigationLink link(Long id, String label, Long categoryId, Integer order) {
        return link(id, label, categoryId, order, null, true, "/seite");
    }

    private NavigationLink link(Long id, String label, Long categoryId, Integer order,
                                String requiredRole, boolean active, String url) {
        NavigationLink link = new NavigationLink();
        link.setId(id);
        link.setLabel(label);
        link.setUrl(url);
        link.setIcon("fa-solid fa-x");
        link.setCategoryId(categoryId);
        link.setSortOrder(order);
        link.setRequiredRole(requiredRole);
        link.setActive(active);
        links.add(link);
        return link;
    }

    private List<String> menuLabels(String... roles) {
        return service.menuFor(Set.of(roles)).stream()
                .map(NavigationDtos.MenuEntryDto::label)
                .toList();
    }

    // ==================================================================
    // Tests
    // ==================================================================

    @Nested
    @DisplayName("Menue der Seitenleiste")
    class Menu {

        @Test
        @DisplayName("mischt Register und einzelne Punkte in einer Reihenfolge")
        void mixesCategoriesAndRootLinks() {
            // Genau so sieht die oberste Ebene aus: Ordner und einzelne Punkte
            // nebeneinander, nicht in getrennten Bloecken.
            link(1L, "Dashboard", null, 0);
            category(10L, "Fleet Management", 10);
            link(2L, "Tracking", 10L, 0);
            link(3L, "CharLink", null, 20);

            assertThat(menuLabels()).containsExactly("Dashboard", "Fleet Management", "CharLink");
        }

        @Test
        @DisplayName("sortiert die Punkte innerhalb eines Registers")
        void sortsWithinACategory() {
            category(10L, "Tools", 0);
            link(1L, "Zweitens", 10L, 20);
            link(2L, "Erstens", 10L, 10);

            assertThat(service.menuFor(Set.of()).getFirst().children())
                    .extracting(NavigationDtos.MenuItemDto::label)
                    .containsExactly("Erstens", "Zweitens");
        }

        @Test
        @DisplayName("laesst ein Register ohne sichtbare Punkte weg")
        void hidesEmptyCategories() {
            // Ein leerer Ordner waere fuer den Nutzer nur ein Klick ins Nichts.
            category(10L, "Leer", 0);
            link(1L, "Dashboard", null, 10);

            assertThat(menuLabels()).containsExactly("Dashboard");
        }

        @Test
        @DisplayName("laesst ein Register weg, dessen Punkte der Nutzer nicht sehen darf")
        void hidesCategoryWhoseLinksAreInvisible() {
            category(10L, "Admin", 0);
            link(1L, "Discord", 10L, 0, "ROLE_IT_ADMIN", true, "/admin/discord");

            assertThat(menuLabels("ROLE_MEMBER")).isEmpty();
            assertThat(menuLabels("ROLE_IT_ADMIN")).containsExactly("Admin");
        }

        @Test
        @DisplayName("zeigt einen Punkt ohne Rolle jedem")
        void showsRoleLessLinksToEveryone() {
            link(1L, "Corp Book", null, 0, null, true, "https://docs.example.org");

            assertThat(menuLabels()).containsExactly("Corp Book");
        }

        @Test
        @DisplayName("laesst abgeschaltete Punkte weg")
        void hidesInactiveLinks() {
            link(1L, "Alt", null, 0, null, false, "/alt");
            link(2L, "Neu", null, 10);

            assertThat(menuLabels()).containsExactly("Neu");
        }

        @Test
        @DisplayName("erkennt externe Ziele an ihrem Schema")
        void marksExternalTargets() {
            link(1L, "Wiki", null, 0, null, true, "https://wiki.example.org");
            link(2L, "Intern", null, 10);

            assertThat(service.menuFor(Set.of()))
                    .extracting(NavigationDtos.MenuEntryDto::external)
                    .containsExactly(true, false);
        }

        @Test
        @DisplayName("sortiert bei gleicher Position nach Namen, statt zu wuerfeln")
        void fallsBackToTheLabel() {
            link(1L, "Zebra", null, 0);
            link(2L, "Anton", null, 0);

            assertThat(menuLabels()).containsExactly("Anton", "Zebra");
        }
    }

    @Nested
    @DisplayName("Verschieben")
    class Moving {

        @Test
        @DisplayName("tauscht zwei Nachbarn der obersten Ebene")
        void swapsTopLevelNeighbours() {
            link(1L, "Erstens", null, 0);
            link(2L, "Zweitens", null, 10);

            service.move(new NavigationDtos.MoveDto(
                    NavigationDtos.MoveKind.LINK, 2L, NavigationDtos.MoveDirection.UP));

            assertThat(menuLabels()).containsExactly("Zweitens", "Erstens");
        }

        @Test
        @DisplayName("tauscht ein Register mit einem einzelnen Punkt")
        void swapsAcrossKinds() {
            // Beide teilen sich die oberste Ebene - der Tausch darf nicht an
            // der Art des Nachbarn scheitern.
            link(1L, "Dashboard", null, 0);
            category(10L, "Tools", 10);
            link(2L, "Mining", 10L, 0);

            service.move(new NavigationDtos.MoveDto(
                    NavigationDtos.MoveKind.CATEGORY, 10L, NavigationDtos.MoveDirection.UP));

            assertThat(menuLabels()).containsExactly("Tools", "Dashboard");
        }

        @Test
        @DisplayName("verschiebt innerhalb eines Registers, nicht darueber hinaus")
        void movesWithinItsCategory() {
            link(1L, "Oben", null, 0);
            category(10L, "Tools", 10);
            link(2L, "Erstens", 10L, 0);
            link(3L, "Zweitens", 10L, 10);

            service.move(new NavigationDtos.MoveDto(
                    NavigationDtos.MoveKind.LINK, 3L, NavigationDtos.MoveDirection.UP));

            assertThat(service.menuFor(Set.of()).get(1).children())
                    .extracting(NavigationDtos.MenuItemDto::label)
                    .containsExactly("Zweitens", "Erstens");
            // Der Punkt oberhalb des Registers bleibt unberuehrt.
            assertThat(menuLabels()).containsExactly("Oben", "Tools");
        }

        @Test
        @DisplayName("tut am Rand der Liste nichts")
        void doesNothingAtTheEdge() {
            link(1L, "Einziger", null, 0);

            service.move(new NavigationDtos.MoveDto(
                    NavigationDtos.MoveKind.LINK, 1L, NavigationDtos.MoveDirection.UP));

            assertThat(menuLabels()).containsExactly("Einziger");
        }

        @Test
        @DisplayName("weist einen unbekannten Punkt ab")
        void rejectsUnknownLink() {
            assertThatThrownBy(() -> service.move(new NavigationDtos.MoveDto(
                    NavigationDtos.MoveKind.LINK, 404L, NavigationDtos.MoveDirection.UP)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Pflege")
    class Editing {

        @Test
        @DisplayName("legt ein Register an und haengt es hinten an")
        void appendsANewCategory() {
            link(1L, "Dashboard", null, 30);

            NavigationDtos.CategoryDto saved = service.saveCategory(
                    new NavigationDtos.SaveCategoryDto(null, "Tools", "fa-solid fa-wrench"));

            assertThat(saved.name()).isEqualTo("Tools");
            assertThat(saved.sortOrder()).isGreaterThan(30);
        }

        @Test
        @DisplayName("setzt ohne Symbol das Standard-Ordnersymbol")
        void fallsBackToTheFolderIcon() {
            assertThat(service.saveCategory(
                    new NavigationDtos.SaveCategoryDto(null, "Tools", "  ")).icon())
                    .isEqualTo(NavigationMigration.DEFAULT_CATEGORY_ICON);
        }

        @Test
        @DisplayName("weist einen leeren oder doppelten Registernamen ab")
        void rejectsBadCategoryNames() {
            assertThatThrownBy(() -> service.saveCategory(
                    new NavigationDtos.SaveCategoryDto(null, "  ", null)))
                    .isInstanceOf(IllegalArgumentException.class);

            NavigationCategory existing = category(10L, "Tools", 0);
            when(categoryRepo.findByNameIgnoreCase("Tools")).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.saveCategory(
                    new NavigationDtos.SaveCategoryDto(null, "Tools", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("gibt es schon");
        }

        @Test
        @DisplayName("laesst beim Aendern den eigenen Namen zu")
        void allowsKeepingItsOwnName() {
            NavigationCategory existing = category(10L, "Tools", 0);
            when(categoryRepo.findByNameIgnoreCase("Tools")).thenReturn(Optional.of(existing));

            assertThat(service.saveCategory(
                    new NavigationDtos.SaveCategoryDto(10L, "Tools", "fa-solid fa-wrench")).icon())
                    .isEqualTo("fa-solid fa-wrench");
        }

        @Test
        @DisplayName("hebt die Punkte eines geloeschten Registers in die oberste Ebene")
        void keepsLinksWhenTheCategoryGoes() {
            // Ein versehentlich geloeschtes Register darf keine Menuepunkte
            // mitreissen.
            category(10L, "Tools", 0);
            NavigationLink mining = link(1L, "Mining", 10L, 0);

            service.deleteCategory(10L);

            assertThat(mining.getCategoryId()).isNull();
            verify(linkRepo).save(mining);
            verify(categoryRepo).delete(any());
        }

        @Test
        @DisplayName("weist ein unbekanntes Register ab")
        void rejectsUnknownCategory() {
            assertThatThrownBy(() -> service.deleteCategory(404L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("verlangt Beschriftung und Ziel")
        void requiresLabelAndUrl() {
            assertThatThrownBy(() -> service.saveLink(new NavigationDtos.SaveLinkDto(
                    null, "  ", "/x", null, null, null, true)))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> service.saveLink(new NavigationDtos.SaveLinkDto(
                    null, "Punkt", "  ", null, null, null, true)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("weist ein unbekanntes Register beim Speichern ab")
        void rejectsUnknownCategoryOnSave() {
            assertThatThrownBy(() -> service.saveLink(new NavigationDtos.SaveLinkDto(
                    null, "Punkt", "/x", null, 404L, null, true)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("haengt einen umgezogenen Punkt hinten an sein neues Register")
        void appendsAtTheNewCategory() {
            // Die alte Position gilt in der neuen Umgebung nichts.
            category(10L, "Tools", 0);
            link(1L, "Bestehend", 10L, 50);
            NavigationLink moving = link(2L, "Umzug", null, 0);

            NavigationDtos.LinkDto saved = service.saveLink(new NavigationDtos.SaveLinkDto(
                    2L, "Umzug", "/x", null, 10L, null, true));

            assertThat(saved.sortOrder()).isGreaterThan(50);
            assertThat(moving.getCategoryId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("laesst die Position unberuehrt, wenn das Register gleich bleibt")
        void keepsThePositionOnAPlainEdit() {
            category(10L, "Tools", 0);
            link(1L, "Punkt", 10L, 20);

            assertThat(service.saveLink(new NavigationDtos.SaveLinkDto(
                    1L, "Punkt neu", "/x", null, 10L, null, true)).sortOrder())
                    .isEqualTo(20);
        }

        @Test
        @DisplayName("wertet eine fehlende Angabe zur Sichtbarkeit als sichtbar")
        void defaultsToVisible() {
            assertThat(service.saveLink(new NavigationDtos.SaveLinkDto(
                    null, "Punkt", "/x", null, null, null, null)).active())
                    .isTrue();
        }

        @Test
        @DisplayName("weist einen unbekannten Punkt beim Loeschen ab")
        void rejectsUnknownLinkOnDelete() {
            assertThatThrownBy(() -> service.deleteLink(404L))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(linkRepo, never()).deleteById(any());
        }
    }

    @Nested
    @DisplayName("Uebersicht der Verwaltung")
    class AdminView {

        @Test
        @DisplayName("zeigt auch die abgeschalteten Punkte")
        void includesInactiveLinks() {
            // Sonst waere ein abgeschalteter Punkt nicht wieder einschaltbar.
            link(1L, "Abgeschaltet", null, 0, null, false, "/x");

            assertThat(service.adminView().links()).singleElement()
                    .satisfies(link -> assertThat(link.active()).isFalse());
        }

        @Test
        @DisplayName("zaehlt die Punkte je Register")
        void countsLinksPerCategory() {
            category(10L, "Tools", 0);
            link(1L, "Eins", 10L, 0);
            link(2L, "Zwei", 10L, 10);
            link(3L, "Frei", null, 20);

            assertThat(service.adminView().categories()).singleElement()
                    .satisfies(category -> assertThat(category.linkCount()).isEqualTo(2));
        }
    }
}
