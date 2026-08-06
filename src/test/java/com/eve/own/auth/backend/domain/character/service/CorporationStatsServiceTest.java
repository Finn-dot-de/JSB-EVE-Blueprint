package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.CorporationScope;
import com.eve.own.auth.backend.domain.character.dto.CharacterDtos;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.Corporation;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Mitglieder-Bilanz einer Corporation")
class CorporationStatsServiceTest {

    private static final Long MAIN_CORP = 98000001L;
    private static final Long MAIN_ID = 1000L;
    private static final Long ALT_ID = 1001L;
    private static final Long UNREGISTERED_ID = 5000L;

    @Mock private CharacterRepository characterRepo;
    @Mock private EsiService esiService;
    @Mock private AuthService authService;

    private CorporationStatsService service;

    @BeforeEach
    void setUp() {
        service = new CorporationStatsService(characterRepo, esiService, authService,
                new CorporationScope(MAIN_CORP, ""));

        when(esiService.getCorporationInfo(anyLong())).thenReturn(
                new EsiService.EsiCorporationResponse("Corp Eins", "CORP", null, null));
        when(esiService.getCorporationMembers(anyLong(), anyString())).thenReturn(EsiResponse.empty());
        when(authService.getValidAccessToken(any())).thenReturn("token");
        when(characterRepo.findByCorporationId(MAIN_CORP)).thenReturn(List.of());
        when(characterRepo.findById(anyLong())).thenReturn(Optional.empty());
    }

    private static Character character(Long id, Long mainId, String name, String... roles) {
        Corporation corporation = new Corporation();
        corporation.setId(MAIN_CORP);

        Character character = new Character();
        character.setId(id);
        character.setMainCharacterId(mainId);
        character.setName(name);
        character.setCorporation(corporation);
        character.setRoles(Set.of(roles));
        return character;
    }

    private CharacterDtos.CorpStatsDto stats() {
        return service.statsForAllCorporations().getFirst();
    }

    @Nested
    @DisplayName("Zaehlung")
    class Counting {

        @Test
        @DisplayName("zaehlt Accounts, Alts und Charaktere getrennt")
        void countsRegisteredCharacters() {
            Character main = character(MAIN_ID, MAIN_ID, "Main");
            when(characterRepo.findByCorporationId(MAIN_CORP))
                    .thenReturn(List.of(main, character(ALT_ID, MAIN_ID, "Alt")));
            when(characterRepo.findById(MAIN_ID)).thenReturn(Optional.of(main));

            CharacterDtos.CorpStatsDto stats = stats();

            assertThat(stats.registeredMains()).isEqualTo(1);
            assertThat(stats.registeredAlts()).isEqualTo(1);
            assertThat(stats.totalRegisteredChars()).isEqualTo(2);
        }

        @Test
        @DisplayName("nennt die Mitgliederzahl laut ESI")
        void reportsEsiMemberCount() {
            when(characterRepo.findByCorporationId(MAIN_CORP))
                    .thenReturn(List.of(character(MAIN_ID, MAIN_ID, "Main")));
            when(esiService.getCorporationMembers(anyLong(), anyString()))
                    .thenReturn(EsiResponse.changed(new Long[]{MAIN_ID, UNREGISTERED_ID}, null, null));
            when(esiService.getUniverseNames(anyList())).thenReturn(new EsiService.EsiIdName[]{
                    new EsiService.EsiIdName(UNREGISTERED_ID, "Fremder Pilot", "character")});

            CharacterDtos.CorpStatsDto stats = stats();

            assertThat(stats.totalEsiMembers()).isEqualTo(2);
            assertThat(stats.unauthedMembers()).singleElement()
                    .satisfies(unauthed -> assertThat(unauthed.name()).isEqualTo("Fremder Pilot"));
        }

        @Test
        @DisplayName("meldet ohne ESI-Auskunft null Mitglieder statt zu scheitern")
        void survivesMissingEsiData() {
            CharacterDtos.CorpStatsDto stats = stats();

            assertThat(stats.totalEsiMembers()).isZero();
            assertThat(stats.unauthedMembers()).isEmpty();
        }

        @Test
        @DisplayName("nennt eine Corporation mit unbekanntem Namen nachvollziehbar")
        void namesUnknownCorporation() {
            when(esiService.getCorporationInfo(anyLong())).thenThrow(new RuntimeException("ESI weg"));

            assertThat(stats().corpName()).isEqualTo("Unknown Corp (98000001)");
        }
    }

    @Nested
    @DisplayName("Token-Beschaffung")
    class TokenProvider {

        @Test
        @DisplayName("nimmt bevorzugt einen Charakter der Fuehrungsebene")
        void prefersLeadership() {
            Character member = character(1L, 1L, "Mitglied", SystemRoles.MEMBER);
            Character director = character(2L, 2L, "Director", SystemRoles.DIRECTOR);
            when(characterRepo.findByCorporationId(MAIN_CORP)).thenReturn(List.of(member, director));

            service.statsForAllCorporations();

            org.mockito.Mockito.verify(authService).getValidAccessToken(director);
        }

        @Test
        @DisplayName("nimmt notfalls irgendeinen Charakter der Corporation")
        void fallsBackToAnyCharacter() {
            Character member = character(1L, 1L, "Mitglied", SystemRoles.MEMBER);
            when(characterRepo.findByCorporationId(MAIN_CORP)).thenReturn(List.of(member));

            service.statsForAllCorporations();

            org.mockito.Mockito.verify(authService).getValidAccessToken(member);
        }

        @Test
        @DisplayName("kommt ohne jeden Charakter zurecht")
        void survivesEmptyCorporation() {
            assertThat(stats().authedMembers()).isEmpty();
            org.mockito.Mockito.verify(authService, org.mockito.Mockito.never())
                    .getValidAccessToken(any());
        }

        @Test
        @DisplayName("laeuft weiter, wenn die Mitgliederliste nicht abrufbar ist")
        void survivesMemberListFailure() {
            when(characterRepo.findByCorporationId(MAIN_CORP))
                    .thenReturn(List.of(character(MAIN_ID, MAIN_ID, "Main")));
            when(esiService.getCorporationMembers(anyLong(), anyString()))
                    .thenThrow(new RuntimeException("ESI weg"));

            assertThat(stats().totalEsiMembers()).isZero();
        }
    }

    @Nested
    @DisplayName("Gruppierung der registrierten Mitglieder")
    class AuthedMembers {

        @Test
        @DisplayName("fasst Main und Alts zu einem Account zusammen")
        void groupsByAccount() {
            Character main = character(MAIN_ID, MAIN_ID, "Main");
            when(characterRepo.findByCorporationId(MAIN_CORP))
                    .thenReturn(List.of(main, character(ALT_ID, MAIN_ID, "Alt")));
            when(characterRepo.findById(MAIN_ID)).thenReturn(Optional.of(main));

            assertThat(stats().authedMembers()).singleElement().satisfies(account -> {
                assertThat(account.mainName()).isEqualTo("Main");
                assertThat(account.alts()).singleElement()
                        .satisfies(alt -> assertThat(alt.name()).isEqualTo("Alt"));
            });
        }

        @Test
        @DisplayName("markiert einen Account, dessen Main woanders sitzt")
        void marksExternalMain() {
            // Sonst waere unklar, warum ein Account ohne seinen Main erscheint.
            Character externalMain = character(MAIN_ID, MAIN_ID, "Externer Main");
            when(characterRepo.findByCorporationId(MAIN_CORP))
                    .thenReturn(List.of(character(ALT_ID, MAIN_ID, "Alt")));
            when(characterRepo.findById(MAIN_ID)).thenReturn(Optional.of(externalMain));

            assertThat(stats().authedMembers()).singleElement()
                    .satisfies(account ->
                            assertThat(account.mainName()).isEqualTo("Externer Main [Main extern]"));
        }

        @Test
        @DisplayName("sortiert die Accounts alphabetisch")
        void sortsAccounts() {
            Character zeta = character(2000L, 2000L, "Zeta");
            Character alpha = character(MAIN_ID, MAIN_ID, "Alpha");
            when(characterRepo.findByCorporationId(MAIN_CORP)).thenReturn(List.of(zeta, alpha));
            when(characterRepo.findById(2000L)).thenReturn(Optional.of(zeta));
            when(characterRepo.findById(MAIN_ID)).thenReturn(Optional.of(alpha));

            assertThat(stats().authedMembers())
                    .extracting(CharacterDtos.AuthedMainDto::mainName)
                    .containsExactly("Alpha", "Zeta");
        }
    }

    @Nested
    @DisplayName("Nicht registrierte Mitglieder")
    class UnauthedMembers {

        @BeforeEach
        void corpHasOneRegisteredCharacter() {
            Character main = character(MAIN_ID, MAIN_ID, "Main");
            when(characterRepo.findByCorporationId(MAIN_CORP)).thenReturn(List.of(main));
            when(characterRepo.findById(MAIN_ID)).thenReturn(Optional.of(main));
        }

        @Test
        @DisplayName("loest die Namen einzeln auf, wenn die Bulk-Abfrage scheitert")
        void resolvesNamesIndividually() {
            when(esiService.getCorporationMembers(anyLong(), anyString()))
                    .thenReturn(EsiResponse.changed(new Long[]{UNREGISTERED_ID}, null, null));
            when(esiService.getUniverseNames(anyList())).thenReturn(null);
            when(esiService.getCharacter(UNREGISTERED_ID)).thenReturn(EsiResponse.changed(
                    new EsiService.EsiCharacterResponse("Einzeln aufgeloest", MAIN_CORP), null, null));

            assertThat(stats().unauthedMembers()).singleElement()
                    .satisfies(unauthed -> assertThat(unauthed.name()).isEqualTo("Einzeln aufgeloest"));
        }

        @Test
        @DisplayName("benennt einen gar nicht aufloesbaren Piloten nachvollziehbar")
        void namesUnresolvablePilot() {
            when(esiService.getCorporationMembers(anyLong(), anyString()))
                    .thenReturn(EsiResponse.changed(new Long[]{UNREGISTERED_ID}, null, null));
            when(esiService.getUniverseNames(anyList())).thenReturn(new EsiService.EsiIdName[0]);
            when(esiService.getCharacter(UNREGISTERED_ID)).thenThrow(new RuntimeException("ESI weg"));

            assertThat(stats().unauthedMembers()).singleElement()
                    .satisfies(unauthed ->
                            assertThat(unauthed.name()).isEqualTo("Unbekannter Pilot (5000)"));
        }

        @Test
        @DisplayName("uebergeht bereits registrierte Charaktere und Dubletten")
        void skipsKnownAndDuplicateIds() {
            when(esiService.getCorporationMembers(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new Long[]{MAIN_ID, UNREGISTERED_ID, UNREGISTERED_ID, null},
                            null, null));
            when(esiService.getUniverseNames(anyList())).thenReturn(new EsiService.EsiIdName[]{
                    new EsiService.EsiIdName(UNREGISTERED_ID, "Fremder", "character")});

            assertThat(stats().unauthedMembers()).hasSize(1);
        }
    }
}
