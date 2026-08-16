package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.character.dto.CharacterDtos;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.Corporation;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
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
@DisplayName("Account-Verwaltung")
class AccountServiceTest {

    private static final Long MAIN_ID = 1000L;
    private static final Long ALT_ID = 1001L;
    private static final Long FOREIGN_ID = 9999L;

    @Mock private CharacterRepository characterRepo;

    private AccountService service;

    @BeforeEach
    void setUp() {
        service = new AccountService(characterRepo);

        when(characterRepo.findById(MAIN_ID)).thenReturn(Optional.of(character(MAIN_ID, MAIN_ID, "Main")));
        when(characterRepo.findByMainCharacterId(MAIN_ID)).thenReturn(List.of(
                character(MAIN_ID, MAIN_ID, "Main"),
                character(ALT_ID, MAIN_ID, "Alt")));
    }

    private static Character character(Long id, Long mainId, String name) {
        return character(id, mainId, name, "Corp Eins");
    }

    private static Character character(Long id, Long mainId, String name, String corporationName) {
        Character character = new Character();
        character.setId(id);
        character.setMainCharacterId(mainId);
        character.setName(name);
        if (corporationName != null) {
            Corporation corporation = new Corporation();
            corporation.setId(98000001L);
            corporation.setName(corporationName);
            character.setCorporation(corporation);
        }
        return character;
    }

    @Nested
    @DisplayName("Eigene Charaktere")
    class OwnCharacters {

        @Test
        @DisplayName("listet Main und Alts und markiert den Main")
        void listsAccountCharacters() {
            List<CharacterDtos.CharacterRefDto> characters = service.charactersOfAccount(MAIN_ID);

            assertThat(characters).hasSize(2);
            assertThat(characters).filteredOn(CharacterDtos.CharacterRefDto::isMain)
                    .singleElement()
                    .satisfies(main -> {
                        assertThat(main.id()).isEqualTo(MAIN_ID);
                        assertThat(main.portraitUrl()).contains("/characters/1000/portrait");
                    });
        }

        @Test
        @DisplayName("liefert einem Alt dieselbe Liste wie seinem Main")
        void altSeesSameAccount() {
            when(characterRepo.findById(ALT_ID)).thenReturn(Optional.of(character(ALT_ID, MAIN_ID, "Alt")));

            assertThat(service.charactersOfAccount(ALT_ID)).hasSize(2);
        }

        @Test
        @DisplayName("weist einen unbekannten Charakter ab")
        void rejectsUnknownCharacter() {
            when(characterRepo.findById(FOREIGN_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.charactersOfAccount(FOREIGN_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Main wechseln")
    class ChangingMain {

        @Test
        @DisplayName("haengt alle Charaktere des Accounts an den neuen Main")
        void movesWholeAccount() {
            service.changeMainCharacter(MAIN_ID, ALT_ID);

            @SuppressWarnings("unchecked")
            var saved = org.mockito.ArgumentCaptor.<List<Character>>captor();
            verify(characterRepo).saveAll(saved.capture());
            assertThat(saved.getValue())
                    .allSatisfy(character -> assertThat(character.getMainCharacterId()).isEqualTo(ALT_ID));
        }

        @Test
        @DisplayName("laesst niemanden einen fremden Charakter vereinnahmen")
        void rejectsForeignCharacter() {
            assertThatThrownBy(() -> service.changeMainCharacter(MAIN_ID, FOREIGN_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("gehoert nicht zu deinem Account");
            verify(characterRepo, never()).saveAll(anyList());
        }
    }

    @Nested
    @DisplayName("Account-Uebersicht der Administration")
    class AdminOverview {

        @Test
        @DisplayName("gruppiert Charaktere zu Accounts und sortiert alphabetisch")
        void groupsAndSorts() {
            when(characterRepo.findAllWithCorporation()).thenReturn(List.of(
                    character(2000L, 2000L, "Zeta"),
                    character(MAIN_ID, MAIN_ID, "Alpha"),
                    character(ALT_ID, MAIN_ID, "Alpha Alt")));

            List<CharacterDtos.AdminAccountDto> accounts = service.allAccounts();

            assertThat(accounts).extracting(CharacterDtos.AdminAccountDto::mainName)
                    .containsExactly("Alpha", "Zeta");
            assertThat(accounts.getFirst().alts()).singleElement()
                    .satisfies(alt -> assertThat(alt.name()).isEqualTo("Alpha Alt"));
        }

        @Test
        @DisplayName("weist einen Charakter ohne Corporation als unbekannt aus")
        void handlesMissingCorporation() {
            when(characterRepo.findAllWithCorporation())
                    .thenReturn(List.of(character(MAIN_ID, MAIN_ID, "Main", null)));

            assertThat(service.allAccounts().getFirst().corporationName()).isEqualTo("Unbekannt");
        }

        @Test
        @DisplayName("nimmt den ersten Charakter, wenn der Main-Datensatz fehlt")
        void fallsBackWhenMainIsMissing() {
            when(characterRepo.findAllWithCorporation())
                    .thenReturn(List.of(character(ALT_ID, MAIN_ID, "Nur ein Alt")));

            assertThat(service.allAccounts()).singleElement()
                    .satisfies(account -> assertThat(account.mainName()).isEqualTo("Nur ein Alt"));
        }

        @Test
        @DisplayName("sortiert die Alts eines Accounts alphabetisch")
        void sortsAlts() {
            when(characterRepo.findAllWithCorporation()).thenReturn(List.of(
                    character(MAIN_ID, MAIN_ID, "Main"),
                    character(1002L, MAIN_ID, "Zulu"),
                    character(ALT_ID, MAIN_ID, "Alfa")));

            assertThat(service.allAccounts().getFirst().alts())
                    .extracting(CharacterDtos.AdminAccountCharDto::name)
                    .containsExactly("Alfa", "Zulu");
        }
    }
}
