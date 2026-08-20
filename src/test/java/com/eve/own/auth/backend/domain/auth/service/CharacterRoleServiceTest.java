package com.eve.own.auth.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.auth.entity.SystemRole;
import com.eve.own.auth.backend.domain.auth.entity.TitleRoleMapping;
import com.eve.own.auth.backend.domain.auth.repository.SystemRoleRepository;
import com.eve.own.auth.backend.domain.auth.repository.TitleRoleMappingRepository;
import com.eve.own.auth.backend.domain.character.CorporationScope;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.Corporation;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * Die Rollenvergabe entscheidet ueber jeden Zugriff - und lief frueher in zwei
 * Kopien, die bereits auseinandergelaufen waren.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Rollenvergabe")
class CharacterRoleServiceTest {

    private static final Long MAIN_CORP = 98000001L;
    private static final Long ALT_CORP = 98000002L;
    private static final Long FOREIGN_CORP = 99999999L;
    private static final String TOKEN = "gueltiges-token";

    @Mock private EsiService esiService;
    @Mock private CharacterRepository characterRepo;
    @Mock private TitleRoleMappingRepository titleRepo;
    @Mock private SystemRoleRepository systemRoleRepo;

    private CharacterRoleService service;

    @BeforeEach
    void setUp() {
        service = new CharacterRoleService(esiService, characterRepo, titleRepo, systemRoleRepo,
                new CorporationScope(MAIN_CORP, String.valueOf(ALT_CORP)));

        when(characterRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(systemRoleRepo.findByIsSpecialTrue()).thenReturn(List.of());
        when(titleRepo.findByCorporationId(anyLong())).thenReturn(new ArrayList<>());
        when(esiService.getCharacterTitles(anyLong(), anyString()))
                .thenReturn(EsiResponse.changed(new EsiService.EsiTitleResponse[0], null, null));
    }

    private static Character characterIn(Long corporationId) {
        Corporation corporation = new Corporation();
        corporation.setId(corporationId);

        Character character = new Character();
        character.setId(1L);
        character.setName("Pilot Eins");
        character.setCorporation(corporation);
        character.setRoles(new HashSet<>());
        return character;
    }

    private static EsiService.EsiTitleResponse title(Long id, String name) {
        return new EsiService.EsiTitleResponse(id, name);
    }

    @Nested
    @DisplayName("Rollen aus der Corp-Zugehoerigkeit")
    class MembershipRoles {

        @Test
        @DisplayName("gibt Mitgliedern der Haupt-Corporation die volle Grundausstattung")
        void mainCorporationMember() {
            Character saved = service.applyRoles(characterIn(MAIN_CORP), TOKEN);

            assertThat(saved.getRoles())
                    .containsExactlyInAnyOrder(SystemRoles.USER, SystemRoles.MARAUDERS);
        }

        @Test
        @DisplayName("gibt Mitgliedern einer Alt-Corporation die Rolle der Haupt-Corp nicht")
        void altCorporationMember() {
            Character saved = service.applyRoles(characterIn(ALT_CORP), TOKEN);

            assertThat(saved.getRoles())
                    .containsExactlyInAnyOrder(SystemRoles.USER);
        }

        @Test
        @DisplayName("stuft Charaktere fremder Corporations auf Gast")
        void foreignCorporationMember() {
            Character saved = service.applyRoles(characterIn(FOREIGN_CORP), TOKEN);

            assertThat(saved.getRoles()).containsExactly(SystemRoles.GUEST);
        }

        @Test
        @DisplayName("setzt beim Rueckstufen alle bisherigen Rollen zurueck")
        void demoteToGuestClearsEverything() {
            Character character = characterIn(MAIN_CORP);
            character.setRoles(new HashSet<>(Set.of(SystemRoles.CEO, SystemRoles.DIRECTOR)));

            Character saved = service.demoteToGuest(character);

            assertThat(saved.getRoles()).containsExactly(SystemRoles.GUEST);
        }
    }

    @Nested
    @DisplayName("Rollen aus Ingame-Titeln")
    class TitleRoles {

        @Test
        @DisplayName("legt fuer einen unbekannten Titel eine Zuordnung an")
        void createsMappingForUnknownTitle() {
            when(esiService.getCharacterTitles(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new EsiService.EsiTitleResponse[]{title(7L, "Fleet Commander")},
                            null, null));

            Character saved = service.applyRoles(characterIn(MAIN_CORP), TOKEN);

            ArgumentCaptor<TitleRoleMapping> mapping = ArgumentCaptor.forClass(TitleRoleMapping.class);
            verify(titleRepo).save(mapping.capture());
            assertThat(mapping.getValue().getTitleId()).isEqualTo(7L);
            assertThat(mapping.getValue().getTitleName()).isEqualTo("Fleet Commander");
            assertThat(mapping.getValue().getRoleName()).isEqualTo("ROLE_FLEET_COMMANDER");
            assertThat(saved.getRoles()).contains("ROLE_FLEET_COMMANDER");
        }

        @Test
        @DisplayName("entfernt die HTML-Auszeichnung aus einem ingame gefaerbten Titel")
        void stripsHtmlFromTitle() {
            when(esiService.getCharacterTitles(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(
                            new EsiService.EsiTitleResponse[]{title(7L, "<color=0xffff0000>Director</color>")},
                            null, null));

            service.applyRoles(characterIn(MAIN_CORP), TOKEN);

            ArgumentCaptor<TitleRoleMapping> mapping = ArgumentCaptor.forClass(TitleRoleMapping.class);
            verify(titleRepo).save(mapping.capture());
            assertThat(mapping.getValue().getTitleName()).isEqualTo("Director");
        }

        @Test
        @DisplayName("nutzt die vom Admin hinterlegte Rolle statt der abgeleiteten")
        void usesConfiguredRole() {
            TitleRoleMapping configured = new TitleRoleMapping();
            configured.setTitleId(7L);
            configured.setTitleName("A38");
            configured.setRoleName("ROLE_DIRECTOR");
            when(titleRepo.findByCorporationId(MAIN_CORP))
                    .thenReturn(new ArrayList<>(List.of(configured)));
            when(esiService.getCharacterTitles(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new EsiService.EsiTitleResponse[]{title(7L, "A38")}, null, null));

            Character saved = service.applyRoles(characterIn(MAIN_CORP), TOKEN);

            assertThat(saved.getRoles()).contains("ROLE_DIRECTOR").doesNotContain("ROLE_A38");
        }

        @Test
        @DisplayName("vergibt nichts, wenn die Zuordnung bewusst leer ist")
        void emptyMappingGrantsNothing() {
            TitleRoleMapping muted = new TitleRoleMapping();
            muted.setTitleId(7L);
            muted.setTitleName("Praktikant");
            muted.setRoleName("  ");
            when(titleRepo.findByCorporationId(MAIN_CORP)).thenReturn(new ArrayList<>(List.of(muted)));
            when(esiService.getCharacterTitles(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new EsiService.EsiTitleResponse[]{title(7L, "Praktikant")},
                            null, null));

            Character saved = service.applyRoles(characterIn(MAIN_CORP), TOKEN);

            assertThat(saved.getRoles())
                    .containsExactlyInAnyOrder(SystemRoles.USER, SystemRoles.MARAUDERS);
        }

        @Test
        @DisplayName("schreibt einen ingame umbenannten Titel nach")
        void updatesRenamedTitle() {
            TitleRoleMapping existing = new TitleRoleMapping();
            existing.setTitleId(7L);
            existing.setTitleName("Alter Name");
            existing.setRoleName("ROLE_DIRECTOR");
            when(titleRepo.findByCorporationId(MAIN_CORP)).thenReturn(new ArrayList<>(List.of(existing)));
            when(esiService.getCharacterTitles(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new EsiService.EsiTitleResponse[]{title(7L, "Neuer Name")},
                            null, null));

            service.applyRoles(characterIn(MAIN_CORP), TOKEN);

            assertThat(existing.getTitleName()).isEqualTo("Neuer Name");
            verify(titleRepo).save(existing);
        }
    }

    @Nested
    @DisplayName("Widerstandsfaehigkeit")
    class Resilience {

        @Test
        @DisplayName("behaelt die Grundrollen, wenn ESI die Titel verweigert")
        void keepsBaseRolesWhenEsiFails() {
            when(esiService.getCharacterTitles(anyLong(), anyString()))
                    .thenThrow(new RuntimeException("ESI antwortet nicht"));

            Character saved = service.applyRoles(characterIn(MAIN_CORP), TOKEN);

            assertThat(saved.getRoles())
                    .containsExactlyInAnyOrder(SystemRoles.USER, SystemRoles.MARAUDERS);
        }

        @Test
        @DisplayName("fragt ohne Token gar nicht erst nach Titeln")
        void skipsTitlesWithoutToken() {
            Character saved = service.applyRoles(characterIn(MAIN_CORP), null);

            verify(esiService, never()).getCharacterTitles(anyLong(), anyString());
            assertThat(saved.getRoles()).contains(SystemRoles.USER);
        }

        @Test
        @DisplayName("rettet von Hand vergebene Sonderrollen ueber die Neuberechnung")
        void retainsSpecialRoles() {
            SystemRole special = new SystemRole();
            special.setRoleName("ROLE_VETERAN");
            when(systemRoleRepo.findByIsSpecialTrue()).thenReturn(List.of(special));

            Character character = characterIn(MAIN_CORP);
            character.setRoles(new HashSet<>(Set.of("ROLE_VETERAN", "ROLE_VERALTET")));

            Character saved = service.applyRoles(character, TOKEN);

            assertThat(saved.getRoles()).contains("ROLE_VETERAN").doesNotContain("ROLE_VERALTET");
        }
    }
}
