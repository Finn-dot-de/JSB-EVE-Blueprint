package com.eve.own.auth.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Rollennamen")
class SystemRolesTest {

    @ParameterizedTest(name = "Titel \"{0}\" ergibt {1}")
    @CsvSource({
            "Director, ROLE_DIRECTOR",
            "Fleet Commander, ROLE_FLEET_COMMANDER",
            "A38, ROLE_A38",
            "Senior Member, ROLE_SENIOR_MEMBER",
            "Junior-Member, ROLE_JUNIOR_MEMBER",
            "Recruiter (Trial), ROLE_RECRUITER_TRIAL"
    })
    @DisplayName("leitet aus einem Ingame-Titel einen Rollennamen ab")
    void derivesRoleFromTitle(String title, String expected) {
        assertThat(SystemRoles.fromTitle(title)).isEqualTo(expected);
    }

    @Test
    @DisplayName("laesst kein Trennzeichen am Rand des Namens stehen")
    void trimsSeparatorsAtTheEdges() {
        // Aus "Recruiter (Trial)" wurde frueher ROLE_RECRUITER_TRIAL_ - der
        // haengende Unterstrich stammte allein von der schliessenden Klammer.
        assertThat(SystemRoles.fromTitle("Recruiter (Trial)")).doesNotEndWith("_");
        assertThat(SystemRoles.normalize("(Recruiter)")).isEqualTo("ROLE_RECRUITER");
    }

    @Test
    @DisplayName("faengt Sonderzeichen und Umlaute in Titeln ab")
    void handlesSpecialCharacters() {
        assertThat(SystemRoles.fromTitle("Flotten-Führung!")).startsWith("ROLE_");
        assertThat(SystemRoles.fromTitle("Flotten-Führung!")).doesNotContain("-", "!");
    }

    @Test
    @DisplayName("traegt bei allen Konstanten das Praefix ROLE_")
    void allConstantsCarryPrefix() {
        assertThat(SystemRoles.USER).isEqualTo("ROLE_USER");
        assertThat(SystemRoles.MEMBER).isEqualTo("ROLE_MEMBER");
        assertThat(SystemRoles.GUEST).isEqualTo("ROLE_GUEST");
        assertThat(SystemRoles.CEO).isEqualTo("ROLE_CEO");
        assertThat(SystemRoles.DIRECTOR).isEqualTo("ROLE_DIRECTOR");
        assertThat(SystemRoles.IT_ADMIN).isEqualTo("ROLE_IT_ADMIN");
        assertThat(SystemRoles.MARAUDERS).isEqualTo("ROLE_MARAUDERS_ASSOCIATED");
    }

    @Nested
    @DisplayName("Normalisierung freier Eingaben")
    class Normalization {

        @ParameterizedTest(name = "\"{0}\" ergibt {1}")
        @CsvSource({
                "Recruiter, ROLE_RECRUITER",
                "recruiter, ROLE_RECRUITER",
                "'  Fleet Commander  ', ROLE_FLEET_COMMANDER",
                "role_fc, ROLE_FC",
                "ROLE_FC, ROLE_FC"
        })
        @DisplayName("bringt jede Schreibweise auf dieselbe Form")
        void normalizesAnyNotation(String input, String expected) {
            assertThat(SystemRoles.normalize(input)).isEqualTo(expected);
        }

        @Test
        @DisplayName("verdoppelt ein vorhandenes Praefix nicht")
        void doesNotDoubleThePrefix() {
            // Sonst entstuende ROLE_ROLE_FC - eine Rolle, die nie greift.
            assertThat(SystemRoles.normalize("ROLE_DIRECTOR")).isEqualTo("ROLE_DIRECTOR");
        }

        @ParameterizedTest(name = "\"{0}\" wird abgelehnt")
        @ValueSource(strings = {"", "   ", "ROLE_", "!!!"})
        @DisplayName("weist eine Eingabe ab, aus der kein Name entsteht")
        void rejectsEmptyResults(String input) {
            assertThatThrownBy(() -> SystemRoles.normalize(input))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("weist null ab, statt einen Namen daraus zu bauen")
        void rejectsNull() {
            assertThatThrownBy(() -> SystemRoles.normalize(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Eingebaute Rollen")
    class BuiltIn {

        @Test
        @DisplayName("zaehlt genau die im Code vergebenen Rollen auf")
        void listsTheRolesTheApplicationGrantsItself() {
            assertThat(SystemRoles.builtIn()).containsExactlyInAnyOrder(
                    SystemRoles.USER, SystemRoles.MEMBER, SystemRoles.MARAUDERS,
                    SystemRoles.GUEST, SystemRoles.CEO, SystemRoles.DIRECTOR,
                    SystemRoles.IT_ADMIN);
        }

        @Test
        @DisplayName("erkennt eine eingebaute Rolle wieder")
        void recognizesABuiltInRole() {
            assertThat(SystemRoles.isBuiltIn(SystemRoles.DIRECTOR)).isTrue();
            assertThat(SystemRoles.isBuiltIn("ROLE_RECRUITER")).isFalse();
        }

        @Test
        @DisplayName("gibt die Liste unveraenderlich heraus")
        void handsOutAnImmutableList() {
            // Sonst koennte ein Aufrufer die Grenze zwischen eingebaut und
            // selbst angelegt fuer den Rest der Laufzeit verschieben.
            assertThatThrownBy(() -> SystemRoles.builtIn().add("ROLE_EIGENE"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
