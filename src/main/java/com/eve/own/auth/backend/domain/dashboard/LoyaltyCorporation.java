package com.eve.own.auth.backend.domain.dashboard;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Die NPC-Corporations, deren Loyalitaetspunkte das Dashboard einzeln ausweist.
 *
 * <p>Alle uebrigen fliessen nur in die Gesamtsumme ein. Paragon steht dabei
 * gesondert: seine Punkte heissen ingame Evermarks und werden auch so gezeigt.</p>
 */
public enum LoyaltyCorporation {

    CONCORD(1000125L, "CONCORD"),
    FEDERAL_ADMINISTRATION(1000119L, "FederalAdmin"),
    BLOOD_RAIDERS(1000134L, "BloodRaiders"),
    FREEDOM_EXTENSION(1000061L, "FreedomExtension");

    /** Paragon - die Quelle der Evermarks, die getrennt ausgewiesen werden. */
    public static final long PARAGON_CORPORATION_ID = 1000419L;

    /** Schluessel der Gesamtsumme in der Antwort. */
    public static final String TOTAL_KEY = "Total";

    private final long corporationId;
    private final String key;

    LoyaltyCorporation(long corporationId, String key) {
        this.corporationId = corporationId;
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<LoyaltyCorporation> ofCorporation(long corporationId) {
        return Arrays.stream(values())
                .filter(corporation -> corporation.corporationId == corporationId)
                .findFirst();
    }

    /** Ein leerer Zaehler je ausgewiesener Corporation, in Anzeigereihenfolge. */
    public static Map<String, Long> emptyCounters() {
        Map<String, Long> counters = new LinkedHashMap<>();
        Arrays.stream(values()).forEach(corporation -> counters.put(corporation.key, 0L));
        return counters;
    }
}
