package com.eve.own.auth.backend.domain.industry.service;

/**
 * Wie der Assistent die Kaufen/Bauen-Frage von sich aus beantworten soll.
 *
 * <p>Eine Voreinstellung ersetzt nicht die einzelne Entscheidung - sie setzt sie
 * nur einmal fuer alle. Danach laesst sich jede Zeile weiter von Hand
 * umstellen; die Voreinstellung ist ein Startpunkt, kein Zwang.</p>
 */
public enum BuildStrategy {

    /**
     * Alles fertig kaufen.
     *
     * <p>Der einfachste Weg und oft der richtige: kein Job, keine Blaupause,
     * keine Wartezeit. Wer nur ein Schiff braucht und die ISK hat, faehrt damit
     * am schnellsten.</p>
     */
    BUY_ALL("Alles kaufen"),

    /**
     * Je Bauteil das Guenstigere waehlen.
     *
     * <p>Verglichen werden die Materialkosten mit der tatsaechlichen
     * Materialeffizienz der jeweiligen Blaupause plus Jobgebuehr gegen den
     * Fertigkaufpreis. Der Unterschied ist nicht theoretisch: bei einem Capital
     * Core Temperature Regulator kostet Eigenbau mit ME 0 acht Millionen mehr,
     * mit ME 10 zehn Millionen weniger. Dieselbe Komponente, entgegengesetzte
     * Antwort - deshalb muss gerechnet und darf nicht geschaetzt werden.</p>
     */
    COST_EFFICIENT("Möglichst günstig"),

    /**
     * Alles selbst bauen, was sich bauen laesst.
     *
     * <p>Kosten spielen keine Rolle. Sinnvoll, wenn man ohnehin Material auf
     * Lager hat, die Jobslots leerstehen oder aus Prinzip nichts zukaufen will.
     * PI-Gueter und Mineralien bleiben ausgenommen - die lassen sich per
     * Industriejob gar nicht herstellen.</p>
     */
    BUILD_ALL("Alles selbst bauen");

    private final String label;

    BuildStrategy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Wandelt einen Text in eine Voreinstellung.
     *
     * <p>Unbekanntes faellt auf {@link #BUY_ALL} zurueck statt zu werfen: eine
     * veraltete Oberflaeche soll den Auftrag nicht unbrauchbar machen, und
     * Kaufen ist die Wahl, die niemanden ueberrascht.</p>
     */
    public static BuildStrategy fromName(String name) {
        if (name == null) {
            return BUY_ALL;
        }
        for (BuildStrategy s : values()) {
            if (s.name().equalsIgnoreCase(name.trim())) {
                return s;
            }
        }
        return BUY_ALL;
    }
}
