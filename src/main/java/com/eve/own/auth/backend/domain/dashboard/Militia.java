package com.eve.own.auth.backend.domain.dashboard;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Die Fraktionen, deren Miliz das Dashboard ausweist.
 *
 * <p>Die IDs vergibt CCP; sie stehen fest und tauchen nirgends sonst im Code auf.
 * Als benannte Konstanten ist auf einen Blick erkennbar, wofuer {@code 500007}
 * steht - vorher war es eine nackte Zahl in einer if-Kette.</p>
 */
public enum Militia {

    AMARR(500007L, "Amarr"),
    GALLENTE(500004L, "Gallente"),
    MINMATAR(500002L, "Minmatar"),
    CALDARI(500001L, "Caldari"),
    ANGEL(500011L, "Angel"),
    GURISTAS(500010L, "Guristas");

    private final long factionId;
    private final String label;

    Militia(long factionId, String label) {
        this.factionId = factionId;
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<Militia> ofFaction(Long factionId) {
        if (factionId == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(militia -> militia.factionId == factionId)
                .findFirst();
    }

    /** Ein leerer Zaehler je Miliz, in Anzeigereihenfolge. */
    public static Map<String, Long> emptyCounters() {
        Map<String, Long> counters = new LinkedHashMap<>();
        Arrays.stream(values()).forEach(militia -> counters.put(militia.label, 0L));
        return counters;
    }
}
