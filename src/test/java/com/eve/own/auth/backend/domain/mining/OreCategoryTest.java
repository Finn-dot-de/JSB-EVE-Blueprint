package com.eve.own.auth.backend.domain.mining;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Steuerklassen abbaubarer Typen")
class OreCategoryTest {

    @ParameterizedTest(name = "SDE-Gruppe {0} gehoert zu {1}")
    @CsvSource({
            "423, ICE",
            "711, GAS",
            "1884, MOON",
            "1920, MOON",
            "1921, MOON",
            "1922, MOON",
            "1923, MOON"
    })
    @DisplayName("ordnet die bekannten Gruppen ihrer Klasse zu")
    void mapsKnownGroups(long groupId, OreCategory expected) {
        assertThat(OreCategory.ofGroup(groupId)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "Gruppe {0}")
    @ValueSource(longs = {18, 462, 1996, 0})
    @DisplayName("faellt fuer unbekannte Gruppen auf ORE zurueck")
    void fallsBackToOre(long groupId) {
        assertThat(OreCategory.ofGroup(groupId)).isEqualTo(OreCategory.ORE);
    }

    @Test
    @DisplayName("behandelt eine fehlende Gruppe wie eine unbekannte")
    void handlesNullGroup() {
        assertThat(OreCategory.ofGroup(null)).isEqualTo(OreCategory.ORE);
    }

    @Test
    @DisplayName("gibt den Namen als Datenbankwert zurueck")
    void exposesDbValue() {
        assertThat(OreCategory.MOON.dbValue()).isEqualTo("MOON");
        assertThat(OreCategory.ORE.dbValue()).isEqualTo("ORE");
    }

    @Test
    @DisplayName("ordnet jede Gruppe genau einer Klasse zu")
    void assignsEachGroupToExactlyOneCategory() {
        // Ueberschneiden sich zwei Klassen, entscheidet die Deklarationsreihenfolge
        // - ein Verhalten, auf das sich niemand verlassen sollte.
        long[] allGroupIds = {423L, 711L, 1884L, 1920L, 1921L, 1922L, 1923L};
        for (long groupId : allGroupIds) {
            long matches = java.util.Arrays.stream(OreCategory.values())
                    .filter(category -> category != OreCategory.ORE)
                    .filter(category -> category == OreCategory.ofGroup(groupId))
                    .count();
            assertThat(matches).as("Gruppe %d", groupId).isEqualTo(1);
        }
    }
}
