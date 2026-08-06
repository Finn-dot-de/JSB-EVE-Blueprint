package com.eve.own.auth.backend.domain.character.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Charakter und Account-Zugehoerigkeit")
class CharacterTest {

    private static Character character(Long id, Long mainCharacterId) {
        Character character = new Character();
        character.setId(id);
        character.setMainCharacterId(mainCharacterId);
        return character;
    }

    @Nested
    @DisplayName("Account-Zuordnung")
    class AccountResolution {

        @Test
        @DisplayName("ein Alt gehoert zum Account seines Mains")
        void altBelongsToMain() {
            Character alt = character(2L, 1L);

            assertThat(alt.getAccountId()).isEqualTo(1L);
            assertThat(alt.isMain()).isFalse();
        }

        @Test
        @DisplayName("ein Main mit eigener ID im Feld ist sein eigener Account")
        void mainWithExplicitSelfReference() {
            Character main = character(1L, 1L);

            assertThat(main.getAccountId()).isEqualTo(1L);
            assertThat(main.isMain()).isTrue();
        }

        @Test
        @DisplayName("ein Charakter ohne Eintrag ist sein eigener Account")
        void characterWithoutMainReference() {
            // Beide Schreibweisen kommen im Datenbestand vor - der Charakter
            // muss sie gleich beantworten.
            Character standalone = character(7L, null);

            assertThat(standalone.getAccountId()).isEqualTo(7L);
            assertThat(standalone.isMain()).isTrue();
        }
    }

    @Nested
    @DisplayName("Rollenpruefung")
    class Roles {

        @Test
        @DisplayName("findet eine vorhandene Rolle")
        void findsExistingRole() {
            Character character = character(1L, 1L);
            character.setRoles(Set.of("ROLE_USER", "ROLE_DIRECTOR"));

            assertThat(character.hasRole("ROLE_DIRECTOR")).isTrue();
            assertThat(character.hasRole("ROLE_CEO")).isFalse();
        }

        @Test
        @DisplayName("kommt mit einem Charakter ohne Rollen zurecht")
        void toleratesMissingRoles() {
            Character character = character(1L, 1L);
            character.setRoles(null);

            assertThat(character.hasRole("ROLE_USER")).isFalse();
        }
    }

    @Test
    @DisplayName("haelt die uebrigen Stammdaten")
    void keepsBasicFields() {
        Instant expiry = Instant.parse("2026-01-01T00:00:00Z");
        Character character = character(1L, 1L);
        character.setName("Pilot Eins");
        character.setTokenExpiry(expiry);

        assertThat(character.getName()).isEqualTo("Pilot Eins");
        assertThat(character.getTokenExpiry()).isEqualTo(expiry);
    }
}
