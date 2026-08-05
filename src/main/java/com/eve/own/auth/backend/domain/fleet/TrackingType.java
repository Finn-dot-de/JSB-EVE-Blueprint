package com.eve.own.auth.backend.domain.fleet;

/**
 * Wie die Teilnahme an einer Flotte erfasst wird.
 *
 * <p>Der Name der Konstante ist zugleich der in {@code fleet_events} gespeicherte
 * Wert. Die Spalte bleibt eine Zeichenkette, damit aeltere Datensaetze mit
 * abweichenden Werten das Lesen nicht sprengen.</p>
 */
public enum TrackingType {

    /**
     * Der FC laesst die Anwesenheit periodisch aus der Ingame-Flotte auslesen.
     * Verlangt, dass der FC online und tatsaechlich in einer Flotte ist.
     */
    LIVE,

    /**
     * Der FC verteilt einen Link, ueber den sich Teilnehmer selbst eintragen.
     * Der Link laeuft nach einer festgelegten Zeit ab.
     */
    LINK;

    /** Vorgabe, wenn ein Aufrufer nichts angibt. */
    public static final TrackingType DEFAULT = LIVE;

    public String dbValue() {
        return name();
    }

    public boolean matches(String storedValue) {
        return name().equals(storedValue);
    }

    /** Wandelt eine gespeicherte oder uebergebene Zeichenkette um; unbekannt gilt als {@link #DEFAULT}. */
    public static TrackingType of(String value) {
        if (value == null) {
            return DEFAULT;
        }
        for (TrackingType type : values()) {
            if (type.matches(value)) {
                return type;
            }
        }
        return DEFAULT;
    }
}
