package com.eve.own.auth.backend.domain.dashboard;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fasst die rund sechzig SDE-Schiffsgruppen zu den Kaesten zusammen, die das
 * Dashboard zeigt.
 *
 * <p>Vorher war diese Zuordnung eine ueber sechzig Zeilen lange if-else-Kette.
 * Als Tabelle laesst sie sich lesen wie eine Liste - und eine neue Gruppe ist
 * eine Zeile, kein weiterer Zweig.</p>
 *
 * <p>Die Reihenfolge der Konstanten ist zugleich die Anzeigereihenfolge innerhalb
 * ihres {@link ShipCategory Kastens} und die Reihenfolge der Pruefung. Beides
 * faellt zusammen, weil sich die Zuordnungen nicht ueberschneiden.</p>
 */
public enum ShipClass {

    // --- Subcapitals ---
    FRIGATE(ShipCategory.SUBCAPITAL, "Frigate",
            Set.of("Frigate", "Assault Frigate", "Covert Ops", "Electronic Attack Ship", "Interceptor",
                    "Logistics Frigate", "Stealth Bomber", "Expedition Frigate")),
    DESTROYER(ShipCategory.SUBCAPITAL, "Destroyer",
            Set.of("Destroyer", "Command Destroyer", "Tactical Destroyer")),
    CRUISER(ShipCategory.SUBCAPITAL, "Cruiser",
            Set.of("Cruiser", "Heavy Assault Cruiser", "Heavy Interdiction Cruiser", "Logistics",
                    "Force Recon Ship", "Combat Recon Ship", "Strategic Cruiser")),
    BATTLECRUISER(ShipCategory.SUBCAPITAL, "Battlecruiser",
            Set.of("Combat Battlecruiser", "Attack Battlecruiser", "Command Ship")),
    BATTLESHIP(ShipCategory.SUBCAPITAL, "Battleship",
            Set.of("Battleship", "Black Ops", "Marauder")),

    // --- Capitals ---
    /** Ueber Teilzeichenkette, weil die SDE mehrere Dreadnought-Gruppen fuehrt. */
    DREADNOUGHT(ShipCategory.CAPITAL, "Dreadnought", MatchMode.CONTAINS, Set.of("Dreadnought")),
    CARRIER(ShipCategory.CAPITAL, "Carrier", Set.of("Carrier")),
    SUPERCARRIER(ShipCategory.CAPITAL, "Supercarrier", Set.of("Supercarrier")),
    FORCE_AUXILIARY(ShipCategory.CAPITAL, "Force Auxiliary",
            Set.of("Force Auxiliary", "Logistics Cruiser")),
    TITAN(ShipCategory.CAPITAL, "Titan", Set.of("Titan")),

    // --- Industrie ---
    MINING(ShipCategory.INDUSTRIAL, "Mining", Set.of("Mining Barge", "Exhumer")),
    HAULER(ShipCategory.INDUSTRIAL, "Hauler",
            Set.of("Hauler", "Blockade Runner", "Deep Space Transport", "Industrial Ship",
                    "Transport Ship")),
    INDUSTRIAL_COMMAND(ShipCategory.INDUSTRIAL, "Industrial Command Ship",
            Set.of("Industrial Command Ship")),
    CAPITAL_INDUSTRIAL(ShipCategory.INDUSTRIAL, "Capital Industrial",
            Set.of("Freighter", "Jump Freighter", "Capital Industrial Ship")),

    // --- Bemerkenswerte Handelsgueter ---
    SKILL_INJECTOR(ShipCategory.NOTABLE, "Skill Injector", MatchMode.CONTAINS, Set.of("Skill Injector")),
    SKILL_EXTRACTOR(ShipCategory.NOTABLE, "Skill Extractor", MatchMode.CONTAINS, Set.of("Skill Extractor")),

    // --- Strukturen ---
    CITADEL(ShipCategory.STRUCTURES, "Citadel", Set.of("Citadel")),
    REFINERY(ShipCategory.STRUCTURES, "Refinery", Set.of("Refinery")),
    ENGINEERING_COMPLEX(ShipCategory.STRUCTURES, "Engineering Complex", Set.of("Engineering Complex"));

    /** Wie ein SDE-Gruppenname mit den hinterlegten Namen verglichen wird. */
    private enum MatchMode { EXACT, CONTAINS }

    private final ShipCategory category;
    private final String label;
    private final MatchMode matchMode;
    private final Set<String> groupNames;

    ShipClass(ShipCategory category, String label, Set<String> groupNames) {
        this(category, label, MatchMode.EXACT, groupNames);
    }

    ShipClass(ShipCategory category, String label, MatchMode matchMode, Set<String> groupNames) {
        this.category = category;
        this.label = label;
        this.matchMode = matchMode;
        this.groupNames = groupNames;
    }

    public ShipCategory category() {
        return category;
    }

    /** Die im Dashboard angezeigte Bezeichnung. */
    public String label() {
        return label;
    }

    private boolean matches(String groupName) {
        if (matchMode == MatchMode.CONTAINS) {
            return groupNames.stream().anyMatch(groupName::contains);
        }
        return groupNames.contains(groupName);
    }

    /** Der Kasten, in den eine SDE-Gruppe gehoert - leer, wenn keiner passt. */
    public static Optional<ShipClass> ofGroup(String groupName) {
        if (groupName == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(shipClass -> shipClass.matches(groupName))
                .findFirst();
    }

    /**
     * Ein leerer Zaehler je Kasten einer Kategorie, in Anzeigereihenfolge.
     *
     * <p>Das Frontend erwartet auch die Kaesten, in denen nichts liegt - sonst
     * springt das Raster je nach Bestand.</p>
     */
    public static Map<String, Long> emptyCounters(ShipCategory category) {
        Map<String, Long> counters = new LinkedHashMap<>();
        Arrays.stream(values())
                .filter(shipClass -> shipClass.category == category)
                .forEach(shipClass -> counters.put(shipClass.label, 0L));
        return counters;
    }
}
