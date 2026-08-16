package com.eve.own.auth.backend.domain.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Zuordnung der SDE-Schiffsgruppen zu den Dashboard-Kaesten")
class ShipClassTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "Frigate, FRIGATE",
            "Assault Frigate, FRIGATE",
            "Stealth Bomber, FRIGATE",
            "Command Destroyer, DESTROYER",
            "Heavy Assault Cruiser, CRUISER",
            "Logistics, CRUISER",
            "Strategic Cruiser, CRUISER",
            "Command Ship, BATTLECRUISER",
            "Marauder, BATTLESHIP",
            "Carrier, CARRIER",
            "Supercarrier, SUPERCARRIER",
            "Titan, TITAN",
            "Force Auxiliary, FORCE_AUXILIARY",
            "Logistics Cruiser, FORCE_AUXILIARY",
            "Mining Barge, MINING",
            "Exhumer, MINING",
            "Blockade Runner, HAULER",
            "Industrial Command Ship, INDUSTRIAL_COMMAND",
            "Jump Freighter, CAPITAL_INDUSTRIAL",
            "Citadel, CITADEL",
            "Refinery, REFINERY",
            "Engineering Complex, ENGINEERING_COMPLEX"
    })
    @DisplayName("ordnet exakt benannte Gruppen zu")
    void mapsExactGroupNames(String groupName, ShipClass expected) {
        assertThat(ShipClass.ofGroup(groupName)).contains(expected);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Dreadnought", "Lancer Dreadnought", "Force Dreadnought"})
    @DisplayName("erkennt Dreadnoughts ueber die Teilzeichenkette")
    void matchesDreadnoughtsByFragment(String groupName) {
        assertThat(ShipClass.ofGroup(groupName)).contains(ShipClass.DREADNOUGHT);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Large Skill Injector", "Small Skill Injector"})
    @DisplayName("erkennt Skill-Injektoren unabhaengig von der Groesse")
    void matchesSkillInjectors(String groupName) {
        assertThat(ShipClass.ofGroup(groupName)).contains(ShipClass.SKILL_INJECTOR);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Ammunition", "Shield Extender", "Veldspar", "Mining Crystal"})
    @DisplayName("laesst Gruppen ohne Kasten unbeachtet")
    void ignoresUnmappedGroups(String groupName) {
        assertThat(ShipClass.ofGroup(groupName)).isEmpty();
    }

    @Test
    @DisplayName("behandelt einen fehlenden Gruppennamen als unbekannt")
    void handlesNullGroupName() {
        assertThat(ShipClass.ofGroup(null)).isEmpty();
    }

    @Test
    @DisplayName("liefert je Kategorie alle Kaesten mit Startwert null")
    void buildsEmptyCounters() {
        Map<String, Long> subcapital = ShipClass.emptyCounters(ShipCategory.SUBCAPITAL);

        assertThat(subcapital)
                .containsExactly(
                        Map.entry("Frigate", 0L),
                        Map.entry("Destroyer", 0L),
                        Map.entry("Cruiser", 0L),
                        Map.entry("Battlecruiser", 0L),
                        Map.entry("Battleship", 0L));
    }

    @Test
    @DisplayName("deckt mit den Zaehlern jede Kategorie ab")
    void everyCategoryHasCounters() {
        for (ShipCategory category : ShipCategory.values()) {
            assertThat(ShipClass.emptyCounters(category))
                    .as("Kategorie %s", category)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("weist jeden Kasten genau einer Kategorie zu")
    void everyShipClassBelongsToOneCategory() {
        long labelled = Arrays.stream(ShipClass.values())
                .filter(shipClass -> shipClass.label() != null && !shipClass.label().isBlank())
                .count();
        assertThat(labelled).isEqualTo(ShipClass.values().length);
    }
}
