package com.eve.own.auth.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.AuthRoleSource;
import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.auth.entity.SystemRole;
import com.eve.own.auth.backend.domain.auth.entity.TitleRoleMapping;
import com.eve.own.auth.backend.domain.auth.repository.SystemRoleRepository;
import com.eve.own.auth.backend.domain.auth.repository.TitleRoleMappingRepository;
import com.eve.own.auth.backend.domain.auth.service.RoleCatalogService.AuthRoleDto;
import java.util.List;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Rollenkatalog")
class RoleCatalogServiceTest {

    @Mock
    private SystemRoleRepository systemRoleRepo;

    @Mock
    private TitleRoleMappingRepository titleRepo;

    private RoleCatalogService service;

    @BeforeEach
    void setUp() {
        service = new RoleCatalogService(systemRoleRepo, titleRepo);
        when(systemRoleRepo.findAll()).thenReturn(List.of());
        when(titleRepo.findAll()).thenReturn(List.of());
    }

    private static SystemRole systemRole(String name, String description, boolean special) {
        SystemRole role = new SystemRole();
        role.setRoleName(name);
        role.setDescription(description);
        role.setSpecial(special);
        return role;
    }

    private static TitleRoleMapping mapping(Long titleId, String titleName, String roleName) {
        TitleRoleMapping titleMapping = new TitleRoleMapping();
        titleMapping.setTitleId(titleId);
        titleMapping.setTitleName(titleName);
        titleMapping.setRoleName(roleName);
        titleMapping.setCorporationId(98000001L);
        return titleMapping;
    }

    private AuthRoleDto find(String name) {
        return service.catalog().stream()
                .filter(role -> role.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " fehlt im Katalog"));
    }

    @Nested
    @DisplayName("Zusammenstellung")
    class Assembly {

        @Test
        @DisplayName("fuehrt alle drei Quellen zusammen")
        void mergesAllThreeSources() {
            when(systemRoleRepo.findAll())
                    .thenReturn(List.of(systemRole("ROLE_RECRUITER", "Wirbt an", false)));
            when(titleRepo.findAll()).thenReturn(List.of(mapping(1L, "A38", "ROLE_A38")));

            assertThat(service.catalog()).extracting(AuthRoleDto::name)
                    .contains(SystemRoles.USER, "ROLE_RECRUITER", "ROLE_A38");
        }

        @Test
        @DisplayName("kennt die eingebauten Rollen auch ohne jeden Datenbankeintrag")
        void listsBuiltInRolesWithoutAnyStoredRow() {
            assertThat(service.catalog()).extracting(AuthRoleDto::name)
                    .containsAll(SystemRoles.builtIn());
        }

        @Test
        @DisplayName("vermerkt zu jeder Rolle ihre Herkunft")
        void marksTheSourceOfEveryRole() {
            when(systemRoleRepo.findAll())
                    .thenReturn(List.of(systemRole("ROLE_RECRUITER", "Wirbt an", false)));
            when(titleRepo.findAll()).thenReturn(List.of(mapping(1L, "A38", "ROLE_A38")));

            assertThat(find(SystemRoles.USER).source()).isEqualTo(AuthRoleSource.BUILT_IN);
            assertThat(find("ROLE_RECRUITER").source()).isEqualTo(AuthRoleSource.CUSTOM);
            assertThat(find("ROLE_A38").source()).isEqualTo(AuthRoleSource.TITLE);
        }

        @Test
        @DisplayName("fuehrt eine Rolle nur einmal, auch wenn sie aus zwei Quellen stammt")
        void listsARoleOnlyOnce() {
            // Eine selbst angelegte Rolle, die zusaetzlich an einem Titel haengt.
            when(systemRoleRepo.findAll())
                    .thenReturn(List.of(systemRole("ROLE_RECRUITER", "Wirbt an", false)));
            when(titleRepo.findAll())
                    .thenReturn(List.of(mapping(1L, "Rekrut", "ROLE_RECRUITER")));

            assertThat(service.catalog()).extracting(AuthRoleDto::name)
                    .filteredOn("ROLE_RECRUITER"::equals)
                    .hasSize(1);
        }

        @Test
        @DisplayName("laesst eine eingebaute Rolle eingebaut, auch wenn ein Titel sie vergibt")
        void keepsABuiltInRoleBuiltIn() {
            // Der Ingame-Titel "Director" zeigt ueblicherweise auf ROLE_DIRECTOR.
            when(titleRepo.findAll())
                    .thenReturn(List.of(mapping(1L, "Director", SystemRoles.DIRECTOR)));

            assertThat(find(SystemRoles.DIRECTOR).source()).isEqualTo(AuthRoleSource.BUILT_IN);
            assertThat(find(SystemRoles.DIRECTOR).grantingTitles()).containsExactly("Director");
        }

        @Test
        @DisplayName("nennt zu jeder Rolle die Titel, die sie vergeben")
        void namesTheGrantingTitles() {
            when(titleRepo.findAll()).thenReturn(List.of(
                    mapping(1L, "A38", "ROLE_A38"),
                    mapping(2L, "Ausbilder", "ROLE_A38")));

            assertThat(find("ROLE_A38").grantingTitles()).containsExactly("A38", "Ausbilder");
        }

        @Test
        @DisplayName("uebergeht Titel, die bewusst keine Rolle vergeben")
        void ignoresTitlesWithoutARole() {
            when(titleRepo.findAll()).thenReturn(List.of(
                    mapping(1L, "Ohne Rechte", null),
                    mapping(2L, "Auch ohne", "  ")));

            assertThat(service.catalog()).extracting(AuthRoleDto::name)
                    .containsExactlyInAnyOrderElementsOf(SystemRoles.builtIn());
        }

        @Test
        @DisplayName("behilft sich bei einer Zuordnung ohne Titelnamen mit der ID")
        void fallsBackToTheTitleId() {
            // Aeltere Zuordnungen wurden ohne Namen angelegt.
            when(titleRepo.findAll()).thenReturn(List.of(mapping(7L, null, "ROLE_A38")));

            assertThat(find("ROLE_A38").grantingTitles()).containsExactly("Titel 7");
        }

        @Test
        @DisplayName("zieht die gespeicherte Beschreibung der eingebauten vor")
        void prefersTheStoredDescription() {
            when(systemRoleRepo.findAll())
                    .thenReturn(List.of(systemRole(SystemRoles.IT_ADMIN, "Eigener Text", true)));

            assertThat(find(SystemRoles.IT_ADMIN).description()).isEqualTo("Eigener Text");
            assertThat(find(SystemRoles.IT_ADMIN).special()).isTrue();
        }

        @Test
        @DisplayName("laesst keine Rolle ohne Beschreibung stehen")
        void neverLeavesADescriptionEmpty() {
            when(systemRoleRepo.findAll())
                    .thenReturn(List.of(systemRole("ROLE_RECRUITER", null, false)));
            when(titleRepo.findAll()).thenReturn(List.of(mapping(1L, "A38", "ROLE_A38")));

            assertThat(service.catalog()).allSatisfy(
                    role -> assertThat(role.description()).isNotBlank());
        }

        @Test
        @DisplayName("sortiert nach Herkunft und darin alphabetisch")
        void sortsBySourceThenName() {
            when(systemRoleRepo.findAll()).thenReturn(List.of(
                    systemRole("ROLE_ZWEITE", null, false),
                    systemRole("ROLE_ERSTE", null, false)));

            List<AuthRoleDto> custom = service.catalog().stream()
                    .filter(role -> role.source() == AuthRoleSource.CUSTOM)
                    .toList();

            assertThat(custom).extracting(AuthRoleDto::name)
                    .containsExactly("ROLE_ERSTE", "ROLE_ZWEITE");
            assertThat(service.catalog().getFirst().source()).isEqualTo(AuthRoleSource.BUILT_IN);
        }

        @Test
        @DisplayName("liefert die reinen Namen alphabetisch")
        void listsPlainNamesAlphabetically() {
            when(systemRoleRepo.findAll())
                    .thenReturn(List.of(systemRole("ROLE_AAA", null, false)));

            assertThat(service.roleNames()).isSorted().contains("ROLE_AAA");
        }
    }

    @Nested
    @DisplayName("Anlegen")
    class Saving {

        @Test
        @DisplayName("speichert eine neue Rolle unter normalisiertem Namen")
        void storesUnderTheNormalizedName() {
            when(systemRoleRepo.findById("ROLE_RECRUITER")).thenReturn(Optional.empty());

            AuthRoleDto saved = service.save("recruiter", "Wirbt an", true);

            ArgumentCaptor<SystemRole> stored = ArgumentCaptor.forClass(SystemRole.class);
            verify(systemRoleRepo).save(stored.capture());
            assertThat(stored.getValue().getRoleName()).isEqualTo("ROLE_RECRUITER");
            assertThat(stored.getValue().getDescription()).isEqualTo("Wirbt an");
            assertThat(stored.getValue().isSpecial()).isTrue();
            assertThat(saved.name()).isEqualTo("ROLE_RECRUITER");
            assertThat(saved.source()).isEqualTo(AuthRoleSource.CUSTOM);
        }

        @Test
        @DisplayName("aendert eine vorhandene Rolle, statt eine zweite anzulegen")
        void updatesAnExistingRole() {
            SystemRole existing = systemRole("ROLE_RECRUITER", "Alt", false);
            when(systemRoleRepo.findById("ROLE_RECRUITER")).thenReturn(Optional.of(existing));

            service.save("ROLE_RECRUITER", "Neu", false);

            verify(systemRoleRepo).save(existing);
            assertThat(existing.getDescription()).isEqualTo("Neu");
        }

        @Test
        @DisplayName("legt eine leere Beschreibung als null ab")
        void storesABlankDescriptionAsNull() {
            when(systemRoleRepo.findById("ROLE_RECRUITER")).thenReturn(Optional.empty());

            service.save("Recruiter", "   ", false);

            ArgumentCaptor<SystemRole> stored = ArgumentCaptor.forClass(SystemRole.class);
            verify(systemRoleRepo).save(stored.capture());
            assertThat(stored.getValue().getDescription()).isNull();
        }

        @Test
        @DisplayName("weist eine eingebaute Rolle ab")
        void rejectsABuiltInRole() {
            // Ein special-Vermerk auf ROLE_DIRECTOR liesse die Rolle jede
            // Neuberechnung ueberdauern - auch ohne den zugehoerigen Titel.
            assertThatThrownBy(() -> service.save("ROLE_DIRECTOR", "Egal", true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("eingebaute Rolle");
            verify(systemRoleRepo, never()).save(any());
        }

        @Test
        @DisplayName("weist eine eingebaute Rolle auch in anderer Schreibweise ab")
        void rejectsABuiltInRoleWrittenDifferently() {
            assertThatThrownBy(() -> service.save("director", "Egal", true))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("weist einen leeren Namen ab")
        void rejectsABlankName() {
            assertThatThrownBy(() -> service.save("  ", "Egal", false))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Loeschen")
    class Deleting {

        @Test
        @DisplayName("loescht eine ungenutzte eigene Rolle")
        void deletesAnUnusedCustomRole() {
            when(systemRoleRepo.existsById("ROLE_RECRUITER")).thenReturn(true);

            service.delete("ROLE_RECRUITER");

            verify(systemRoleRepo).deleteById("ROLE_RECRUITER");
        }

        @Test
        @DisplayName("weist eine eingebaute Rolle ab")
        void refusesToDeleteABuiltInRole() {
            assertThatThrownBy(() -> service.delete(SystemRoles.MEMBER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("eingebaute Rolle");
            verify(systemRoleRepo, never()).deleteById(any());
        }

        @Test
        @DisplayName("weist eine Rolle ab, die ein Titel noch vergibt, und nennt ihn")
        void refusesWhileATitleStillGrantsIt() {
            // Sonst verschwaende die Rolle nur aus der Liste und wuerde vom
            // naechsten Sync trotzdem weiter verteilt.
            when(titleRepo.findAll())
                    .thenReturn(List.of(mapping(1L, "Rekrut", "ROLE_RECRUITER")));

            assertThatThrownBy(() -> service.delete("ROLE_RECRUITER"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Rekrut");
            verify(systemRoleRepo, never()).deleteById(any());
        }

        @Test
        @DisplayName("weist eine unbekannte Rolle ab")
        void refusesAnUnknownRole() {
            when(systemRoleRepo.existsById("ROLE_GIBTSNICHT")).thenReturn(false);

            assertThatThrownBy(() -> service.delete("ROLE_GIBTSNICHT"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unbekannt");
        }
    }
}
