package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.service.CharacterRoleService;
import com.eve.own.auth.backend.domain.character.CorporationScope;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.Corporation;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.repository.CorporationRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
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
@DisplayName("Pruefung der Corp-Zugehoerigkeit")
class CharacterMembershipServiceTest {

    private static final Long MAIN_CORP = 98000001L;
    private static final Long FOREIGN_CORP = 99999999L;

    @Mock private EsiService esiService;
    @Mock private CharacterRepository characterRepo;
    @Mock private CorporationRepository corpRepo;
    @Mock private CharacterRoleService roleService;

    private CharacterMembershipService service;

    @BeforeEach
    void setUp() {
        service = new CharacterMembershipService(esiService, characterRepo, corpRepo,
                new CorporationScope(MAIN_CORP, ""), roleService);
        when(characterRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(corpRepo.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private static Character character(Long corporationId, Long mainCharacterId) {
        Corporation corporation = new Corporation();
        corporation.setId(corporationId);
        corporation.setName("Corp " + corporationId);

        Character character = new Character();
        character.setId(1L);
        character.setName("Pilot Eins");
        character.setMainCharacterId(mainCharacterId);
        character.setCorporation(corporation);
        return character;
    }

    private void esiReportsCorporation(Long corporationId) {
        when(esiService.getCharacter(anyLong())).thenReturn(EsiResponse.changed(
                new EsiService.EsiCharacterResponse("Pilot Eins", corporationId), null, null));
    }

    @Nested
    @DisplayName("Mitgliedschaft")
    class Membership {

        @Test
        @DisplayName("laesst ein Mitglied einer betreuten Corporation weiterlaufen")
        void memberStays() {
            esiReportsCorporation(MAIN_CORP);

            assertThat(service.verifyMembership(character(MAIN_CORP, 1L))).isTrue();
            verify(roleService, never()).demoteToGuest(any());
        }

        @Test
        @DisplayName("stuft einen ausgetretenen Main auf Gast und bricht den Sync ab")
        void demotesDepartedMain() {
            esiReportsCorporation(FOREIGN_CORP);
            when(corpRepo.findById(FOREIGN_CORP)).thenReturn(Optional.of(new Corporation()));

            Character main = character(MAIN_CORP, 1L);

            assertThat(service.verifyMembership(main)).isFalse();
            verify(roleService).demoteToGuest(main);
        }

        @Test
        @DisplayName("laesst einen Alt in einer fremden Corporation in Ruhe")
        void keepsAltInForeignCorporation() {
            // Alts duerfen ausserhalb sitzen - ueber den Zugang entscheidet der Main.
            esiReportsCorporation(FOREIGN_CORP);
            when(corpRepo.findById(FOREIGN_CORP)).thenReturn(Optional.of(new Corporation()));

            Character alt = character(MAIN_CORP, 999L);

            assertThat(service.verifyMembership(alt)).isTrue();
            verify(roleService, never()).demoteToGuest(any());
        }

        @Test
        @DisplayName("wertet fehlende ESI-Auskunft nicht als Austritt")
        void silenceIsNoProof() {
            when(esiService.getCharacter(anyLong())).thenReturn(EsiResponse.empty());

            assertThat(service.verifyMembership(character(MAIN_CORP, 1L))).isTrue();
        }
    }

    @Nested
    @DisplayName("Corporationswechsel")
    class CorporationChange {

        @Test
        @DisplayName("haengt den Charakter an eine bereits bekannte Corporation um")
        void movesToKnownCorporation() {
            esiReportsCorporation(FOREIGN_CORP);
            Corporation known = new Corporation();
            known.setId(FOREIGN_CORP);
            known.setName("Bekannte Corp");
            when(corpRepo.findById(FOREIGN_CORP)).thenReturn(Optional.of(known));

            Character alt = character(MAIN_CORP, 999L);
            service.verifyMembership(alt);

            assertThat(alt.getCorporation()).isSameAs(known);
            verify(characterRepo).save(alt);
        }

        @Test
        @DisplayName("legt eine unbekannte Corporation aus den ESI-Stammdaten an")
        void createsUnknownCorporation() {
            esiReportsCorporation(FOREIGN_CORP);
            when(corpRepo.findById(FOREIGN_CORP)).thenReturn(Optional.empty());
            when(esiService.getCorporationInfo(FOREIGN_CORP)).thenReturn(
                    new EsiService.EsiCorporationResponse("Neue Corp", "NEU", null, 500001L));

            service.verifyMembership(character(MAIN_CORP, 999L));

            ArgumentCaptor<Corporation> saved = ArgumentCaptor.forClass(Corporation.class);
            verify(corpRepo).save(saved.capture());
            assertThat(saved.getValue().getName()).isEqualTo("Neue Corp");
            assertThat(saved.getValue().getTicker()).isEqualTo("NEU");
            assertThat(saved.getValue().getFactionId()).isEqualTo(500001L);
        }

        @Test
        @DisplayName("legt einen Platzhalter an, wenn die Stammdaten nicht ladbar sind")
        void createsPlaceholderCorporation() {
            // Ohne gueltige Corp-Referenz liesse sich der Charakter nicht speichern.
            esiReportsCorporation(FOREIGN_CORP);
            when(corpRepo.findById(FOREIGN_CORP)).thenReturn(Optional.empty());
            when(esiService.getCorporationInfo(FOREIGN_CORP))
                    .thenThrow(new RuntimeException("ESI nicht erreichbar"));

            service.verifyMembership(character(MAIN_CORP, 999L));

            ArgumentCaptor<Corporation> saved = ArgumentCaptor.forClass(Corporation.class);
            verify(corpRepo).save(saved.capture());
            assertThat(saved.getValue().getName()).isEqualTo("Unknown Corp");
            assertThat(saved.getValue().getTicker()).isEqualTo("UNK");
        }
    }

    @Nested
    @DisplayName("Fraktionszugehoerigkeit")
    class FactionUpdate {

        @Test
        @DisplayName("schreibt die Fraktion der Corporation nach")
        void updatesFaction() {
            Character character = character(MAIN_CORP, 1L);
            when(esiService.getCorporationInfo(MAIN_CORP)).thenReturn(
                    new EsiService.EsiCorporationResponse("Corp", "TCK", null, 500002L));

            service.refreshCorporationFaction(character);

            assertThat(character.getCorporation().getFactionId()).isEqualTo(500002L);
            verify(corpRepo).save(character.getCorporation());
        }

        @Test
        @DisplayName("laesst die Fraktion unberuehrt, wenn ESI keine liefert")
        void keepsFactionWhenEsiHasNone() {
            Character character = character(MAIN_CORP, 1L);
            when(esiService.getCorporationInfo(MAIN_CORP)).thenReturn(
                    new EsiService.EsiCorporationResponse("Corp", "TCK", null, null));

            service.refreshCorporationFaction(character);

            assertThat(character.getCorporation().getFactionId()).isNull();
            verify(corpRepo, never()).save(any());
        }

        @Test
        @DisplayName("laeuft weiter, wenn der Abruf scheitert")
        void survivesEsiFailure() {
            Character character = character(MAIN_CORP, 1L);
            when(esiService.getCorporationInfo(MAIN_CORP))
                    .thenThrow(new RuntimeException("ESI nicht erreichbar"));

            service.refreshCorporationFaction(character);

            verify(corpRepo, never()).save(any());
        }

        @Test
        @DisplayName("kommt mit einem Charakter ohne Corporation zurecht")
        void toleratesCharacterWithoutCorporation() {
            Character character = new Character();
            character.setId(1L);

            service.refreshCorporationFaction(character);

            verify(esiService, never()).getCorporationInfo(anyLong());
        }
    }
}
