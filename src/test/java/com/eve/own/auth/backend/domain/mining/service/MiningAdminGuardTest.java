package com.eve.own.auth.backend.domain.mining.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Die Rechtepruefung der Mining-Verwaltung.
 *
 * <p>Sie steht zwischen jedem Mitglied und der Frage, wer wieviel ISK bekommt.
 * Am Endpunkt haengt dieselbe Regel noch einmal als Annotation - die aber gehoert
 * zu einem Einstiegspunkt und faellt bei einem Umbau lautlos weg. Was hier
 * durchkommt, kommt ueberall durch.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Rechtepruefung der Mining-Verwaltung")
class MiningAdminGuardTest {

    private static final Long ID = 100L;

    @Mock private CharacterRepository characterRepo;

    private MiningAdminGuard guard;

    @BeforeEach
    void setUp() {
        guard = new MiningAdminGuard(characterRepo);
    }

    private void charakterMitRollen(String... roles) {
        Character character = new Character();
        character.setId(ID);
        character.setName("Pilot 100");
        character.setRoles(Set.of(roles));
        when(characterRepo.findById(ID)).thenReturn(Optional.of(character));
    }

    @Test
    @DisplayName("laesst CEO, Director und IT-Admin durch")
    void allowsLeadership() {
        // Genau die drei Namen aus AccessRules.LEADERSHIP_OR_IT. Wer sie dort
        // aendert, muss sie hier mitaendern - sonst verteilt ein Rollenkreis
        // Geld, der die Seite gar nicht aufrufen darf.
        for (String rolle : List.of(SystemRoles.CEO, SystemRoles.DIRECTOR, SystemRoles.IT_ADMIN)) {
            charakterMitRollen(rolle);
            assertThat(guard.requireLeadership(ID).getId()).isEqualTo(ID);
        }
    }

    @Test
    @DisplayName("weist ein gewoehnliches Mitglied ab")
    void deniesPlainMember() {
        // OHNE DIESE REGEL saehe jedes angemeldete Mitglied die Steuerakten aller
        // anderen und koennte sich selbst Geld zusprechen.
        charakterMitRollen(SystemRoles.USER, SystemRoles.MEMBER);

        assertThatThrownBy(() -> guard.requireLeadership(ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("weist einen Charakter ganz ohne Rollen ab")
    void deniesRolelessCharacter() {
        charakterMitRollen();

        assertThatThrownBy(() -> guard.requireLeadership(ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("weist eine unbekannte Charakter-ID ab")
    void deniesUnknownCharacter() {
        // Nicht als AccessDeniedException, sondern als fehlerhafte Anfrage: der
        // Unterschied zwischen "darf nicht" und "gibt es nicht" gehoert in die
        // Meldung, sonst sucht jemand nach einer Berechtigung, die gar nicht das
        // Problem ist.
        when(characterRepo.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireLeadership(ID))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
